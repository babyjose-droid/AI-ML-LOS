package com.rhythm.los.document;

/** Port for document reading. HttpDocumentReader calls the AI service; FilenameDocumentReader is the offline mock. */
public interface DocumentReader {
    String name();

    /** True when readings carry extracted fields that KYC can use. */
    boolean extractsFields();

    DocumentReading read(byte[] bytes, String contentType, String fileName, String declaredType);
}
