package com.rhythm.los.party;

import com.rhythm.los.common.ApiException;
import com.rhythm.los.common.Json;
import com.rhythm.los.flow.Conditions;
import com.rhythm.los.document.NameMatch;

import java.time.LocalDate;
import java.time.Period;
import java.util.*;

/**
 * A product's rules for co-applicants and guarantors, and the pure checks that apply them. No database access: the
 * service passes in the application facts and the parties, so every rule can be tested on its own.
 *
 * Gates, in order: SUBMIT (who may be on the loan and how many), DECISION (each party has consented, is KYC-verified and
 * has a bureau result), SANCTION (co-owners of the collateral have joined, credit's requests are met) and DISBURSE (every
 * party has signed). An issue blocks its own gate and every later one.
 */
public record PartyRules(Slot coApplicant, Slot guarantor, boolean coOwnersMustJoin, Clubbing incomeClubbing,
                         List<String> relationships, int minBureauScore, int minAge, int maxAgeAtMaturity, String note) {

    /** mode: NOT_ALLOWED, OPTIONAL or MANDATORY (at least one). requiredWhen: a workflow condition that makes one mandatory. */
    public record Slot(String mode, int max, String requiredWhen) {}

    /** Income of co-applicants added to the applicant's for affordability; maxEarners counts the applicant too. */
    public record Clubbing(boolean allowed, int maxEarners, List<String> relationships) {}

    public static final String CO_APPLICANT = "CO_APPLICANT";
    public static final String GUARANTOR = "GUARANTOR";
    public static final Set<String> ROLES = Set.of(CO_APPLICANT, GUARANTOR);
    public static final Set<String> MODES = Set.of("NOT_ALLOWED", "OPTIONAL", "MANDATORY");
    public static final List<String> ALL_RELATIONSHIPS = List.of("SPOUSE", "PARENT", "CHILD", "SIBLING", "BUSINESS_PARTNER", "DIRECTOR", "RELATIVE", "FRIEND", "OTHER");
    public static final List<String> GATES = List.of("SUBMIT", "DECISION", "SANCTION", "DISBURSE");

    /** For a product saved before party rules existed: parties optional, nothing mandatory, no income clubbing. */
    public static final PartyRules DEFAULT = new PartyRules(new Slot("OPTIONAL", 2, ""), new Slot("OPTIONAL", 2, ""), false,
            new Clubbing(false, 1, List.of()), ALL_RELATIONSHIPS, 0, 18, 70, "");

    public static PartyRules parse(String json) {
        if (json == null || json.isBlank()) return DEFAULT;
        try {
            return normalise(Json.read(json, PartyRules.class));
        } catch (RuntimeException e) {
            return DEFAULT;
        }
    }

    /** Fills gaps with safe values and checks the rules; throws a 422 with a plain message when a rule is wrong. */
    public static PartyRules validated(PartyRules r) {
        PartyRules n = normalise(r);
        for (Slot s : List.of(n.coApplicant(), n.guarantor())) {
            if (!MODES.contains(s.mode())) throw ApiException.unprocessable("BAD_PARTY_RULE", "Party mode must be one of " + MODES);
            if (s.max() < 0 || s.max() > 5) throw ApiException.unprocessable("BAD_PARTY_RULE", "At most 5 co-applicants or guarantors");
            if ("MANDATORY".equals(s.mode()) && s.max() < 1) throw ApiException.unprocessable("BAD_PARTY_RULE", "A mandatory party needs a maximum of at least 1");
            if (!"NOT_ALLOWED".equals(s.mode()) && s.max() < 1) throw ApiException.unprocessable("BAD_PARTY_RULE", "Set a maximum of at least 1, or choose 'not allowed'");
            String err = Conditions.validate(s.requiredWhen());
            if (err != null) throw ApiException.unprocessable("BAD_PARTY_RULE", "Required-when rule: " + err);
            if ("NOT_ALLOWED".equals(s.mode()) && s.requiredWhen() != null && !s.requiredWhen().isBlank())
                throw ApiException.unprocessable("BAD_PARTY_RULE", "A party that is not allowed cannot be required");
        }
        for (String x : n.relationships()) if (!ALL_RELATIONSHIPS.contains(x)) throw ApiException.unprocessable("BAD_PARTY_RULE", "Unknown relationship " + x);
        for (String x : n.incomeClubbing().relationships()) if (!ALL_RELATIONSHIPS.contains(x)) throw ApiException.unprocessable("BAD_PARTY_RULE", "Unknown relationship " + x);
        if (n.incomeClubbing().allowed() && n.incomeClubbing().maxEarners() < 2)
            throw ApiException.unprocessable("BAD_PARTY_RULE", "Income clubbing needs at least 2 earners (the applicant and one co-applicant)");
        if (n.minBureauScore() != 0 && (n.minBureauScore() < 300 || n.minBureauScore() > 900))
            throw ApiException.unprocessable("BAD_PARTY_RULE", "Minimum bureau score must be 0 (none) or between 300 and 900");
        if (n.minAge() < 18 || n.maxAgeAtMaturity() > 80 || n.minAge() >= n.maxAgeAtMaturity())
            throw ApiException.unprocessable("BAD_PARTY_RULE", "Party age limits must be 18 or more and at most 80 at maturity");
        return n;
    }

    static PartyRules normalise(PartyRules r) {
        if (r == null) return DEFAULT;
        Slot co = slot(r.coApplicant()), g = slot(r.guarantor());
        Clubbing c = r.incomeClubbing() == null ? new Clubbing(false, 1, List.of())
                : new Clubbing(r.incomeClubbing().allowed(), Math.max(1, r.incomeClubbing().maxEarners()),
                r.incomeClubbing().relationships() == null ? List.of() : List.copyOf(r.incomeClubbing().relationships()));
        List<String> rel = r.relationships() == null || r.relationships().isEmpty() ? ALL_RELATIONSHIPS : List.copyOf(r.relationships());
        return new PartyRules(co, g, r.coOwnersMustJoin(), c, rel, Math.max(0, r.minBureauScore()), r.minAge() <= 0 ? 18 : r.minAge(),
                r.maxAgeAtMaturity() <= 0 ? 70 : r.maxAgeAtMaturity(), r.note() == null ? "" : r.note().trim());
    }

    private static Slot slot(Slot s) {
        if (s == null) return new Slot("NOT_ALLOWED", 0, "");
        String mode = s.mode() == null ? "OPTIONAL" : s.mode().trim().toUpperCase();
        return new Slot(mode, "NOT_ALLOWED".equals(mode) ? 0 : s.max(), s.requiredWhen() == null ? "" : s.requiredWhen().trim());
    }

    public Slot slot(String role) { return GUARANTOR.equals(role) ? guarantor : coApplicant; }

    // ------------------------------------------------------------------ inputs for the checks

    /** A party as the checks see it. Statuses as in application_party. */
    public record Party(long id, String role, String relationship, String name, String pan, LocalDate dob, boolean incomeConsidered,
                        boolean coOwner, String status, boolean consented, String kycState, String bureauState, Integer bureauScore,
                        double monthlyIncome, double existingEmi, double bureauEmi, boolean signed) {
        boolean active() { return !"REMOVED".equals(status); }
    }

    public record Requirement(long id, String role, String reason) {}

    public record Issue(String code, String gate, String message, String role) {}

    /** The people who must be on the loan and the state each must reach, as issues. Empty when nothing is missing. */
    public List<Issue> check(Map<String, Object> facts, List<Party> parties, List<String> coOwners, String primaryName,
                             List<Requirement> openRequests, boolean sanctioned, int tenureMonths, LocalDate today) {
        List<Issue> out = new ArrayList<>();
        List<Party> live = parties.stream().filter(Party::active).toList();
        for (String role : List.of(CO_APPLICANT, GUARANTOR)) {
            Slot s = slot(role);
            long n = live.stream().filter(p -> role.equals(p.role())).count();
            String who = label(role);
            if ("NOT_ALLOWED".equals(s.mode()) && n > 0)
                out.add(new Issue("PARTY_NOT_ALLOWED", "SUBMIT", "This product does not allow a " + who + ": remove " + n, role));
            if (n > s.max() && !"NOT_ALLOWED".equals(s.mode()))
                out.add(new Issue("TOO_MANY_PARTIES", "SUBMIT", "At most " + s.max() + " " + plural(who, s.max()) + " for this product (now " + n + ")", role));
            boolean required = "MANDATORY".equals(s.mode()) || (s.requiredWhen() != null && !s.requiredWhen().isBlank() && Conditions.eval(s.requiredWhen(), facts));
            if (required && n == 0) {
                // a product that always needs the party blocks submission; a rule ("amount > 5 L", "score < 700") must be met
                // before sanction, so credit can also bring the party in after appraisal
                boolean always = "MANDATORY".equals(s.mode());
                out.add(new Issue("PARTY_REQUIRED", always ? "SUBMIT" : "SANCTION",
                        "A " + who + " is required" + (always ? " for this product" : " before sanction (" + s.requiredWhen() + ")"), role));
            }
        }
        for (Party p : live) {
            if (!relationships.contains(p.relationship()))
                out.add(new Issue("BAD_RELATIONSHIP", "SUBMIT", p.name() + ": relationship " + p.relationship().toLowerCase().replace('_', ' ') + " is not allowed for this product", p.role()));
            if (!p.consented())
                out.add(new Issue("PARTY_CONSENT", "DECISION", p.name() + " (" + label(p.role()) + ") has not given consent yet", p.role()));
            else if (!"VERIFIED".equals(p.kycState()))
                out.add(new Issue("PARTY_KYC", "DECISION", p.name() + " (" + label(p.role()) + "): KYC " + kycWord(p.kycState()), p.role()));
            else if ("NOT_FETCHED".equals(p.bureauState()) || "FAILED".equals(p.bureauState()))
                out.add(new Issue("PARTY_BUREAU", "DECISION", p.name() + " (" + label(p.role()) + "): bureau report not fetched", p.role()));
            if (sanctioned && !p.signed())
                out.add(new Issue("PARTY_SIGNATURE", "DISBURSE", p.name() + " (" + label(p.role()) + ") has not signed the " + (GUARANTOR.equals(p.role()) ? "guarantee" : "loan agreement and KFS"), p.role()));
        }
        if (coOwnersMustJoin)
            for (String owner : coOwners) {
                if (primaryName != null && NameMatch.score(primaryName, owner) >= 0.8) continue;
                boolean joined = live.stream().anyMatch(p -> CO_APPLICANT.equals(p.role()) && NameMatch.score(p.name(), owner) >= 0.8);
                if (!joined) out.add(new Issue("CO_OWNER_MISSING", "SANCTION", owner + " co-owns the collateral and must join as a co-applicant", CO_APPLICANT));
            }
        for (Requirement r : openRequests)
            out.add(new Issue("PARTY_REQUESTED", "SANCTION", "Credit asked for a " + label(r.role()) + ": " + r.reason(), r.role()));
        return out;
    }

    /** Issues that block the given gate (its own and every earlier gate's). */
    public static List<Issue> blocking(List<Issue> issues, String gate) {
        int g = GATES.indexOf(gate);
        return issues.stream().filter(i -> GATES.indexOf(i.gate()) <= g).toList();
    }

    /** Problems with one party to add or change, before it is saved. */
    public List<String> admit(String role, String relationship, LocalDate dob, boolean incomeConsidered, long sameRoleCount, int tenureMonths, LocalDate today) {
        List<String> out = new ArrayList<>();
        Slot s = slot(role);
        if ("NOT_ALLOWED".equals(s.mode())) out.add("This product does not allow a " + label(role));
        else if (sameRoleCount >= s.max()) out.add("This product allows at most " + s.max() + " " + plural(label(role), s.max()));
        if (!relationships.contains(relationship)) out.add("Relationship " + relationship.toLowerCase().replace('_', ' ') + " is not allowed for this product");
        if (dob != null) {
            int age = Period.between(dob, today).getYears();
            if (age < minAge) out.add("A " + label(role) + " must be at least " + minAge);
            double atMaturity = age + tenureMonths / 12.0;
            if (atMaturity > maxAgeAtMaturity) out.add("A " + label(role) + " must be " + maxAgeAtMaturity + " or younger when the loan ends (would be " + Math.round(atMaturity) + ")");
        }
        if (incomeConsidered && !clubs(role, relationship)) out.add(GUARANTOR.equals(role) ? "A guarantor's income is not counted" : "Income of a " + relationship.toLowerCase().replace('_', ' ') + " cannot be added for this product");
        return out;
    }

    public boolean clubs(String role, String relationship) {
        return CO_APPLICANT.equals(role) && incomeClubbing.allowed()
                && (incomeClubbing.relationships().isEmpty() || incomeClubbing.relationships().contains(relationship));
    }

    /** Joint affordability and the party policy, for the underwriting engine. */
    public record Joint(int coApplicants, int guarantors, int earnersCounted, double clubbedIncome, double clubbedEmi, List<String> policyFails) {}

    public Joint joint(List<Party> parties, int tenureMonths, LocalDate today) {
        List<Party> live = parties.stream().filter(Party::active).toList();
        int co = (int) live.stream().filter(p -> CO_APPLICANT.equals(p.role())).count();
        int g = (int) live.stream().filter(p -> GUARANTOR.equals(p.role())).count();
        double income = 0, emi = 0;
        int counted = 0;
        for (Party p : live) {
            if (!p.incomeConsidered() || !clubs(p.role(), p.relationship()) || !"VERIFIED".equals(p.kycState())) continue;
            if (counted + 1 >= incomeClubbing.maxEarners()) break;
            counted++;
            income += Math.max(0, p.monthlyIncome());
            emi += Math.max(p.existingEmi(), p.bureauEmi());
        }
        List<String> fails = new ArrayList<>();
        for (Party p : live) {
            String who = p.name() + " (" + label(p.role()) + ")";
            if (minBureauScore > 0) {
                if ("NO_HIT".equals(p.bureauState()) && GUARANTOR.equals(p.role())) fails.add(who + ": no credit record");
                else if (p.bureauScore() != null && p.bureauScore() > 0 && p.bureauScore() < minBureauScore)
                    fails.add(who + ": bureau score " + p.bureauScore() + " below " + minBureauScore);
            }
            if (p.dob() != null) {
                double atMaturity = Period.between(p.dob(), today).getYears() + tenureMonths / 12.0;
                if (atMaturity > maxAgeAtMaturity) fails.add(who + ": " + Math.round(atMaturity) + " at maturity (limit " + maxAgeAtMaturity + ")");
            }
        }
        return new Joint(co, g, counted, Math.round(income), Math.round(emi), fails);
    }

    /** Names on a title or declaration: "Asha Rao & Vikram Rao", "A and B", "A, B". */
    public static List<String> splitOwners(String s) {
        if (s == null || s.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String x : s.split("\\s*(?:&|,|;|\\band\\b|/)\\s*")) {
            String t = x.trim().replaceAll("^(?i)(shri|smt|mr|mrs|ms)\\.?\\s+", "");
            if (t.length() >= 3 && !out.contains(t)) out.add(t);
        }
        return out;
    }

    public static String label(String role) { return GUARANTOR.equals(role) ? "guarantor" : "co-applicant"; }

    static String plural(String w, long n) { return n == 1 ? w : w + "s"; }

    static String kycWord(String s) {
        return switch (s == null ? "" : s) {
            case "REVIEW" -> "waiting for review";
            case "FAILED" -> "failed";
            default -> "not done";
        };
    }
}
