package com.rhythm.los.document;

import java.util.List;
import java.util.Map;

/**
 * Offline mock used when no AI service is configured (unit and journey tests).
 * A file name containing "blur" is unreadable; "tamper" raises an editing signal.
 */
public class FilenameDocumentReader implements DocumentReader {
    @Override public String name() { return "MOCK-DOC"; }
    @Override public boolean extractsFields() { return false; }

    @Override
    public DocumentReading read(byte[] bytes, String contentType, String fileName, String declaredType) {
        String n = fileName == null ? "" : fileName.toLowerCase();
        if (n.contains("blur")) {
            return new DocumentReading("UNREADABLE", declaredType, 0.4, Map.of(), List.of(), Map.of("issues", List.of("Image is blurred")),
                    Map.of("score", 0), List.of("Image is blurred"), 0.41, name(), false, null);
        }
        boolean tamper = n.contains("tamper");
        return new DocumentReading("OK", declaredType, 0.96, Map.of(), List.of(), Map.of("issues", List.of()),
                tamper ? Map.of("score", 0.6, "signals", List.of("Possible edit detected: fonts and metadata do not match the issuer template"))
                        : Map.of("score", 0, "signals", List.of()),
                List.of(), 0.96, name(), false, null);
    }
}
