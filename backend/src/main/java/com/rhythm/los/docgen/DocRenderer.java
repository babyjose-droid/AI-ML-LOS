package com.rhythm.los.docgen;

import java.util.List;

/** Makes PDFs from filled-in HTML and reads Word templates. The AI service does both; without it a plain PDF is made. */
public interface DocRenderer {
    String name();
    byte[] pdf(String html, String title);
    /** A Word (.docx) template as HTML; null when this renderer cannot read Word. */
    WordImport word(byte[] docx);

    record WordImport(String html, List<String> fields, List<String> messages) {}
}
