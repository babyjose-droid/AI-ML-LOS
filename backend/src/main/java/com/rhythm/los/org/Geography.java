package com.rhythm.los.org;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * PIN code to branch (master lookups): which active branch serves a PIN code, and whether a product is offered there
 * (FSD 14.1 step 3, rule R18). Until branches are given PIN codes the check is not made (null), never failed.
 */
@Service
public class Geography {
    private final BranchRepository branches;

    public Geography(BranchRepository branches) { this.branches = branches; }

    private List<Branch> withPins() {
        return branches.findAll().stream().filter(b -> b.isActive() && "BRANCH".equals(b.getLevel())
                && b.getPincodes() != null && !b.getPincodes().isBlank()).toList();
    }

    /** null when no branch has PIN codes yet or the PIN is missing; otherwise whether a branch serving the PIN offers the product. */
    public Boolean served(String pincode, String productCode) {
        List<Branch> all = withPins();
        if (all.isEmpty() || pincode == null || pincode.isBlank()) return null;
        return all.stream().anyMatch(b -> b.servesPin(pincode) && b.offers(productCode));
    }

    /** The branch an application from this PIN code goes to (portal applications have no branch of their own). */
    public Optional<Branch> branchFor(String pincode, String productCode) {
        if (pincode == null) return Optional.empty();
        List<Branch> all = withPins();
        return all.stream().filter(b -> b.servesPin(pincode) && b.offers(productCode)).findFirst()
                .or(() -> all.stream().filter(b -> b.servesPin(pincode)).findFirst());
    }
}
