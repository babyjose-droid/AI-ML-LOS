package com.rhythm.los.notify;

import com.rhythm.los.org.AppUser;
import com.rhythm.los.org.AppUserRepository;
import com.rhythm.los.security.CurrentUser;
import com.rhythm.los.workflow.MyWorkService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.*;

/**
 * Alerts: each officer's new My work tasks become notifications, shown in the mobile app and pushed to their phones.
 * Worked out every minute for everyone (and at once when an officer opens their alerts).
 */
@Service
public class NotifyService {
    private static final Logger log = LoggerFactory.getLogger(NotifyService.class);
    private final JdbcTemplate jdbc;
    private final AppUserRepository users;
    private final MyWorkService work;
    private final ExpoPush push;

    public NotifyService(JdbcTemplate jdbc, AppUserRepository users, MyWorkService work, ExpoPush push) {
        this.jdbc = jdbc;
        this.users = users;
        this.work = work;
        this.push = push;
    }

    public record Alert(Long id, String title, String body, Long applicationId, String action, java.time.Instant createdAt, java.time.Instant readAt) {}

    // ---------------------------------------------------------------- devices
    public void register(CurrentUser u, String token, String platform) {
        if (token == null || token.isBlank() || token.length() > 255) throw com.rhythm.los.common.ApiException.unprocessable("TOKEN_REQUIRED", "A push token is needed");
        jdbc.update("""
            insert into push_device (username, token, platform) values (?, ?, ?)
            on conflict (token) do update set username = excluded.username, platform = excluded.platform, last_seen_at = now()""", u.username(), token, platform);
    }

    public void unregister(CurrentUser u, String token) {
        jdbc.update("delete from push_device where token = ? and username = ?", token, u.username());
    }

    // ---------------------------------------------------------------- alerts
    public Map<String, Object> list(CurrentUser u) {
        refresh(u, false);
        List<Alert> alerts = jdbc.query("""
            select id, title, body, application_id, task_key, created_at, read_at from user_notification where username = ?
            order by read_at is not null, created_at desc limit 100""", (rs, i) -> new Alert(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getObject(4) == null ? null : rs.getLong(4), rs.getString(5).split(":")[0], rs.getTimestamp(6).toInstant(),
                rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant()), u.username());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("alerts", alerts);
        m.put("unread", alerts.stream().filter(a -> a.readAt() == null).count());
        m.put("devices", jdbc.queryForObject("select count(*) from push_device where username = ?", Long.class, u.username()));
        m.put("pushEnabled", push.enabled());
        return m;
    }

    public void read(CurrentUser u, List<Long> ids) {
        if (ids == null || ids.isEmpty()) jdbc.update("update user_notification set read_at = now() where username = ? and read_at is null", u.username());
        else for (Long id : ids) jdbc.update("update user_notification set read_at = now() where username = ? and id = ? and read_at is null", u.username(), id);
    }

    /** Every active officer, in the current tenant (TenantJobs runs it per tenant). */
    public void sweep() {
        for (AppUser a : users.findAll()) {
            if (!a.isActive() || a.getRole() == null) continue;
            try { refresh(new CurrentUser(a.getUsername(), a.getRole(), a.getFullName(), a.getBranchId()), true); }
            catch (RuntimeException e) { log.warn("alerts for {} not worked out: {}", a.getUsername(), e.getMessage()); }
        }
    }

    /** Brings one officer's alerts in line with their tasks; pushes the new ones when asked to. */
    void refresh(CurrentUser u, boolean sendPush) {
        Map<String, MyWorkService.Task> current = new LinkedHashMap<>();
        for (MyWorkService.Task t : work.tasks(u)) current.putIfAbsent(NotifyRules.key(t.action(), t.applicationId(), t.appNo()), t);
        List<String> kept = jdbc.queryForList("select task_key from user_notification where username = ?", String.class, u.username());
        boolean first = jdbc.update("insert into notify_state (username) values (?) on conflict do nothing", u.username()) == 1;
        NotifyRules.Plan plan = NotifyRules.plan(current.keySet(), kept, first);
        for (String k : plan.drop()) jdbc.update("delete from user_notification where username = ? and task_key = ?", u.username(), k);
        for (String k : plan.silent()) insert(u, k, current.get(k), true);
        for (String k : plan.announce()) insert(u, k, current.get(k), false);
        if (!sendPush) return;
        List<Map<String, Object>> due = jdbc.queryForList("""
            select id, title, body, application_id from user_notification where username = ? and read_at is null and pushed_at is null order by id""", u.username());
        if (due.isEmpty()) return;
        List<String> tokens = jdbc.queryForList("select token from push_device where username = ?", String.class, u.username());
        if (!tokens.isEmpty() && push.enabled()) {
            List<ExpoPush.Message> msgs = new ArrayList<>();
            for (Map<String, Object> n : due) for (String tok : tokens) {
                Map<String, Object> data = new HashMap<>();
                if (n.get("application_id") != null) data.put("applicationId", n.get("application_id"));
                msgs.add(new ExpoPush.Message(tok, String.valueOf(n.get("title")), n.get("body") == null ? "" : String.valueOf(n.get("body")), data));
            }
            for (String gone : push.send(msgs)) jdbc.update("delete from push_device where token = ?", gone);
        }
        for (Map<String, Object> n : due) jdbc.update("update user_notification set pushed_at = now() where id = ?", n.get("id"));
    }

    private void insert(CurrentUser u, String key, MyWorkService.Task t, boolean read) {
        String title = t.task();
        String body = t.applicant() == null ? (t.appNo() == null ? "" : t.appNo())
                : t.applicant() + " · " + t.appNo() + (t.product() == null ? "" : " · " + t.product()) + (t.amount() > 0 ? " · ₹" + String.format("%,.0f", t.amount()) : "");
        jdbc.update("""
            insert into user_notification (username, task_key, application_id, title, body, read_at, pushed_at) values (?, ?, ?, ?, ?, ?, ?)
            on conflict (username, task_key) do nothing""", u.username(), key, t.applicationId(), cut(title, 200), cut(body, 500),
            read ? new Timestamp(System.currentTimeMillis()) : null, read ? new Timestamp(System.currentTimeMillis()) : null);
    }

    private static String cut(String s, int n) { return s == null ? null : s.length() > n ? s.substring(0, n) : s; }
}
