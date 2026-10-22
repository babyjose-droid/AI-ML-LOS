package com.rhythm.los.flow;

import com.rhythm.los.application.LoanApplication;

import java.util.*;
import java.util.function.Predicate;

/**
 * The stages a workflow can use. Built-in stages follow the application's state machine (for example Documents is
 * done when the documents state is COMPLETE). Recorded stages (group formation, valuation, gold appraisal, legal)
 * are completed by a person with a pass/fail outcome.
 */
public final class StageLibrary {
    private StageLibrary() {}

    public enum Kind { STATE, RECORDED }

    public record Def(String key, String name, String role, int sla, String mode, Kind kind, List<String> modes,
                      List<String> integrations, Predicate<LoanApplication> done, List<String> dataFields, String help) {}

    static final List<String> ALL_MODES = List.of("AUTO", "AUTO_REVIEW", "MANUAL");

    public static final Map<String, Def> DEFS = new LinkedHashMap<>();

    static {
        add(new Def("lead", "Lead & application", "SALES", 24, "MANUAL", Kind.STATE, List.of("MANUAL"),
                List.of("Borrower portal", "Sales app"), a -> !"DRAFT".equals(a.getAppState()), List.of(), "Done when the application is submitted"));
        add(new Def("kyc", "Consent & KYC", "OPERATIONS", 4, "AUTO", Kind.STATE, ALL_MODES,
                List.of("Document AI", "KYC from documents"), a -> "VERIFIED".equals(a.getKycState()), List.of(), "Done when KYC is verified"));
        add(new Def("docs", "Documents", "OPERATIONS", 8, "AUTO", Kind.STATE, ALL_MODES,
                List.of("Document AI"), a -> "COMPLETE".equals(a.getDocsState()), List.of(), "Done when all required documents are verified"));
        add(new Def("field", "Field visit", "FIELD_OFFICER", 24, "MANUAL", Kind.STATE, List.of("MANUAL"),
                List.of("Field app"), a -> Set.of("DONE", "NOT_REQUIRED").contains(a.getFieldState()), List.of(), "Done when the field visit is recorded"));
        add(new Def("uw", "Underwriting", "CREDIT_OFFICER", 2, "AUTO", Kind.STATE, List.of("AUTO", "AUTO_REVIEW"),
                List.of("Bureau", "AA / bank statement", "Decision engine"), a -> !"PENDING".equals(a.getDecisionState()), List.of(), "Done when the decision engine has decided"));
        add(new Def("cam", "Credit memo", "CREDIT_OFFICER", 4, "AUTO", Kind.STATE, List.of("AUTO", "AUTO_REVIEW"),
                List.of("Credit memo writer"), a -> !"PENDING".equals(a.getDecisionState()), List.of(), "Written with the decision; in AUTO_REVIEW a credit officer confirms it"));
        add(new Def("sanction", "Sanction", "CREDIT_MANAGER", 8, "MANUAL", Kind.STATE, List.of("MANUAL"),
                List.of("KFS generator"), a -> Set.of("SANCTIONED", "KFS_ACCEPTED").contains(a.getSanctionState()) || "DISBURSED".equals(a.getAppState()),
                List.of(), "Done when sanctioned under the delegation matrix"));
        add(new Def("disb", "Disbursement", "OPERATIONS", 24, "MANUAL", Kind.STATE, List.of("MANUAL", "AUTO_REVIEW"),
                List.of("Penny drop", "Payout", "LMS"), a -> "DISBURSED".equals(a.getAppState()), List.of(), "Done when the money is sent"));
        add(new Def("cgt", "Group formation (CGT)", "FIELD_OFFICER", 72, "MANUAL", Kind.RECORDED, List.of("MANUAL"),
                List.of("Field app"), a -> false, List.of("groupName", "members", "sessionsHeld"), "Compulsory group training: group formed and members trained"));
        add(new Def("grt", "Group recognition test (GRT)", "CREDIT_OFFICER", 24, "MANUAL", Kind.RECORDED, List.of("MANUAL"),
                List.of("Field app"), a -> false, List.of("membersPresent"), "An officer tests the group's understanding before lending"));
        add(new Def("val", "Vehicle valuation", "FIELD_OFFICER", 24, "MANUAL", Kind.RECORDED, List.of("MANUAL"),
                List.of("Valuation partner", "Vahan RC check"), a -> false, List.of("registrationNo", "valuation"), "Inspect the vehicle, check the RC and record the valuation"));
        add(new Def("gold", "Gold appraisal", "CREDIT_OFFICER", 1, "MANUAL", Kind.RECORDED, List.of("MANUAL"),
                List.of("Purity analyser"), a -> false, List.of("netWeightGrams", "purityKarat", "value"), "Weigh and test the gold and record its value"));
        add(new Def("col", "Collateral AI review", "CREDIT_OFFICER", 120, "MANUAL", Kind.RECORDED, List.of("MANUAL"),
                List.of("Collateral Document AI", "Collateral Vision AI", "Valuation AI"), a -> false, List.of(),
                "Done when every collateral passes the collateral rules (CR-01 to CR-11), after legal, valuer and field reviews"));
        add(new Def("asset", "Asset & dealer check", "OPERATIONS", 24, "MANUAL", Kind.RECORDED, List.of("MANUAL"),
                List.of("Invoice AI", "Dealer master", "Vahan"), a -> false, List.of(),
                "Asset finance: dealer, quotation and invoice, down payment, insurance and LTV pass the asset rules (AF-01 to AF-13)"));
        add(new Def("limit", "Programme & limit", "OPERATIONS", 24, "MANUAL", Kind.RECORDED, List.of("MANUAL"),
                List.of("Anchor programme", "Buyer master"), a -> false, List.of(),
                "Trade finance: the anchor programme or the buyer sublimits and the borrower's GSTIN pass the limit checks (LS-01 to LS-04)"));
        add(new Def("plan", "Loan plan", "OPERATIONS", 24, "MANUAL", Kind.RECORDED, List.of("MANUAL"),
                List.of("Plan document AI"), a -> false, List.of(),
                "Housing, education or farm loan: what the loan pays for passes the plan checks (HS, ED or AG rules)"));
        add(new Def("pledge", "Pledge & lien", "OPERATIONS", 8, "MANUAL", Kind.RECORDED, List.of("MANUAL"),
                List.of("CAS / deposit receipt AI", "Depository", "Price feed"), a -> false, List.of(),
                "Loan against securities or a deposit: holdings or deposit read, drawing power covers the loan (PL / FD rules)"));
        add(new Def("legal", "Legal & technical (property)", "CREDIT_MANAGER", 72, "MANUAL", Kind.RECORDED, List.of("MANUAL"),
                List.of("Legal vendor", "Technical vendor"), a -> false, List.of("titleClear", "marketValue"), "Title search and technical valuation of the property"));
    }

    private static void add(Def d) { DEFS.put(d.key(), d); }

    public static Def def(String key) {
        Def d = DEFS.get(key);
        if (d == null) throw new IllegalArgumentException("Unknown stage " + key);
        return d;
    }

    public static Stage defaults(String key) {
        Def d = def(key);
        return new Stage(d.key(), d.name(), d.role(), d.sla(), d.mode(), "QUEUE", "", d.integrations());
    }

    /** Who a breached or failed stage goes to when the action is ESCALATE. */
    public static String escalationRole(String role) {
        return switch (role) {
            case "SALES" -> "OPERATIONS";
            case "FIELD_OFFICER" -> "CREDIT_OFFICER";
            case "OPERATIONS", "CREDIT_OFFICER" -> "CREDIT_MANAGER";
            default -> "CRO";
        };
    }
}
