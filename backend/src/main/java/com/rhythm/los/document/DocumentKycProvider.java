package com.rhythm.los.document;

import com.rhythm.los.application.LoanApplication;
import com.rhythm.los.integration.VendorModels.*;
import com.rhythm.los.integration.Vendors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

/**
 * KYC derived from the verified documents (Phase 2). Compares what the AI read on PAN and the officially valid
 * document (Aadhaar, voter ID, driving licence or passport) with the application, and records every check.
 *
 * This establishes consistency, not authenticity. Online validation against the issuers (NSDL/Protean PAN,
 * UIDAI or DigiLocker, CKYC) is added in the integrations phase through the same KycProvider port.
 */
@Component
@Primary
@ConditionalOnExpression("'${rhythm.document-ai.url:}' != ''")
public class DocumentKycProvider implements Vendors.KycProvider {
    static final List<String> OVD = List.of("AADHAAR", "VOTER_ID", "DRIVING_LICENCE", "PASSPORT");
    static final double CONTACT_UNVERIFIED = 0.9;

    private final DocumentService documents;
    private final KycCheckRepository checks;

    public DocumentKycProvider(DocumentService documents, KycCheckRepository checks) {
        this.documents = documents;
        this.checks = checks;
    }

    @Override public String name() { return "DOCUMENT-AI"; }
    @Override public boolean needsVerifiedDocuments() { return true; }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public KycBundle verify(LoanApplication a) {
        Map<String, Map<String, DocumentReading.FieldValue>> byType = new LinkedHashMap<>();
        for (AppDocument d : documents.list(a.getId())) {
            if ("VERIFIED".equals(d.getStatus())) byType.putIfAbsent(d.getDocType(), documents.extracted(d));
        }
        List<KycCheck> out = new ArrayList<>();
        Long id = a.getId();

        // PAN
        Map<String, DocumentReading.FieldValue> pan = byType.get("PAN");
        double idMatch = 0.5, panName = -1, panDob = -1;
        String panOnCard = null, nameOnPan = null;
        if (pan != null) {
            panOnCard = v(pan, "pan");
            idMatch = a.getPan().equalsIgnoreCase(panOnCard) ? 1.0 : 0.0;
            out.add(new KycCheck(id, "PAN on card matches application", idMatch, a.getPan(), panOnCard, "PAN"));
            nameOnPan = v(pan, "name");
            if (nameOnPan != null) {
                panName = NameMatch.score(a.getApplicantName(), nameOnPan);
                out.add(new KycCheck(id, "Name on PAN matches", panName, a.getApplicantName(), nameOnPan, "PAN"));
            }
            panDob = dob(a.getDob(), v(pan, "dob"));
            if (panDob >= 0) out.add(new KycCheck(id, "Date of birth on PAN matches", panDob, a.getDob().toString(), v(pan, "dob"), "PAN"));
        }

        // Officially valid document
        String ovdType = OVD.stream().filter(byType::containsKey).findFirst().orElse(null);
        Map<String, DocumentReading.FieldValue> ovd = ovdType == null ? null : byType.get(ovdType);
        double ovdName = -1, ovdDob = -1, address = -1;
        String nameOnOvd = null, addrOnOvd = null;
        if (ovd != null) {
            nameOnOvd = "PASSPORT".equals(ovdType) ? join(v(ovd, "givenNames"), v(ovd, "surname")) : v(ovd, "name");
            if (nameOnOvd != null) {
                ovdName = NameMatch.score(a.getApplicantName(), nameOnOvd);
                out.add(new KycCheck(id, "Name on " + label(ovdType) + " matches", ovdName, a.getApplicantName(), nameOnOvd, ovdType));
            }
            ovdDob = dob(a.getDob(), v(ovd, "dob"));
            if (ovdDob >= 0) out.add(new KycCheck(id, "Date of birth on " + label(ovdType) + " matches", ovdDob, a.getDob().toString(), v(ovd, "dob"), ovdType));
            addrOnOvd = v(ovd, "address");
            String pin = v(ovd, "pincode");
            if (pin != null && a.getPincode() != null) {
                address = pin.equals(a.getPincode()) ? 1.0 : 0.5;
                out.add(new KycCheck(id, "PIN code on " + label(ovdType) + " matches", address, a.getPincode(), pin, ovdType));
            } else if (addrOnOvd != null && a.getCity() != null) {
                address = addrOnOvd.toUpperCase().contains(a.getCity().toUpperCase()) ? 0.85 : 0.5;
                out.add(new KycCheck(id, "City on " + label(ovdType) + " matches", address, a.getCity(), addrOnOvd, ovdType));
            }
        }
        if (nameOnPan != null && nameOnOvd != null) {
            out.add(new KycCheck(id, "Same name on PAN and " + label(ovdType), NameMatch.score(nameOnPan, nameOnOvd), nameOnPan, nameOnOvd, "PAN+" + ovdType));
        }

        // Supporting documents
        Map<String, DocumentReading.FieldValue> udyam = byType.get("UDYAM");
        if (udyam != null && v(udyam, "ownerName") != null) {
            out.add(new KycCheck(id, "Owner name on Udyam matches", NameMatch.score(a.getApplicantName(), v(udyam, "ownerName")),
                    a.getApplicantName(), v(udyam, "ownerName"), "UDYAM"));
        }
        Map<String, DocumentReading.FieldValue> slip = byType.get("SALARY_SLIP");
        if (slip != null && v(slip, "employeeName") != null) {
            out.add(new KycCheck(id, "Employee name on salary slip matches", NameMatch.score(a.getApplicantName(), v(slip, "employeeName")),
                    a.getApplicantName(), v(slip, "employeeName"), "SALARY_SLIP"));
        }
        if (pan == null) out.add(new KycCheck(id, "PAN document verified", 0, "PAN", "not on file", "PAN"));
        if (ovd == null) out.add(new KycCheck(id, "Officially valid document verified", 0, "Aadhaar, voter ID, licence or passport", "not on file", "OVD"));

        checks.deleteByApplicationId(id);
        checks.saveAll(out);

        double nameMatch = minPositive(panName, ovdName, 0.0);
        double dobMatch = minPositive(panDob, ovdDob, 0.0);
        double addr = address >= 0 ? address : 0.6;
        String regName = nameOnPan != null ? nameOnPan : nameOnOvd != null ? nameOnOvd : "";
        return new KycBundle(
                new PanResult(panOnCard, regName, pan == null ? "NOT_ON_FILE" : "READ_FROM_DOCUMENT", pan == null ? null : v(pan, "dob")),
                new AadhaarResult(ovd == null ? null : v(ovd, "aadhaarMasked"), nameOnOvd, ovd == null ? null : v(ovd, "dob"), addrOnOvd, addr),
                new CkycResult(false, null, CONTACT_UNVERIFIED),
                nameMatch, dobMatch, idMatch, addr, CONTACT_UNVERIFIED);
    }

    private static double minPositive(double a, double b, double dflt) {
        if (a < 0 && b < 0) return dflt;
        if (a < 0) return b;
        if (b < 0) return a;
        return Math.min(a, b);
    }

    private static String v(Map<String, DocumentReading.FieldValue> m, String k) {
        DocumentReading.FieldValue f = m.get(k);
        return f == null || f.value() == null || f.value().isBlank() ? null : f.value().trim();
    }

    private static String join(String a, String b) {
        String s = ((a == null ? "" : a) + " " + (b == null ? "" : b)).trim();
        return s.isEmpty() ? null : s;
    }

    /** 1 exact, 0.6 same year (year-only documents), 0 different, -1 not readable. */
    static double dob(LocalDate expected, String found) {
        if (found == null) return -1;
        if (found.length() == 4) return found.equals(String.valueOf(expected.getYear())) ? 0.6 : 0;
        try { return LocalDate.parse(found).equals(expected) ? 1 : 0; } catch (Exception e) { return -1; }
    }

    private static String label(String type) {
        return switch (type) {
            case "AADHAAR" -> "Aadhaar";
            case "VOTER_ID" -> "voter ID";
            case "DRIVING_LICENCE" -> "driving licence";
            case "PASSPORT" -> "passport";
            default -> type;
        };
    }
}
