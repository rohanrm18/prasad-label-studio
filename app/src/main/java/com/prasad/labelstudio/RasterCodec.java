package com.prasad.labelstudio;

import java.io.ByteArrayOutputStream;

/**
 * Turns a grey-scale label image into the byte stream the P58D understands:
 * ESC @ (init), GS v 0 (raster bit image), then optional ESC J (extra feed).
 * This is the same command sequence the app has always used on the P58D. Pure Java.
 */
final class RasterCodec {

    private RasterCodec() { }

    /**
     * @param luminance  width*height values 0..255, row by row (0 = black)
     * @param darkness   a pixel darker than this prints black (80..220, default 160)
     * @param shiftDots  moves the picture left (-) or right (+) by this many dots
     * @param feedDots   extra paper feed after the label (0 = none, max 255)
     */
    static byte[] encode(int width, int height, int[] luminance,
                         int darkness, int shiftDots, int feedDots) {
        int bytesPerRow = (width + 7) / 8;
        byte[] raster = new byte[bytesPerRow * height];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int tx = x + shiftDots;
                if (tx < 0 || tx >= width) continue;
                if (luminance[y * width + x] < darkness) {
                    raster[y * bytesPerRow + (tx / 8)] |= (byte) (0x80 >> (tx % 8));
                }
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(raster.length + 16);
        out.write(0x1B);
        out.write(0x40);
        out.write(0x1D);
        out.write(0x76);
        out.write(0x30);
        out.write(0x00);
        out.write(bytesPerRow & 0xFF);
        out.write((bytesPerRow >> 8) & 0xFF);
        out.write(height & 0xFF);
        out.write((height >> 8) & 0xFF);
        out.write(raster, 0, raster.length);
        if (feedDots > 0) {
            out.write(0x1B);
            out.write(0x4A);
            out.write(Math.min(255, feedDots));
        }
        return out.toByteArray();
    }

    /** Printer-init only. Used to check that the Bluetooth link is alive. */
    static byte[] initCommand() {
        return new byte[]{0x1B, 0x40};
    }
}
