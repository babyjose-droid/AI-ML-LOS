package com.rhythm.los.docgen;

import java.util.*;

/** The fields loan documents can use and the built-in blocks. Pure, so templates can be checked in unit tests. */
public final class DocFields {
    private DocFields() {}

    /** path -> (description, sample); lists also have their item fields. */
    public record Field(String path, String label, String sample, List<String> items) {}

    public static final List<Field> FIELDS = List.of(
            f("lender.name", "Lender's name", "Rhythm Demo Finance Ltd (NBFC)"),
            f("lender.address", "Lender's registered address", "Pune 411001"),
            f("lender.grievanceOfficer", "Grievance redressal officer", "Grievance officer, grievance@lender.in"),
            f("branch.name", "Branch", "Pune Kothrud"), f("branch.city", "Branch city", "Pune"),
            f("today", "Today's date (use | date)", "2026-10-15"),
            f("app.no", "Application number", "APP-300018"), f("app.date", "Application date (use | date)", "2026-10-02"),
            f("app.purpose", "Loan purpose", "Working capital"),
            f("app.amount", "Amount applied for (use | rupees)", "600000"), f("app.tenureMonths", "Tenure applied for (months)", "36"), f("app.channel", "Channel", "BRANCH"),
            f("product.name", "Product", "Business loan (unsecured)"), f("product.code", "Product code", "BL-UNS"),
            f("applicant.name", "Borrower's name", "Rohan Kulkarni"), f("applicant.pan", "Borrower's PAN", "ROHPK1234K"),
            f("applicant.mobile", "Borrower's mobile", "9876500411"), f("applicant.email", "Borrower's email", "rohan@example.in"),
            f("applicant.dob", "Date of birth (use | date)", "1985-04-02"), f("applicant.gender", "Gender", "MALE"),
            f("applicant.address", "Address", "12 Paud Road"), f("applicant.city", "City", "Pune"), f("applicant.pincode", "PIN code", "411038"),
            f("applicant.segment", "Segment", "MSME"), f("applicant.businessName", "Business name", "Kulkarni Traders"),
            f("applicant.vintageYears", "Years in business", "7"), f("applicant.monthlyIncome", "Declared monthly income (use | rupees)", "90000"),
            f("applicant.bankAccount", "Bank account (masked)", "XXXXXXXX8411"), f("applicant.ifsc", "IFSC", "ICIC0000123"),
            f("applicant.language", "Preferred language", "mr"),
            f("loan.amount", "Amount applied / sanctioned (use | rupees or | words)", "600000"),
            f("loan.tenureMonths", "Tenure in months", "36"), f("loan.rate", "Interest rate % a year", "18.5"),
            f("loan.apr", "APR % (all charges)", "20.1"), f("loan.emi", "EMI (use | rupees)", "21843"),
            f("loan.processingFee", "Processing fee", "12000"), f("loan.gst", "GST on fee", "2160"), f("loan.insurance", "Insurance", "0"),
            f("loan.netDisbursed", "Net disbursed", "585840"), f("loan.totalInterest", "Total interest", "186348"),
            f("loan.totalPayable", "Total payable", "786348"), f("loan.repaymentMode", "Repayment mode", "NACH"),
            f("loan.coolingOffDays", "Cooling-off days", "3"), f("loan.secured", "Secured loan (true/false)", "false"),
            f("loan.ref", "Loan account number (after disbursement)", "LN-300018"),
            f("sanction.date", "Sanction date (use | date)", "2026-10-10"), f("sanction.by", "Sanctioned by", "cm1"),
            f("sanction.level", "Sanction level", "L2"), f("sanction.validDays", "Sanction valid for (days)", "30"),
            l("sanction.conditions", "Sanction conditions (list of text: {{this}})", List.of()),
            f("disbursement.date", "Disbursement date (use | date)", "2026-10-12"), f("disbursement.utr", "Transfer reference (UTR)", "UTR123456"),
            f("disbursement.account", "Account credited (masked)", "XXXXXXXX8411"),
            l("coApplicants", "Co-applicants", List.of("name", "relationship", "pan", "dob", "address", "city", "mobile")),
            l("guarantors", "Guarantors", List.of("name", "relationship", "pan", "type", "signatory", "designation", "address", "city", "mobile")),
            l("parties", "Co-applicants and guarantors", List.of("name", "role", "relationship", "pan", "type", "signatory")),
            l("collaterals", "Collateral", List.of("colNo", "type", "description", "owner", "address", "pincode", "surveyNo", "areaSqft", "value",
                    "registrationNo", "makeModel", "year")),
            l("schedule", "Repayment schedule", List.of("month", "emi", "interest", "principal", "balance")),
            l("charges", "Contingent charges (KFS)", List.of("name", "amount")),
            f("count.coApplicants", "Number of co-applicants", "1"), f("count.guarantors", "Number of guarantors", "1"),
            f("count.collaterals", "Number of collaterals", "0"));

    /** Built-in blocks: {{> NAME}}. They follow regulation or the data, so they are not editable. */
    public static final Map<String, String> BUILT_IN = new LinkedHashMap<>();
    static {
        BUILT_IN.put("KFS_STANDARD", """
                <h2>Part 1: Interest rate and fees/charges</h2>
                <p class="small">In the format of the Reserve Bank of India's directions on the Key Facts Statement for loans and advances.</p>
                <table>
                <tr><th>1</th><th>Loan proposal / account no.</th><td>{{app.no}}</td><th>Type of loan</th><td>{{product.name}}</td></tr>
                <tr><th>2</th><th>Sanctioned loan amount</th><td colspan="3">{{loan.amount | rupees}} ({{loan.amount | words}})</td></tr>
                <tr><th>3</th><th>Disbursal schedule</th><td colspan="3">100% upfront, to the borrower's bank account after the documents are signed</td></tr>
                <tr><th>4</th><th>Loan term</th><td colspan="3">{{loan.tenureMonths}} months</td></tr>
                <tr><th>5</th><th>Instalments</th><td colspan="3">{{loan.tenureMonths}} equated monthly instalments of {{loan.emi | rupees}}, starting one month after disbursement</td></tr>
                <tr><th>6</th><th>Interest rate and type</th><td colspan="3">{{loan.rate | percent}} a year, fixed, on reducing balance</td></tr>
                <tr><th>7</th><th>Fees and charges payable to the lender</th><td colspan="3">Processing fee {{loan.processingFee | rupees}} + GST {{loan.gst | rupees}}{{#if loan.insurance}}; insurance {{loan.insurance | rupees}}{{/if}}</td></tr>
                <tr><th>8</th><th>Annual Percentage Rate (APR)</th><td colspan="3"><b>{{loan.apr | percent}}</b></td></tr>
                <tr><th>9</th><th>Contingent charges</th><td colspan="3">{{#each charges}}{{name}}: {{amount}}<br>{{else}}None{{/each}}</td></tr>
                </table>
                <p class="small">Part 2 (other qualitative information): recovery agents are engaged only as per RBI guidelines; grievances: {{lender.grievanceOfficer}}.
                Cooling-off period: {{loan.coolingOffDays}} days, during which the loan can be exited by repaying the principal and the proportionate APR without any penalty.
                Total amount payable: {{loan.totalPayable | rupees}} (interest {{loan.totalInterest | rupees}}). Net amount disbursed: {{loan.netDisbursed | rupees}}.</p>
                """);
        BUILT_IN.put("SCHEDULE_TABLE", """
                <table><tr><th>Instalment</th><th>EMI</th><th>Interest</th><th>Principal</th><th>Balance</th></tr>
                {{#each schedule}}<tr><td>{{month}}</td><td>{{emi | rupees}}</td><td>{{interest | rupees}}</td><td>{{principal | rupees}}</td><td>{{balance | rupees}}</td></tr>
                {{else}}<tr><td colspan="5">The schedule is set at sanction.</td></tr>{{/each}}</table>
                """);
        BUILT_IN.put("PARTIES_TABLE", """
                <table><tr><th>Role</th><th>Name</th><th>Relationship</th><th>PAN</th></tr>
                <tr><td>Borrower</td><td>{{applicant.name}}</td><td>Self</td><td>{{applicant.pan}}</td></tr>
                {{#each parties}}<tr><td>{{role}}</td><td>{{name}}{{#if signatory}} (through {{signatory}}){{/if}}</td><td>{{relationship}}</td><td>{{pan}}</td></tr>{{/each}}</table>
                """);
        BUILT_IN.put("COLLATERAL_TABLE", """
                <table><tr><th>No.</th><th>Security</th><th>Details</th><th>Value</th></tr>
                {{#each collaterals}}<tr><td>{{colNo}}</td><td>{{type}}</td><td>{{description}} {{address}} {{registrationNo}} {{makeModel}}</td><td>{{value | rupees}}</td></tr>
                {{else}}<tr><td colspan="4">No security</td></tr>{{/each}}</table>
                """);
        BUILT_IN.put("SIGNATURES", """
                <div class="sign">
                <div>Borrower: {{applicant.name}}</div>
                {{#each coApplicants}}<div>Co-applicant: {{name}}</div>{{/each}}
                {{#each guarantors}}<div>Guarantor: {{name}}{{#if signatory}}, through {{signatory}}{{#if designation}} ({{designation}}){{/if}}{{/if}}</div>{{/each}}
                <div>For {{lender.name}}<br>Authorised signatory</div>
                </div>
                """);
    }

    static Field f(String p, String label, String sample) { return new Field(p, label, sample, List.of()); }
    static Field l(String p, String label, List<String> items) { return new Field(p, label, null, items); }

    /** For the engine's checks: path -> item fields (empty for plain values). */
    public static Map<String, Set<String>> catalog() {
        Map<String, Set<String>> m = new LinkedHashMap<>();
        for (Field x : FIELDS) m.put(x.path(), x.items().isEmpty() && !x.path().equals("sanction.conditions") ? Set.of() : new HashSet<>(x.items()));
        // sanction.conditions is a list of plain text: {{this}}
        m.put("sanction.conditions", Set.of("this"));
        return m;
    }


    /** Sample values from the field catalog (lists get one or two sample rows). */
    public static Map<String, Object> sample() {
        Map<String, Object> root = new LinkedHashMap<>();
        for (Field f : FIELDS) {
            if (!f.items().isEmpty() || f.path().equals("sanction.conditions")) continue;
            String v = f.sample();
            put(root, f.path(), "true".equals(v) || "false".equals(v) ? (Object) Boolean.valueOf(v) : v != null && v.matches("[0-9]+(\\.[0-9]+)?") && !f.path().endsWith("pincode") && !f.path().endsWith("mobile")
                    ? (Object) Double.valueOf(v) : v);
        }
        put(root, "sanction.conditions", List.of("Original title deeds to be deposited before disbursement", "NACH mandate to be registered"));
        root.put("coApplicants", List.of(Map.of("name", "Sneha Kulkarni", "relationship", "spouse", "pan", "SNEPK4321K", "dob", "1988-02-11", "address", "12 Paud Road", "city", "Pune", "mobile", "9876500412")));
        root.put("guarantors", List.of(Map.of("name", "Madhav Joshi", "relationship", "business associate", "pan", "MADPJ1234J", "type", "Individual", "signatory", "", "designation", "", "address", "4 FC Road", "city", "Pune", "mobile", "9876500413")));
        root.put("parties", List.of(Map.of("name", "Sneha Kulkarni", "role", "Co-applicant", "relationship", "spouse", "pan", "SNEPK4321K", "type", "Individual", "signatory", ""),
                Map.of("name", "Madhav Joshi", "role", "Guarantor", "relationship", "business associate", "pan", "MADPJ1234J", "type", "Individual", "signatory", "")));
        root.put("collaterals", List.of());
        root.put("schedule", List.of(Map.of("month", 1, "emi", 21843, "interest", 9250, "principal", 12593, "balance", 587407),
                Map.of("month", 2, "emi", 21843, "interest", 9056, "principal", 12787, "balance", 574620)));
        root.put("charges", List.of(Map.of("name", "Foreclosure charge", "amount", "4% of principal outstanding"), Map.of("name", "Bounce charge", "amount", "₹500 per bounce")));
        return root;
    }

    @SuppressWarnings("unchecked")
    static void put(Map<String, Object> root, String path, Object v) {
        String[] p = path.split("\\.");
        Map<String, Object> cur = root;
        for (int i = 0; i < p.length - 1; i++) cur = (Map<String, Object>) cur.computeIfAbsent(p[i], k -> new LinkedHashMap<String, Object>());
        cur.put(p[p.length - 1], v);
    }
}
