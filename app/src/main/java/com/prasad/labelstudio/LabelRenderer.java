package com.prasad.labelstudio;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.Base64;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Draws one label from the saved editor design and converts it to P58D raster bytes.
 *
 * The geometry is the P58D-calibrated mapping the app already used: 8 dots per mm, at most
 * 384 dots (48 mm) across, a small safety inset, no extra feed on gap labels (extra feed
 * accumulates and drifts away from the die-cut positions).
 *
 * Design coordinates are 0.1 mm units: a 50 x 30 mm label is 500 x 300.
 */
final class LabelRenderer implements PrintCoordinator.Rasterizer {

    @Override
    public byte[] render(LabelJob job, boolean lastLabel) throws Exception {
        JSONObject context = (JSONObject) job.context;
        JSONObject design = context == null ? null : context.optJSONObject("design");
        if (design == null) throw new IllegalArgumentException("this label has no design");
        JSONObject options = context.optJSONObject("options");

        JSONObject page = design.optJSONObject("page");
        float pageW = (float) Math.max(10, page == null ? 50 : page.optDouble("w", 50));
        float pageH = (float) Math.max(10, page == null ? 30 : page.optDouble("h", 30));

        final int width = Math.max(8, Math.min(384, Math.round(pageW * 8f)));
        final int height = Math.max(8, Math.round(pageH * 8f));

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.WHITE);

            float insetX = Math.min(8f, width * 0.04f);
            float insetY = Math.min(8f, height * 0.04f);
            float sx = (width - 2f * insetX) / (pageW * 10f);
            float sy = (height - 2f * insetY) / (pageH * 10f);

            JSONArray objects = design.optJSONArray("objects");
            if (objects != null) {
                for (int i = 0; i < objects.length(); i++) {
                    JSONObject obj = objects.optJSONObject(i);
                    if (obj != null) drawObject(canvas, obj, job, insetX, insetY, sx, sy);
                }
            }

            int darkness = options == null ? 160 : clamp(options.optInt("darkness", 160), 80, 220);
            double alignMm = options == null ? 0 : clampD(options.optDouble("alignMm", 0), -3, 3);
            int shiftDots = (int) Math.round(alignMm * 8.0);

            // Feed is always measured at 8 dots per mm (the printer's vertical resolution).
            // Gap labels: the label itself is 30 mm of raster but the roll advances by
            // label + gap, so after every label we feed the gap length (default 3 mm).
            // If labels creep UP the roll over a batch, raise the gap; if they creep DOWN, lower it.
            String paperType = options == null ? "gap" : options.optString("paperType", "gap");
            double feedMm;
            if ("gap".equalsIgnoreCase(paperType)) {
                feedMm = options == null ? 3 : options.optDouble("gapMm", 3);
            } else {
                feedMm = options == null ? 0 : options.optDouble(lastLabel ? "lastFeedMm" : "feedMm", 0);
            }
            int feedDots = (int) Math.round(clampD(feedMm, 0, 10) * 8.0);
            // Gap paper: the gap is sent as blank raster rows (every printer obeys these), not
            // as a separate feed command that some firmware ignores.
            int gapRows = 0;
            if ("gap".equalsIgnoreCase(paperType)) {
                gapRows = feedDots;
                feedDots = 0;
            }

            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            int[] luminance = new int[width * (height + gapRows)];
            java.util.Arrays.fill(luminance, 255);
            for (int i = 0; i < pixels.length; i++) luminance[i] = Color.red(pixels[i]);

            return RasterCodec.encode(width, height + gapRows, luminance, darkness, shiftDots, feedDots);
        } finally {
            bitmap.recycle();
        }
    }

    private void drawObject(Canvas c, JSONObject obj, LabelJob job,
                            float insetX, float insetY, float sx, float sy) throws Exception {
        String type = obj.optString("type", "text");
        float ex = insetX + (float) obj.optDouble("x", 0) * sx;
        float ey = insetY + (float) obj.optDouble("y", 0) * sy;
        float w = (float) obj.optDouble("w", 100) * sx;
        float h = (float) obj.optDouble("h", 40) * sy;
        float rotation = (float) obj.optDouble("rotation", 0);

        c.save();
        try {
            c.rotate(rotation, ex + w / 2f, ey + h / 2f);

            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(color(obj.optString("color", "#000000"), Color.BLACK));

            if ("text".equals(type) || "icon".equals(type)) {
                drawText(c, p, obj, job, ex, ey, w, h, sx);
            } else if ("rect".equals(type)) {
                drawRect(c, p, obj, ex, ey, w, h, sx);
            } else if ("line".equals(type)) {
                p.setColor(color(obj.optString("stroke", "#111111"), Color.BLACK));
                p.setStrokeWidth(Math.max(1.2f, (float) obj.optDouble("strokeWidth", 3) * sx));
                c.drawLine(ex, ey, ex + w, ey + h, p);
            } else if ("barcode".equals(type)) {
                drawBarcode(c, obj, job, ex, ey, w, h, sx);
            } else if ("qr".equals(type)) {
                drawQr(c, obj, job, ex, ey, w, h);
            } else if ("image".equals(type)) {
                drawImage(c, obj, ex, ey, w, h);
            }
        } finally {
            c.restore();
        }
    }

    private void drawText(Canvas c, Paint p, JSONObject obj, LabelJob job,
                          float ex, float ey, float w, float h, float sx) {
        String raw = obj.optString("text", "");
        // A line that shows the barcode number (e.g. "No. {BARCODE}") is left out when the label has no barcode.
        if (raw.toUpperCase(java.util.Locale.US).contains("{BARCODE}") && (job.barcode == null || job.barcode.trim().length() == 0)) return;
        String text = Tokens.resolve(raw, job);
        if (text.length() == 0) return;

        p.setTypeface(Typeface.create(
            family(obj.optString("font", "sans")),
            style(obj.optBoolean("bold", false), obj.optBoolean("italic", false))));
        p.setTextSize(Math.max(8f, (float) obj.optDouble("size", 20) * sx));

        String align = obj.optString("align", "left");
        float x;
        if ("center".equals(align)) {
            p.setTextAlign(Paint.Align.CENTER);
            x = ex + w / 2f;
        } else if ("right".equals(align)) {
            p.setTextAlign(Paint.Align.RIGHT);
            x = ex + w;
        } else {
            p.setTextAlign(Paint.Align.LEFT);
            x = ex;
        }
        c.drawText(text, x, ey + Math.min(h, p.getTextSize() + 2), p);
    }

    private void drawRect(Canvas c, Paint p, JSONObject obj,
                          float ex, float ey, float w, float h, float sx) {
        RectF r = new RectF(ex, ey, ex + w, ey + h);
        float radius = (float) Math.max(0, Math.min(40, obj.optDouble("radius", 0))) * sx;

        String fill = obj.optString("fill", "none");
        if (fill.length() > 0 && !"none".equalsIgnoreCase(fill)) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(color(fill, Color.WHITE));
            c.drawRoundRect(r, radius, radius, p);
        }

        double border = obj.optDouble("strokeWidth", 2);
        if (border > 0) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1.2f, (float) border * sx));
            p.setColor(color(obj.optString("stroke", "#111111"), Color.BLACK));
            c.drawRoundRect(r, radius, radius, p);
        }
    }

    private void drawBarcode(Canvas c, JSONObject obj, LabelJob job,
                             float ex, float ey, float w, float h, float sx) throws Exception {
        String value = Tokens.resolve(obj.optString("text", ""), job).trim();
        if (value.length() == 0) return;   // e.g. "{BARCODE}" on a no-barcode label: draw nothing

        boolean caption = obj.optBoolean("showText", true);
        float barsH = caption ? h * 0.72f : h;

        BitMatrix m = new MultiFormatWriter().encode(
            value, BarcodeFormat.CODE_128, Math.max(80, (int) w), Math.max(24, (int) barsH));

        Paint bp = new Paint();
        bp.setColor(Color.BLACK);
        float cellW = w / m.getWidth();
        float cellH = barsH / m.getHeight();
        for (int x = 0; x < m.getWidth(); x++) {
            for (int y = 0; y < m.getHeight(); y++) {
                if (m.get(x, y)) {
                    c.drawRect(ex + x * cellW, ey + y * cellH, ex + (x + 1) * cellW, ey + (y + 1) * cellH, bp);
                }
            }
        }

        if (caption) {
            bp.setTextSize(Math.max(10f, 14f * sx));
            bp.setTextAlign(Paint.Align.CENTER);
            c.drawText(value, ex + w / 2f, ey + h - 1, bp);
        }
    }

    private void drawQr(Canvas c, JSONObject obj, LabelJob job,
                        float ex, float ey, float w, float h) throws Exception {
        String value = Tokens.resolve(obj.optString("text", ""), job).trim();
        if (value.length() == 0) return;

        BitMatrix m = new MultiFormatWriter().encode(
            value, BarcodeFormat.QR_CODE, Math.max(64, (int) w), Math.max(64, (int) h));

        Paint qp = new Paint();
        qp.setColor(Color.BLACK);
        float cellW = w / m.getWidth();
        float cellH = h / m.getHeight();
        for (int x = 0; x < m.getWidth(); x++) {
            for (int y = 0; y < m.getHeight(); y++) {
                if (m.get(x, y)) {
                    c.drawRect(ex + x * cellW, ey + y * cellH, ex + (x + 1) * cellW, ey + (y + 1) * cellH, qp);
                }
            }
        }
    }

    private void drawImage(Canvas c, JSONObject obj, float ex, float ey, float w, float h) {
        String src = obj.optString("src", "");
        int comma = src.indexOf(',');
        if (comma < 0 || !src.substring(0, comma).contains("base64")) return;
        try {
            byte[] raw = Base64.decode(src.substring(comma + 1), Base64.DEFAULT);
            Bitmap image = BitmapFactory.decodeByteArray(raw, 0, raw.length);
            if (image != null) {
                c.drawBitmap(image, null, new RectF(ex, ey, ex + w, ey + h), new Paint(Paint.FILTER_BITMAP_FLAG));
                image.recycle();
            }
        } catch (Exception ignored) {
            // a damaged picture is skipped rather than stopping the whole batch
        }
    }

    // Preview ---------------------------------------------------------------------------

    /**
     * SVG of the exact barcode / QR pattern that will be printed, for the editor preview.
     * Returns "" when there is nothing to draw.
     */
    static String codeSvg(String kind, String text) {
        try {
            if (text == null || text.trim().length() == 0) return "";
            boolean qr = "qr".equals(kind);
            final BitMatrix m = new MultiFormatWriter().encode(
                text.trim(), qr ? BarcodeFormat.QR_CODE : BarcodeFormat.CODE_128, 1, 1);
            CodeSvg.Bits bits = new CodeSvg.Bits() {
                @Override
                public int width() {
                    return m.getWidth();
                }

                @Override
                public int height() {
                    return m.getHeight();
                }

                @Override
                public boolean get(int x, int y) {
                    return m.get(x, y);
                }
            };
            return qr ? CodeSvg.matrix(bits) : CodeSvg.barcode(bits);
        } catch (Exception e) {
            return "";
        }
    }

    // Helpers ---------------------------------------------------------------------------

    private static String family(String font) {
        if ("serif".equals(font)) return "serif";
        if ("mono".equals(font)) return "monospace";
        if ("cond".equals(font)) return "sans-serif-condensed";
        return "sans-serif";
    }

    private static int style(boolean bold, boolean italic) {
        if (bold) return italic ? Typeface.BOLD_ITALIC : Typeface.BOLD;
        return italic ? Typeface.ITALIC : Typeface.NORMAL;
    }

    private static int color(String value, int fallback) {
        try {
            return Color.parseColor(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double clampD(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
