package com.prasad.labelstudio;

/**
 * Draws a barcode / QR bit matrix as a small SVG so the editor preview shows exactly the
 * pattern that will be printed (the same ZXing matrix). Pure Java.
 */
final class CodeSvg {

    interface Bits {
        int width();

        int height();

        boolean get(int x, int y);
    }

    private CodeSvg() { }

    /** One-dimensional barcode: only the first row is needed; stretched to the box height. */
    static String barcode(Bits bits) {
        int w = bits.width();
        StringBuilder d = new StringBuilder();
        int x = 0;
        while (x < w) {
            if (bits.get(x, 0)) {
                int start = x;
                while (x < w && bits.get(x, 0)) x++;
                d.append('M').append(start).append(" 0h").append(x - start).append("v1h-").append(x - start).append('z');
            } else {
                x++;
            }
        }
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + w
            + " 1\" preserveAspectRatio=\"none\" shape-rendering=\"crispEdges\">"
            + "<path d=\"" + d + "\" fill=\"#000\"/></svg>";
    }

    /** Two-dimensional code (QR). */
    static String matrix(Bits bits) {
        int w = bits.width();
        int h = bits.height();
        StringBuilder d = new StringBuilder();
        for (int y = 0; y < h; y++) {
            int x = 0;
            while (x < w) {
                if (bits.get(x, y)) {
                    int start = x;
                    while (x < w && bits.get(x, y)) x++;
                    d.append('M').append(start).append(' ').append(y)
                        .append('h').append(x - start).append("v1h-").append(x - start).append('z');
                } else {
                    x++;
                }
            }
        }
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + w + " " + h
            + "\" preserveAspectRatio=\"xMidYMid meet\" shape-rendering=\"crispEdges\">"
            + "<path d=\"" + d + "\" fill=\"#000\"/></svg>";
    }
}
