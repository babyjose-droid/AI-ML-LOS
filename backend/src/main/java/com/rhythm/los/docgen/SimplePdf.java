package com.rhythm.los.docgen;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A plain text PDF (Helvetica, A4) from the document HTML, used when the AI document service is not configured
 * (tests, a quick local run). Headings are bold; tables become lines; text outside Latin-1 is replaced. The real
 * renderer is the AI service.
 */
public final class SimplePdf implements DocRenderer {
    @Override public String name() { return "BASIC-PDF"; }
    @Override public WordImport word(byte[] docx) { return null; }

    @Override
    public byte[] pdf(String html, String title) {
        List<String[]> lines = new ArrayList<>();   // [font, text]
        String s = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", "")
                .replaceAll("(?i)<h[1-3][^>]*>", "\n\u0001").replaceAll("(?i)</h[1-3]>", "\n")
                .replaceAll("(?i)<(br|/p|/div|/li|/tr|/table)[^>]*>", "\n").replaceAll("(?i)<li[^>]*>", "\n- ")
                .replaceAll("(?i)</t[dh]>", "  |  ").replaceAll("<[^>]+>", "")
                .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
                .replace("₹", "Rs ");
        for (String raw : s.split("\n")) {
            boolean head = raw.startsWith("\u0001");
            String t = raw.replace("\u0001", "").replaceAll("\\s+", " ").trim();
            if (t.isEmpty()) continue;
            for (String w : wrap(t, head ? 70 : 95)) lines.add(new String[]{head ? "F2" : "F1", w});
        }
        int perPage = 52;
        List<String> pages = new ArrayList<>();
        for (int i = 0; i < Math.max(1, lines.size()); i += perPage) {
            StringBuilder c = new StringBuilder("BT\n");
            int y = 800;
            for (int j = i; j < Math.min(lines.size(), i + perPage); j++) {
                String[] l = lines.get(j);
                c.append("/").append(l[0]).append(l[0].equals("F2") ? " 12" : " 9.5").append(" Tf 1 0 0 1 50 ").append(y).append(" Tm (").append(pdfText(l[1])).append(") Tj\n");
                y -= 14.5;
            }
            c.append("ET\n");
            pages.add(c.toString());
        }
        return build(pages, title);
    }

    static List<String> wrap(String t, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String w : t.split(" ")) {
            if (cur.length() + w.length() + 1 > width && cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
            if (cur.length() > 0) cur.append(' ');
            cur.append(w);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    static String pdfText(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) {
            if (ch == '(' || ch == ')' || ch == '\\') b.append('\\').append(ch);
            else if (ch < 32 || ch > 255) b.append('?');
            else b.append(ch);
        }
        return b.toString();
    }

    static byte[] build(List<String> contents, String title) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        StringBuilder kids = new StringBuilder();
        int n = contents.size();
        // objects: 1 catalog, 2 pages, 3 font, 4 bold font, 5 info, then (page, content) pairs
        for (int i = 0; i < n; i++) kids.append(6 + 2 * i).append(" 0 R ");
        List<String> objs = new ArrayList<>();
        objs.add("<< /Type /Catalog /Pages 2 0 R >>");
        objs.add("<< /Type /Pages /Kids [" + kids + "] /Count " + n + " >>");
        objs.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>");
        objs.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>");
        objs.add("<< /Title (" + pdfText(title == null ? "Loan document" : title) + ") /Producer (Rhythm LOS) >>");
        for (int i = 0; i < n; i++) {
            objs.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents " + (7 + 2 * i) + " 0 R >>");
            byte[] c = contents.get(i).getBytes(StandardCharsets.ISO_8859_1);
            objs.add("<< /Length " + c.length + " >>\nstream\n" + contents.get(i) + "endstream");
        }
        write(out, "%PDF-1.4\n");
        for (int i = 0; i < objs.size(); i++) {
            offsets.add(out.size());
            write(out, (i + 1) + " 0 obj\n" + objs.get(i) + "\nendobj\n");
        }
        int xref = out.size();
        StringBuilder x = new StringBuilder("xref\n0 " + (objs.size() + 1) + "\n0000000000 65535 f \n");
        for (int o : offsets) x.append(String.format("%010d 00000 n \n", o));
        x.append("trailer\n<< /Size ").append(objs.size() + 1).append(" /Root 1 0 R /Info 5 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        write(out, x.toString());
        return out.toByteArray();
    }

    static void write(ByteArrayOutputStream out, String s) { out.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1)); }
}
