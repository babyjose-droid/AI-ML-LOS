package com.rhythm.los.risk;

import com.rhythm.los.application.LoanApplication;
import com.rhythm.los.application.LoanApplicationRepository;
import com.rhythm.los.common.Mask;
import com.rhythm.los.org.DataScope;
import com.rhythm.los.security.CurrentUser;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Network risk (FR-KYC-08, FSD 14.6): applications are linked when they share a mobile number, bank account, device,
 * email or address with a different PAN. The network around an application (two hops) gives a 0-100 score: links to
 * many other PANs, shared bank accounts and devices, and links to applications declined for fraud raise it.
 */
@Service
public class NetworkService {
    static final int MAX_NODES = 60;

    public record Node(Long id, String appNo, String name, String state, String fraudState, boolean self, boolean visible) {}
    public record Edge(Long from, Long to, String via) {}
    public record Network(int score, List<String> factors, List<Node> nodes, List<Edge> edges) {}

    private final LoanApplicationRepository apps;
    private final DataScope scope;
    private final boolean enabled;

    public NetworkService(LoanApplicationRepository apps, DataScope scope,
                          @org.springframework.beans.factory.annotation.Value("${rhythm.network.enabled:true}") boolean enabled) {
        this.apps = apps;
        this.scope = scope;
        this.enabled = enabled;
    }

    /** The score used by the decision engine (0 when the network check is switched off). */
    public int score(LoanApplication a) { return enabled ? of(a).score() : 0; }

    static String norm(String s) { return s == null ? "" : s.toLowerCase().replaceAll("[^a-z0-9]", ""); }

    static String addressKey(LoanApplication a) {
        String ad = norm(a.getAddress());
        return ad.length() < 8 || a.getPincode() == null ? null : ad + "|" + a.getPincode();
    }

    static List<String> shared(LoanApplication a, LoanApplication b) {
        List<String> via = new ArrayList<>();
        if (a.getMobile() != null && a.getMobile().equals(b.getMobile())) via.add("mobile");
        if (a.getBankAccountNo() != null && !a.getBankAccountNo().isBlank() && a.getBankAccountNo().equals(b.getBankAccountNo())) via.add("bank account");
        if (a.getDeviceId() != null && a.getDeviceId().equals(b.getDeviceId())) via.add("device");
        if (a.getEmail() != null && !a.getEmail().isBlank() && a.getEmail().equalsIgnoreCase(b.getEmail())) via.add("email");
        String ka = addressKey(a);
        if (ka != null && ka.equals(addressKey(b))) via.add("address");
        return via;
    }

    /** The network for scoring (all applications, regardless of who is looking). */
    public Network of(LoanApplication root) { return build(root, null); }

    /** The network as a person sees it: applications outside their data scope show only a masked reference. */
    public Network view(LoanApplication root, CurrentUser u) { return build(root, scope.visibleBranches(u)); }

    private Network build(LoanApplication root, Set<Long> visibleBranches) {
        List<LoanApplication> all = apps.findAllByOrderByIdDesc();
        Map<Long, LoanApplication> byId = new HashMap<>();
        all.forEach(x -> byId.put(x.getId(), x));
        Map<Long, Integer> depth = new LinkedHashMap<>();
        List<Edge> edges = new ArrayList<>();
        Deque<LoanApplication> queue = new ArrayDeque<>();
        depth.put(root.getId(), 0);
        queue.add(root);
        while (!queue.isEmpty() && depth.size() < MAX_NODES) {
            LoanApplication cur = queue.poll();
            int d = depth.get(cur.getId());
            if (d >= 2) continue;
            for (LoanApplication o : all) {
                if (o.getId().equals(cur.getId()) || o.getPan().equalsIgnoreCase(cur.getPan())) continue;
                List<String> via = shared(cur, o);
                if (via.isEmpty()) continue;
                boolean seen = edges.stream().anyMatch(e -> e.from().equals(o.getId()) && e.to().equals(cur.getId()));
                if (!seen) edges.add(new Edge(cur.getId(), o.getId(), String.join(", ", via)));
                if (!depth.containsKey(o.getId()) && depth.size() < MAX_NODES) {
                    depth.put(o.getId(), d + 1);
                    queue.add(o);
                }
            }
        }
        // score
        Set<String> otherPans = new HashSet<>();
        int fraud = 0, review = 0, rejected = 0;
        boolean sharedAccount = false, sharedDevice = false;
        for (Long id : depth.keySet()) {
            if (id.equals(root.getId())) continue;
            LoanApplication o = byId.get(id);
            otherPans.add(o.getPan().toUpperCase());
            if ("BLOCK".equals(o.getFraudState())) fraud++;
            else if ("REVIEW".equals(o.getFraudState())) review++;
            if ("REJECTED".equals(o.getAppState())) rejected++;
        }
        for (Edge e : edges) {
            if (!e.from().equals(root.getId()) && !e.to().equals(root.getId())) continue;
            if (e.via().contains("bank account")) sharedAccount = true;
            if (e.via().contains("device")) sharedDevice = true;
        }
        List<String> factors = new ArrayList<>();
        int score = 0;
        if (!otherPans.isEmpty()) { score += Math.min(40, 10 * otherPans.size()); factors.add(otherPans.size() + " other PAN(s) in the network"); }
        if (sharedAccount) { score += 25; factors.add("Bank account shared with another PAN"); }
        if (sharedDevice) { score += 10; factors.add("Device shared with another PAN"); }
        if (fraud > 0) { score += 30 * fraud; factors.add(fraud + " linked application(s) declined for fraud"); }
        if (review > 0) { score += 15 * review; factors.add(review + " linked application(s) in fraud review"); }
        if (rejected > 0) { score += 5 * rejected; factors.add(rejected + " linked application(s) rejected"); }
        score = Math.min(100, score);
        List<Node> nodes = new ArrayList<>();
        for (Long id : depth.keySet()) {
            LoanApplication o = byId.get(id);
            boolean vis = visibleBranches == null || DataScope.canSee(visibleBranches, o);
            nodes.add(new Node(o.getId(), o.getAppNo(), vis ? o.getApplicantName() : initials(o.getApplicantName()) + " · " + Mask.pan(o.getPan()),
                    o.getAppState(), o.getFraudState(), id.equals(root.getId()), vis));
        }
        return new Network(score, factors, nodes, edges);
    }

    static String initials(String n) {
        if (n == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String p : n.trim().split("\\s+")) if (!p.isEmpty()) sb.append(p.charAt(0)).append('.');
        return sb.toString();
    }
}
