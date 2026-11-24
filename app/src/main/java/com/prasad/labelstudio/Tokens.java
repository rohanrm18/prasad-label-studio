package com.prasad.labelstudio;

/**
 * Fills {STYLE} {MODEL} {CATEGORY} {COLOUR} {SIZE} {BARCODE} in label text with the
 * details of the label being printed, so one saved design serves every size and style.
 * Unknown {words} are left exactly as typed. Pure Java.
 */
final class Tokens {

    private Tokens() { }

    static String resolve(String text, LabelJob job) {
        if (text == null) return "";
        if (text.indexOf('{') < 0) return text;

        StringBuilder out = new StringBuilder(text.length() + 16);
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '{') {
                int close = text.indexOf('}', i + 1);
                if (close > i) {
                    String value = lookup(text.substring(i + 1, close), job);
                    if (value != null) {
                        out.append(value);
                        i = close + 1;
                        continue;
                    }
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** Returns null when the name is not a known token. */
    private static String lookup(String name, LabelJob job) {
        String key = name.trim().toUpperCase(java.util.Locale.US);
        if (key.equals("STYLE")) return nz(job.style);
        if (key.equals("MODEL")) return nz(job.model);
        if (key.equals("CATEGORY")) return nz(job.category);
        if (key.equals("COLOUR") || key.equals("COLOR")) return nz(job.colour);
        if (key.equals("SIZE")) return nz(job.size);
        if (key.equals("BARCODE")) return nz(job.barcode);
        return null;
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }
}
