package com.rhythm.los.workflow;

import com.rhythm.los.application.ApplicationService;
import com.rhythm.los.application.LoanApplication;
import com.rhythm.los.consent.ConsentService;
import com.rhythm.los.decision.DecisionRecord;
import com.rhythm.los.decision.DecisionRecordRepository;
import com.rhythm.los.disbursement.Disbursement;
import com.rhythm.los.disbursement.DisbursementRepository;
import com.rhythm.los.document.AppDocument;
import com.rhythm.los.document.AppDocumentRepository;
import com.rhythm.los.document.KycCheckRepository;
import com.rhythm.los.integration.DataSnapshotRepository;
import com.rhythm.los.memo.CreditMemoRepository;
import com.rhythm.los.sanction.SanctionRecord;
import com.rhythm.los.sanction.SanctionRecordRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

/**
 * Data-capture checklist per stage (FSD 11–12): for each stage, the minimum and additional data items, their source
 * (B borrower, F fetched, D derived, O officer) and whether the file has them. Minimum items that apply and are missing
 * are what the stage still needs before the case can move on.
 */
@Service
public class DataCaptureService {
    public record Item(String stage, String item, String tier, String source, String why, boolean applies, boolean captured) {}

    private final ApplicationService apps;
    private final AppDocumentRepository docs;
    private final KycCheckRepository kyc;
    private final DataSnapshotRepository snaps;
    private final DecisionRecordRepository decisions;
    private final SanctionRecordRepository sanctions;
    private final DisbursementRepository disb;
    private final ConsentService consents;
    private final CreditMemoRepository memos;

    public DataCaptureService(ApplicationService apps, AppDocumentRepository docs, KycCheckRepository kyc, DataSnapshotRepository snaps,
                              DecisionRecordRepository decisions, SanctionRecordRepository sanctions, DisbursementRepository disb,
                              ConsentService consents, CreditMemoRepository memos) {
        this.apps = apps;
        this.docs = docs;
        this.kyc = kyc;
        this.snaps = snaps;
        this.decisions = decisions;
        this.sanctions = sanctions;
        this.disb = disb;
        this.consents = consents;
        this.memos = memos;
    }

    private static boolean has(String s) { return s != null && !s.isBlank(); }
    private static boolean pos(BigDecimal b) { return b != null && b.signum() > 0; }

    public List<Item> checklist(Long appId) {
        LoanApplication a = apps.get(appId);
        boolean msme = "MSME".equals(a.getSegment());
        boolean salaried = "SALARIED".equals(a.getSegment());
        Set<String> docTypes = new HashSet<>();
        for (AppDocument d : docs.findByApplicationIdOrderByIdAsc(appId)) if (!"REJECTED".equals(d.getStatus())) docTypes.add(d.getDocType());
        Set<String> kinds = new HashSet<>();
        snaps.findByApplicationIdOrderByIdDesc(appId).forEach(s -> kinds.add(s.getKind()));
        boolean kycRun = !kyc.findByApplicationIdOrderByIdAsc(appId).isEmpty() || kinds.contains("KYC");
        DecisionRecord d = decisions.findFirstByApplicationIdOrderByIdDesc(appId).orElse(null);
        boolean decided = d != null && !"INCOMPLETE".equals(d.getDecision());
        List<SanctionRecord> sr = sanctions.findByApplicationIdOrderByIdAsc(appId);
        SanctionRecord approved = sr.stream().filter(s -> "APPROVED".equals(s.getAction())).reduce((x, y) -> y).orElse(null);
        List<Disbursement> ds = disb.findByApplicationIdOrderByIdAsc(appId);
        Disbursement done = ds.stream().filter(x -> "SUCCESS".equals(x.getStatus()) || x.getUtr() != null).reduce((x, y) -> y).orElse(null);
        boolean fieldNeeded = List.of("REQUIRED", "DONE").contains(a.getFieldState());
        boolean bureauHit = kinds.contains("BUREAU");

        List<Item> out = new ArrayList<>();
        // Stage 1: lead and application
        out.add(new Item("lead", "Mobile number", "MINIMUM", "B", "Identity anchor, consent channel, dedupe", true, has(a.getMobile())));
        out.add(new Item("lead", "PAN", "MINIMUM", "B", "Identity, bureau key, dedupe", true, has(a.getPan())));
        out.add(new Item("lead", "Name and date of birth", "MINIMUM", "F", "Identity match, age rule", true, has(a.getApplicantName()) && a.getDob() != null));
        out.add(new Item("lead", "Product and loan purpose", "MINIMUM", "B", "Product match, policy, end-use", true, has(a.getProductCode()) && has(a.getPurpose())));
        out.add(new Item("lead", "Requested amount and tenure", "MINIMUM", "B", "Affordability, delegation", true, pos(a.getLoanAmount()) && a.getTenureMonths() > 0));
        out.add(new Item("lead", "PIN code", "MINIMUM", "B", "Branch routing, negative-area rule", true, has(a.getPincode())));
        out.add(new Item("lead", "Segment / occupation", "MINIMUM", "B", "Model and scorecard choice", true, has(a.getSegment())));
        out.add(new Item("lead", "Preferred language", "MINIMUM", "B", "KFS and communication language", true, has(a.getPreferredLanguage())));
        out.add(new Item("lead", "Declared monthly income", "MINIMUM", "B", "Indicative eligibility; checked against bank data", true, pos(a.getDeclaredMonthlyIncome())));
        out.add(new Item("lead", "Business name and GST details", "ADDITIONAL", "F", "Vintage, turnover, bank-GST check", msme, has(a.getBusinessName()) && kinds.contains("GST")));
        out.add(new Item("lead", "Udyam number", "ADDITIONAL", "F", "MSME status, PSL and co-lending", msme, docTypes.contains("UDYAM")));
        out.add(new Item("lead", "Employer name", "ADDITIONAL", "B", "Employment stability", salaried, has(a.getBusinessName())));
        // Stage 2: consent and KYC
        out.add(new Item("kyc", "Consent artefacts (KYC, bank data, bureau)", "MINIMUM", "B", "Legal basis for every pull", true, !consents.list(appId).isEmpty() || a.getConsentAt() != null));
        out.add(new Item("kyc", "Identity record (CKYC / PAN / OVD)", "MINIMUM", "F", "Identity verification", true, kycRun));
        out.add(new Item("kyc", "Current address", "MINIMUM", "B", "Field visit, communication", true, has(a.getAddress())));
        out.add(new Item("kyc", "Video KYC outcome", "ADDITIONAL", "O", "Needed when KYC goes to review", "REVIEW".equals(a.getKycState()), false));
        // Stage 3: documents
        out.add(new Item("docs", "Bank statement (AA) covering 6+ months", "MINIMUM", "F", "Income, obligations, bounces", true, kinds.contains("AA") || docTypes.contains("BANK_STATEMENT")));
        out.add(new Item("docs", "PAN card image", "MINIMUM", "B", "KYC record when PAN is not e-verified", true, docTypes.contains("PAN")));
        out.add(new Item("docs", "Business proof (GST, Udyam or shop licence)", "MINIMUM", "B", "Business existence and vintage", msme,
                docTypes.contains("UDYAM") || docTypes.contains("GST_CERT") || kinds.contains("GST")));
        out.add(new Item("docs", "Salary slips (3 months)", "MINIMUM", "B", "Income cross-check without an AA salary tag", salaried, docTypes.contains("SALARY_SLIP")));
        out.add(new Item("docs", "Rent agreement / ownership proof", "ADDITIONAL", "B", "Stability, address", msme, docTypes.contains("RENT_AGREEMENT")));
        out.add(new Item("docs", "ITR, last 2 years", "ADDITIONAL", "B", "Income for tickets above ₹5 lakh", a.getLoanAmount().compareTo(new BigDecimal("500000")) > 0, docTypes.contains("ITR")));
        // Stage 4: field visit
        boolean visited = "DONE".equals(a.getFieldState());
        out.add(new Item("field", "Visit check-in (geo and time)", "MINIMUM", "D", "Proves the visit happened", fieldNeeded, visited));
        out.add(new Item("field", "Checklist: business running, owner met", "MINIMUM", "O", "Verification rule", fieldNeeded, visited));
        out.add(new Item("field", "Officer remarks", "MINIMUM", "O", "Credit memo", fieldNeeded, visited));
        out.add(new Item("field", "Photos: premises, stock, owner with officer", "MINIMUM", "O", "Existence, scale, identity", fieldNeeded, visited && docTypes.contains("PHOTO")));
        // Stage 5: underwriting
        out.add(new Item("uw", "Sustainable monthly income", "MINIMUM", "D", "Affordability, FOIR", true, d != null && pos(d.getSustainableIncome())));
        out.add(new Item("uw", "Existing EMIs (bank + bureau)", "MINIMUM", "D", "FOIR, surplus", true, kinds.contains("AA")));
        out.add(new Item("uw", "Essential household expenses", "MINIMUM", "B", "Surplus", true, a.getEssentialExpenses() != null));
        out.add(new Item("uw", "Bureau score and history", "MINIMUM", "F", "Thick-file model, bureau rules", bureauHit, bureauHit));
        out.add(new Item("uw", "GST filing regularity and turnover", "ADDITIONAL", "D", "Business stability", msme, kinds.contains("GST")));
        out.add(new Item("uw", "Engine decision with reasons", "MINIMUM", "D", "Decision, PD, band, reason codes", true, decided));
        out.add(new Item("uw", "Reviewer decision and reason", "MINIMUM", "O", "Audit of REFER cases", d != null && "REFER".equals(d.getDecision()), approved != null || sr.stream().anyMatch(s -> "DECLINED".equals(s.getAction()))));
        // Stage 6: credit memo
        var memo = memos.findFirstByApplicationIdOrderByVersionDesc(appId).orElse(null);
        out.add(new Item("cam", "Memo text, edits, signer, time", "MINIMUM", "D", "Credit memo on file", decided, memo != null));
        out.add(new Item("cam", "Deviation list with justification", "MINIMUM", "O", "Needed when a rule was deviated", d != null && "REFER".equals(d.getDecision()),
                memo != null && "SIGNED".equals(memo.getStatus())));
        out.add(new Item("cam", "Additional conditions proposed", "ADDITIONAL", "O", "Conditions on sanction", false, approved != null && approved.getConditionsJson() != null));
        // Stage 7: sanction
        out.add(new Item("sanction", "Sanctioned amount, tenure, rate, EMI, fees", "MINIMUM", "D", "KFS", true, approved != null));
        out.add(new Item("sanction", "APR, total payable, schedule", "MINIMUM", "D", "KFS disclosure", true, approved != null && approved.getKfsJson() != null));
        out.add(new Item("sanction", "Approver ID, level, decision, time", "MINIMUM", "O", "Delegation audit", true, approved != null));
        out.add(new Item("sanction", "KFS acceptance OTP and language", "MINIMUM", "B", "No execution before acceptance", true, approved != null && approved.getKfsAcceptedAt() != null));
        // Stage 8: disbursement
        out.add(new Item("disb", "Disbursement account, IFSC, penny-drop", "MINIMUM", "B", "Funds only to the borrower", true, has(a.getBankAccountNo()) && ds.stream().anyMatch(x -> x.getNameMatchScore() != null)));
        out.add(new Item("disb", "e-Sign and eNACH references", "MINIMUM", "F", "Agreement and repayment mandate", true, done != null));
        out.add(new Item("disb", "UTR, amount, date", "MINIMUM", "F", "Proof of payment", true, done != null && done.getUtr() != null));
        out.add(new Item("disb", "LMS loan ID", "MINIMUM", "F", "Loan servicing", true, done != null && done.getLmsPayload() != null));
        return out;
    }
}
