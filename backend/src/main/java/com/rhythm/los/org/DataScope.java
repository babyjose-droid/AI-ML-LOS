package com.rhythm.los.org;

import com.rhythm.los.application.LoanApplication;
import com.rhythm.los.security.CurrentUser;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Branch data scope (FR-ADM-03): staff see applications of their own unit and the units under it. A user at the head
 * office sees everything. An application without a branch (borrower portal) stays visible to all until it is routed.
 */
@Component
public class DataScope {
    private final BranchRepository branches;

    public DataScope(BranchRepository branches) { this.branches = branches; }

    /** The branch ids the user can see; null means all. */
    public Set<Long> visibleBranches(CurrentUser u) {
        if (u.role() == Role.BORROWER || u.branchId() == null) return null;
        List<Branch> all = branches.findAll();
        Branch own = all.stream().filter(b -> b.getId().equals(u.branchId())).findFirst().orElse(null);
        if (own == null || own.getParentId() == null) return null; // head office
        Map<Long, List<Long>> children = new HashMap<>();
        for (Branch b : all) if (b.getParentId() != null) children.computeIfAbsent(b.getParentId(), k -> new ArrayList<>()).add(b.getId());
        Set<Long> out = new HashSet<>();
        Deque<Long> todo = new ArrayDeque<>(List.of(own.getId()));
        while (!todo.isEmpty()) {
            Long id = todo.pop();
            if (out.add(id)) todo.addAll(children.getOrDefault(id, List.of()));
        }
        return out;
    }

    public boolean canSee(CurrentUser u, LoanApplication a) {
        return a.getBranchId() == null || canSee(visibleBranches(u), a);
    }

    /** With the visible set worked out once (for lists). */
    public static boolean canSee(Set<Long> visible, LoanApplication a) {
        return visible == null || a.getBranchId() == null || visible.contains(a.getBranchId());
    }
}
