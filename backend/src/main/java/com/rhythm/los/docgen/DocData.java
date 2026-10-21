package com.rhythm.los.docgen;

import com.rhythm.los.application.ApplicationService;
import com.rhythm.los.application.LoanApplication;
import com.rhythm.los.common.Json;
import com.rhythm.los.sanction.Kfs;
import com.rhythm.los.sanction.SanctionRecord;
import com.rhythm.los.sanction.SanctionRecordRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Fills the fields a loan document can use (see {@link DocFields}) from the application (and, after sanction, the KFS and sanction record),
 * plus the built-in blocks every lender gets as they are (the RBI KFS table, the repayment schedule, signature lines).
 */
@Service
public class DocData {
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final ApplicationService apps;
    private final SanctionRecordRepository sanctions;
    private com.rhythm.los.party.ApplicationPartyRepository parties;
    private com.rhythm.los.collateral.CollateralRepository collaterals;
    private com.rhythm.los.collateral.CollateralValuationRepository valuations;
    private com.rhythm.los.org.BranchRepository branches;
    private com.rhythm.los.disbursement.DisbursementRepository disbursements;
    private com.rhythm.los.book.LiveLoanRepository loans;
    private com.rhythm.los.asset.AssetService assets;

    @org.springframework.beans.factory.annotation.Autowired
    void setAssets(@org.springframework.context.annotation.Lazy com.rhythm.los.asset.AssetService a) { this.assets = a; }

    @Value("${rhythm.lender.name:Rhythm Demo Finance Ltd (NBFC)}") String lenderName = "Rhythm Demo Finance Ltd (NBFC)";
    @Value("${rhythm.lender.address:Registered office: Pune, Maharashtra}") String lenderAddress = "Registered office: Pune, Maharashtra";

    public DocData(ApplicationService apps, SanctionRecordRepository sanctions) {
        this.apps = apps;
        this.sanctions = sanctions;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setSources(com.rhythm.los.party.ApplicationPartyRepository p, com.rhythm.los.collateral.CollateralRepository c,
                    com.rhythm.los.collateral.CollateralValuationRepository v, com.rhythm.los.org.BranchRepository b,
                    com.rhythm.los.disbursement.DisbursementRepository d, com.rhythm.los.book.LiveLoanRepository l) {
        this.parties = p;
        this.collaterals = c;
        this.valuations = v;
        this.branches = b;
        this.disbursements = d;
        this.loans = l;
    }

    static String mask(String acc) { return acc == null || acc.length() < 4 ? acc : "X".repeat(acc.length() - 4) + acc.substring(acc.length() - 4); }

    /** The values a document for this application is filled with. */
    public Map<String, Object> of(LoanApplication a) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("lender", Map.of("name", lenderName, "address", lenderAddress, "grievanceOfficer", "Grievance redressal officer, " + lenderName));
        Map<String, Object> branch = new LinkedHashMap<>();
        if (branches != null && a.getBranchId() != null) branches.findById(a.getBranchId()).ifPresent(b -> { branch.put("name", b.getName()); branch.put("city", b.getCity()); });
        d.put("branch", branch);
        d.put("today", LocalDate.now(IST).toString());
        Map<String, Object> app = new LinkedHashMap<>();
        app.put("no", a.getAppNo());
        app.put("date", a.getCreatedAt() == null ? null : a.getCreatedAt().atZone(IST).toLocalDate().toString());
        app.put("purpose", a.getPurpose());
        app.put("amount", a.getLoanAmount());
        app.put("tenureMonths", a.getTenureMonths());
        app.put("channel", a.getChannel());
        d.put("app", app);
        var p = apps.product(a);
        d.put("product", Map.of("name", p.getName(), "code", a.getProductCode()));
        Map<String, Object> ap = new LinkedHashMap<>();
        ap.put("name", a.getApplicantName());
        ap.put("pan", a.getPan());
        ap.put("mobile", a.getMobile());
        ap.put("email", a.getEmail());
        ap.put("dob", a.getDob() == null ? null : a.getDob().toString());
        ap.put("gender", a.getGender());
        ap.put("address", a.getAddress());
        ap.put("city", a.getCity());
        ap.put("pincode", a.getPincode());
        ap.put("segment", a.getSegment());
        ap.put("businessName", a.getBusinessName());
        ap.put("vintageYears", a.getBusinessVintageYears());
        ap.put("monthlyIncome", a.getDeclaredMonthlyIncome());
        ap.put("bankAccount", mask(a.getBankAccountNo()));
        ap.put("ifsc", a.getBankIfsc());
        ap.put("language", a.getPreferredLanguage() == null ? "en" : a.getPreferredLanguage());
        d.put("applicant", ap);

        List<Map<String, Object>> co = new ArrayList<>(), gu = new ArrayList<>(), all = new ArrayList<>();
        if (parties != null)
            for (var x : parties.findByApplicationIdOrderByIdAsc(a.getId())) {
                if (!x.active()) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", x.getId());
                m.put("name", x.getName());
                boolean g = "GUARANTOR".equals(x.getRole());
                m.put("role", g ? "Guarantor" : "Co-applicant");
                m.put("relationship", x.getRelationship() == null ? null : x.getRelationship().toLowerCase().replace('_', ' '));
                m.put("pan", x.getPan());
                m.put("dob", x.getDob() == null ? null : x.getDob().toString());
                m.put("address", x.getAddress());
                m.put("city", x.getCity());
                m.put("mobile", x.getMobile());
                m.put("type", x.getPartyType() == null || "INDIVIDUAL".equals(x.getPartyType()) ? "Individual" : x.getPartyType().charAt(0) + x.getPartyType().substring(1).toLowerCase());
                m.put("signatory", x.getSignatoryName());
                m.put("designation", x.getSignatoryDesignation());
                (g ? gu : co).add(m);
                all.add(m);
            }
        d.put("coApplicants", co);
        d.put("guarantors", gu);
        d.put("parties", all);

        List<Map<String, Object>> cols = new ArrayList<>();
        if (collaterals != null)
            for (var c : collaterals.findByApplicationIdOrderByIdAsc(a.getId())) {
                if (Set.of("RELEASED", "SUBSTITUTED").contains(c.getLienState()) || "SUBSTITUTE".equals(c.getPurpose()) && !"ACTIVE".equals(c.getLienState())) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("colNo", c.getColNo());
                m.put("type", c.getType().charAt(0) + c.getType().substring(1).toLowerCase());
                m.put("description", c.getDescription());
                m.put("owner", c.getOwnerName());
                m.put("address", c.getAddress());
                m.put("pincode", c.getPincode());
                m.put("surveyNo", c.getSurveyNo());
                m.put("areaSqft", c.getAreaSqft());
                m.put("registrationNo", c.getRegistrationNo());
                m.put("makeModel", c.getMakeModel());
                m.put("year", c.getManufactureYear());
                if (valuations != null)
                    valuations.findByCollateralIdOrderByIdAsc(c.getId()).stream().filter(v -> v.isApproved() && !"AI".equals(v.getSource()))
                            .mapToDouble(v -> v.getMarketValue().doubleValue()).min().ifPresent(v -> m.put("value", v));
                cols.add(m);
            }
        d.put("collaterals", cols);
        d.put("count", Map.of("coApplicants", co.size(), "guarantors", gu.size(), "collaterals", cols.size()));

        Map<String, Object> loan = new LinkedHashMap<>();
        loan.put("amount", a.getLoanAmount());
        loan.put("tenureMonths", a.getTenureMonths());
        loan.put("secured", !cols.isEmpty());
        Optional<SanctionRecord> s = sanctions.findFirstByApplicationIdAndActionOrderByIdDesc(a.getId(), "APPROVED");
        List<Map<String, Object>> schedule = new ArrayList<>(), charges = new ArrayList<>();
        Map<String, Object> sanction = new LinkedHashMap<>();
        if (s.isPresent()) {
            Kfs k = Json.read(s.get().getKfsJson(), Kfs.class);
            loan.put("amount", k.sanctionedAmount());
            loan.put("tenureMonths", k.tenureMonths());
            loan.put("rate", k.ratePa());
            loan.put("apr", k.aprPa());
            loan.put("emi", k.emi());
            loan.put("processingFee", k.processingFee());
            loan.put("gst", k.gstOnFee());
            loan.put("insurance", k.insurance());
            loan.put("netDisbursed", k.netDisbursed());
            loan.put("totalInterest", k.totalInterest());
            loan.put("totalPayable", k.totalPayable());
            loan.put("repaymentMode", k.repaymentMode());
            loan.put("coolingOffDays", k.coolingOffDays());
            for (Kfs.Row r : k.schedule())
                schedule.add(Map.of("month", r.month(), "emi", r.emi(), "interest", r.interest(), "principal", r.principal(), "balance", r.balance()));
            if (k.contingentCharges() != null) for (Kfs.Charge c : k.contingentCharges()) charges.add(Map.of("name", c.name(), "amount", c.amount()));
            sanction.put("date", s.get().getCreatedAt().atZone(IST).toLocalDate().toString());
            sanction.put("by", s.get().getActor());
            sanction.put("level", s.get().getLevel());
            sanction.put("validDays", 30);
            sanction.put("conditions", s.get().getConditionsJson() == null ? List.of() : List.of(Json.read(s.get().getConditionsJson(), String[].class)));
        }
        if (loans != null) loans.findByApplicationId(a.getId()).ifPresent(l -> loan.put("ref", l.getLoanRef()));
        d.put("loan", loan);
        d.put("schedule", schedule);
        d.put("charges", charges);
        d.put("sanction", sanction);
        Map<String, Object> disb = new LinkedHashMap<>();
        if (disbursements != null)
            disbursements.findByApplicationIdOrderByIdAsc(a.getId()).stream().filter(x -> "SUCCESS".equals(x.getStatus())).reduce((x, y) -> y).ifPresent(x -> {
                disb.put("date", x.getCreatedAt().atZone(IST).toLocalDate().toString());
                disb.put("utr", x.getUtr());
                disb.put("account", x.getBeneficiaryAccount());
            });
        d.put("disbursement", disb);
        d.put("asset", assets == null ? Map.of() : assets.docData(a.getId()));
        return d;
    }
}
