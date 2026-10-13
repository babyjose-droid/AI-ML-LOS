package com.rhythm.los.document;

/** Port for document reading. HttpDocumentReader calls the AI service; FilenameDocumentReader is the offline mock. */
public interface DocumentReader {
    String name();

    /** True when readings carry extracted fields that KYC can use. */
    boolean extractsFields();

    DocumentReading read(byte[] bytes, String contentType, String fileName, String declaredType);

    /** True when bank statements are analysed transaction by transaction (AI service). */
    default boolean readsStatements() { return false; }

    /** Full bank statement analysis. The reading's fields carry the header (holder, masked account, IFSC, period). */
    default StatementReading analyzeStatement(byte[] bytes, String contentType, String fileName, String holderHint, Double declaredIncome) {
        throw new UnsupportedOperationException("Statement analysis needs the AI service");
    }

    /** Removes a PDF password so only an unlocked copy is stored; the password itself is never kept. */
    default byte[] unlockPdf(byte[] pdf, String password) {
        throw com.rhythm.los.common.ApiException.unprocessable("UNLOCK_UNAVAILABLE",
                "Password-protected PDFs need the AI document service. Upload an unlocked copy.");
    }

    record StatementReading(DocumentReading reading, String analysisJson) {}

    /** A named file sent to the AI service. For video KYC frames the name starts with the step (front-, left-, right-, code-, pan-). */
    record Part(String name, byte[] bytes, String contentType) {}

    /** True when video KYC frames can be analysed (face match, liveness, PAN on camera). */
    default boolean analysesVideoKyc() { return false; }

    /** Video KYC analysis; returns the AI service's JSON. */
    default String analyzeVideoKyc(java.util.List<Part> frames, java.util.List<Part> idImages, String expectedPan) {
        throw new UnsupportedOperationException("Video KYC analysis needs the AI service");
    }
}
