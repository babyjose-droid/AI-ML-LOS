package com.rhythm.los.document;

import java.util.List;
import java.util.Map;

/** Result of reading one document (AI service or mock). Field values never contain a full Aadhaar number. */
public record DocumentReading(
        String status,                 // OK, NEEDS_REVIEW, UNREADABLE
        String documentType,
        double typeConfidence,
        Map<String, FieldValue> fields,
        List<Map<String, Object>> validations,
        Map<String, Object> quality,
        Map<String, Object> tamper,
        List<String> reviewReasons,
        double overallConfidence,
        String engine,
        boolean masked,
        byte[] maskedImage) {

    public record FieldValue(String value, double confidence, String source) {}

    public double tamperScore() {
        Object s = tamper == null ? null : tamper.get("score");
        return s instanceof Number n ? n.doubleValue() : 0;
    }
}
