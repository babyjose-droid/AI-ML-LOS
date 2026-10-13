package com.rhythm.los.report;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/** Management information: funnel, decisions, turnaround times, operations and control indicators for a date range. */
@Service
public class ReportService {
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private final JdbcTemplate jdbc;

    public ReportService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Range(Timestamp from, Timestamp to, LocalDate fromDay, LocalDate toDay) {
        static Range of(LocalDate from, LocalDate to) {
            LocalDate t = to == null ? LocalDate.now(IST) : to;
            LocalDate f = from == null ? t.minusDays(29) : from;
            if (f.isAfter(t)) { LocalDate x = f; f = t; t = x; }
            if (f.plusDays(731).isBefore(t)) f = t.minusDays(731);
            return new Range(Timestamp.from(f.atStartOfDay(IST).toInstant()), Timestamp.from(t.plusDays(1).atStartOfDay(IST).toInstant()), f, t);
        }
    }

    public Map<String, Object> summary(LocalDate from, LocalDate to) {
        Range r = Range.of(from, to);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", r.fromDay().toString());
        out.put("to", r.toDay().toString());
        out.put("generatedAt", Instant.now().toString());
        out.put("funnel", funnel(r));
        out.put("products", jdbc.queryForList("""
                select a.product_code as product, count(*) as applications, coalesce(sum(a.loan_amount),0) as requested,
                       count(*) filter (where a.app_state = 'DISBURSED') as disbursed,
                       coalesce(sum(d.gross_amount) filter (where d.status = 'SUCCESS'),0) as disbursed_amount,
                       count(*) filter (where a.app_state = 'REJECTED') as rejected
                from loan_application a left join disbursement d on d.application_id = a.id and d.status = 'SUCCESS'
                where a.created_at >= ? and a.created_at < ? group by a.product_code order by applications desc""", r.from(), r.to()));
        out.put("branches", jdbc.queryForList("""
                select coalesce(b.name, 'Unassigned') as branch, count(*) as applications,
                       count(*) filter (where a.app_state = 'DISBURSED') as disbursed,
                       coalesce(sum(a.loan_amount) filter (where a.app_state = 'DISBURSED'),0) as disbursed_amount
                from loan_application a left join branch b on b.id = a.branch_id
                where a.created_at >= ? and a.created_at < ? group by b.name order by applications desc""", r.from(), r.to()));
        out.put("decisions", decisions(r));
        out.put("turnaround", turnaround(r));
        out.put("daily", daily(r));
        out.put("operations", operations(r));
        out.put("controls", controls(r));
        return out;
    }

    private Map<String, Object> funnel(Range r) {
        return jdbc.queryForMap("""
                select count(*) as created,
                       count(*) filter (where app_state <> 'DRAFT') as submitted,
                       count(*) filter (where decision_state <> 'PENDING') as decided,
                       count(*) filter (where decision_state in ('APPROVE','APPROVE_WITH_CONDITIONS')) as engine_approved,
                       count(*) filter (where decision_state = 'REFER') as referred,
                       count(*) filter (where sanction_state in ('SANCTIONED','KFS_ACCEPTED') or app_state = 'DISBURSED') as sanctioned,
                       count(*) filter (where app_state = 'DISBURSED') as disbursed,
                       count(*) filter (where app_state = 'REJECTED') as rejected,
                       count(*) filter (where app_state = 'WITHDRAWN') as withdrawn,
                       coalesce(sum(loan_amount) filter (where app_state = 'DISBURSED'),0) as disbursed_amount
                from loan_application where created_at >= ? and created_at < ?""", r.from(), r.to());
    }

    private Map<String, Object> decisions(Range r) {
        String latest = """
                with latest as (select distinct on (d.application_id) d.* from decision_record d
                                join loan_application a on a.id = d.application_id
                                where a.created_at >= ? and a.created_at < ? order by d.application_id, d.id desc)
                """;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mix", jdbc.queryForList(latest + "select decision, count(*) as n, round(avg(pd)::numeric, 4) as avg_pd from latest group by decision order by n desc", r.from(), r.to()));
        m.put("bands", jdbc.queryForList(latest + "select risk_band as band, count(*) as n from latest group by risk_band order by risk_band", r.from(), r.to()));
        m.put("policies", jdbc.queryForList(latest + "select policy_version as policy, count(*) as n from latest group by policy_version order by policy_version", r.from(), r.to()));
        return m;
    }

    private Map<String, Object> turnaround(Range r) {
        // hours between milestones, median and 90th percentile, for applications created in the range
        return jdbc.queryForMap("""
                with m as (
                  select a.id, a.created_at,
                    (select min(created_at) from state_history h where h.application_id = a.id and h.domain = 'APP' and h.to_state = 'SUBMITTED') as submitted,
                    (select min(created_at) from decision_record d where d.application_id = a.id) as decided,
                    (select min(created_at) from sanction_record s where s.application_id = a.id and s.action = 'APPROVED') as sanctioned,
                    (select min(created_at) from disbursement x where x.application_id = a.id and x.status = 'SUCCESS') as disbursed
                  from loan_application a where a.created_at >= ? and a.created_at < ?)
                select
                  round((percentile_cont(0.5) within group (order by extract(epoch from decided - submitted) / 3600))::numeric, 1) as submit_to_decision_p50,
                  round((percentile_cont(0.9) within group (order by extract(epoch from decided - submitted) / 3600))::numeric, 1) as submit_to_decision_p90,
                  round((percentile_cont(0.5) within group (order by extract(epoch from sanctioned - decided) / 3600))::numeric, 1) as decision_to_sanction_p50,
                  round((percentile_cont(0.5) within group (order by extract(epoch from disbursed - sanctioned) / 3600))::numeric, 1) as sanction_to_disbursal_p50,
                  round((percentile_cont(0.5) within group (order by extract(epoch from disbursed - created_at) / 3600))::numeric, 1) as end_to_end_p50,
                  count(disbursed) as disbursed_count
                from m""", r.from(), r.to());
    }

    private List<Map<String, Object>> daily(Range r) {
        return jdbc.queryForList("""
                select to_char(day, 'YYYY-MM-DD') as day,
                       (select count(*) from loan_application a where (a.created_at at time zone 'Asia/Kolkata')::date = day) as applications,
                       (select count(*) from disbursement d where d.status = 'SUCCESS' and (d.created_at at time zone 'Asia/Kolkata')::date = day) as disbursals,
                       (select coalesce(sum(gross_amount),0) from disbursement d where d.status = 'SUCCESS' and (d.created_at at time zone 'Asia/Kolkata')::date = day) as disbursed_amount
                from generate_series(?::date, ?::date, interval '1 day') as day order by day""", r.fromDay(), r.toDay());
    }

    private Map<String, Object> operations(Range r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("documents", jdbc.queryForList("""
                select d.doc_type as type, count(*) as n,
                       count(*) filter (where d.ai_status = 'OK') as auto_ok,
                       count(*) filter (where d.ai_status = 'NEEDS_REVIEW' or d.reviewed_by is not null) as reviewed,
                       count(*) filter (where d.status = 'REJECTED') as rejected,
                       count(*) filter (where d.tamper_flag) as tamper
                from app_document d join loan_application a on a.id = d.application_id
                where a.created_at >= ? and a.created_at < ? group by d.doc_type order by n desc""", r.from(), r.to()));
        m.put("kyc", jdbc.queryForMap("""
                select count(*) filter (where kyc_state = 'VERIFIED') as verified, count(*) filter (where kyc_state = 'REVIEW') as review,
                       count(*) filter (where kyc_state = 'FAILED') as failed, count(*) filter (where kyc_state = 'NOT_STARTED') as not_started
                from loan_application where created_at >= ? and created_at < ?""", r.from(), r.to()));
        m.put("videoKyc", jdbc.queryForMap("""
                select count(*) as sessions, count(*) filter (where status = 'APPROVED') as approved, count(*) filter (where status = 'REJECTED') as rejected,
                       count(*) filter (where ai_status = 'PASS') as ai_pass, count(*) filter (where status in ('EXPIRED','CANCELLED')) as abandoned
                from vkyc_session where created_at >= ? and created_at < ?""", r.from(), r.to()));
        m.put("vendors", jdbc.queryForList("""
                select vendor, operation, count(*) as calls, count(*) filter (where status in ('FAILED','DLQ')) as failures,
                       count(*) filter (where status = 'DLQ') as dead_letter, round(avg(latency_ms)) as avg_ms
                from integration_log where created_at >= ? and created_at < ? group by vendor, operation order by calls desc""", r.from(), r.to()));
        return m;
    }

    private Map<String, Object> controls(Range r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sanctionOverrides", jdbc.queryForObject("select count(*) from sanction_record where override_flag and created_at >= ? and created_at < ?", Long.class, r.from(), r.to()));
        m.put("referredButSanctioned", jdbc.queryForObject("""
                select count(*) from loan_application a where a.created_at >= ? and a.created_at < ? and a.decision_state = 'REFER'
                and exists (select 1 from sanction_record s where s.application_id = a.id and s.action = 'APPROVED')""", Long.class, r.from(), r.to()));
        m.put("videoKycOverrides", jdbc.queryForObject("select count(*) from vkyc_session where ai_override and created_at >= ? and created_at < ?", Long.class, r.from(), r.to()));
        m.put("documentsApprovedWithTamperFlag", jdbc.queryForObject("""
                select count(*) from app_document d join loan_application a on a.id = d.application_id
                where d.tamper_flag and d.status = 'VERIFIED' and a.created_at >= ? and a.created_at < ?""", Long.class, r.from(), r.to()));
        m.put("policyChanges", jdbc.queryForList("""
                select version, status, created_by as maker, checker, live_from from policy_version
                where (live_from >= ? and live_from < ?) or (submitted_at >= ? and submitted_at < ?) order by id desc""", r.from(), r.to(), r.from(), r.to()));
        return m;
    }

    /** One row per application in the range, for Excel. */
    public String applicationsCsv(LocalDate from, LocalDate to) {
        Range r = Range.of(from, to);
        StringBuilder sb = new StringBuilder("app_no,created_at,product,branch,applicant,amount,tenure,app_state,kyc,docs,decision,sanction,disbursement,latest_pd,band,policy\n");
        jdbc.query("""
                select a.app_no, a.created_at, a.product_code, coalesce(b.name,''), a.applicant_name, a.loan_amount, a.tenure_months, a.app_state, a.kyc_state,
                       a.docs_state, a.decision_state, a.sanction_state, a.disb_state, d.pd, d.risk_band, d.policy_version
                from loan_application a left join branch b on b.id = a.branch_id
                left join lateral (select pd, risk_band, policy_version from decision_record x where x.application_id = a.id order by x.id desc limit 1) d on true
                where a.created_at >= ? and a.created_at < ? order by a.id""", rs -> {
            for (int i = 1; i <= 16; i++) {
                Object v = rs.getObject(i);
                String s = v == null ? "" : v instanceof Timestamp t ? t.toInstant().atZone(IST).toLocalDateTime().toString() : v.toString();
                if (s.matches("^[=+\\-@].*")) s = "'" + s;   // stop spreadsheet formula injection
                sb.append(i > 1 ? "," : "").append('"').append(s.replace("\"", "\"\"")).append('"');
            }
            sb.append('\n');
        }, r.from(), r.to());
        return sb.toString();
    }
}
