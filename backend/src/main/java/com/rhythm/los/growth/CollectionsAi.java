package com.rhythm.los.growth;

import java.time.LocalTime;
import java.util.List;

/**
 * Collections scoring: propensity to pay, the next channel and the best time to reach the borrower. A transparent
 * starter model (logistic over repayment behaviour and promise-to-pay history) until enough contact outcomes exist
 * to train one. Contact is only between 8 am and 7 pm (RBI fair practices code / recovery agent guidelines).
 */
public final class CollectionsAi {
    private CollectionsAi() {}

    public static final LocalTime CONTACT_FROM = LocalTime.of(8, 0);
    public static final LocalTime CONTACT_TO = LocalTime.of(19, 0);

    public record Recommendation(double propensity, String channel, String bestTime, String script, int priority, List<String> drivers) {}

    public static double propensity(int dpd, int bounces3m, double behaviourPd, int paidEmis, int ptpKept, int ptpBroken, int daysSincePayment) {
        double z = 1.6 - 0.025 * Math.min(dpd, 180) - 0.35 * bounces3m - 2.0 * behaviourPd + 0.05 * Math.min(paidEmis, 24)
                + 0.6 * Math.min(ptpKept, 3) - 0.8 * Math.min(ptpBroken, 3) - 0.01 * Math.min(daysSincePayment, 180);
        return Math.round(1000.0 / (1 + Math.exp(-z))) / 1000.0;
    }

    public static boolean withinContactHours(LocalTime t) { return !t.isBefore(CONTACT_FROM) && t.isBefore(CONTACT_TO); }

    /** Segment default, replaced by the hour at which this borrower has answered before (when there is one). */
    public static String bestTime(String segment, Integer answeredHour) {
        if (answeredHour != null && answeredHour >= 8 && answeredHour < 19)
            return String.format("%d:00–%d:00 (answered at this hour before)", answeredHour, answeredHour + 1);
        return "SALARIED".equals(segment) ? "8–9 am or 6–7 pm (outside working hours)"
                : "MSME".equals(segment) || "SELF_EMPLOYED".equals(segment) ? "11 am–1 pm (after the shop opens, before lunch)"
                : "10 am–12 pm";
    }

    public static Recommendation recommend(int dpd, int ews, double propensity, int ptpBroken, String segment, Integer answeredHour, boolean openPtp) {
        String channel, script;
        if (dpd == 0) { channel = "WHATSAPP"; script = "Friendly reminder of the next instalment date and amount; offer the payment link."; }
        else if (dpd <= 7) { channel = "SMS"; script = "Instalment missed: payment link and the branch number; no charges mentioned beyond the KFS."; }
        else if (dpd <= 30) { channel = "CALL"; script = "Ask what happened, agree a date to pay (promise to pay) and the amount."; }
        else if (dpd <= 60) { channel = ptpBroken > 0 || propensity < 0.3 ? "FIELD_VISIT" : "CALL";
            script = "Two instalments due: understand the cash flow problem; if it is temporary, consider restructuring."; }
        else if (dpd <= 90) { channel = "FIELD_VISIT"; script = "Visit the business or home; collect, or assess restructuring before the account turns NPA."; }
        else { channel = "NOTICE"; script = "NPA: formal recovery notice; field visit and legal review by the recovery team."; }
        if (openPtp && dpd <= 60) { channel = "WHATSAPP"; script = "A promise to pay is open: courtesy reminder on the promised date."; }
        int bucket = dpd == 0 ? 0 : dpd <= 30 ? 1 : dpd <= 60 ? 2 : dpd <= 90 ? 3 : 4;
        // the earlier buckets and the less likely payers first: rolling forward is what costs most
        int priority = (int) Math.round(Math.min(100, bucket * 18 + (1 - propensity) * 30 + Math.min(ews, 100) * 0.2 + (bucket == 3 ? 10 : 0)));
        List<String> drivers = new java.util.ArrayList<>();
        if (dpd > 0) drivers.add(dpd + " days past due");
        if (ptpBroken > 0) drivers.add(ptpBroken + " broken promise(s) to pay");
        if (propensity >= 0.6) drivers.add("Likely to pay (" + Math.round(propensity * 100) + "%)");
        else if (propensity < 0.3) drivers.add("Unlikely to pay without a visit (" + Math.round(propensity * 100) + "%)");
        return new Recommendation(propensity, channel, bestTime(segment, answeredHour), script, priority, drivers);
    }
}
