package com.prasad.labelstudio;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Plain-Java tests for the pure core (no Android, no JUnit needed).
 *   javac -d out app/src/main/java/com/prasad/labelstudio/{BarcodeSequence,Tokens,LabelJob,JsonText,PrintCoordinator,RasterCodec,CodeSvg}.java tests/CoreTests.java
 *   java -cp out com.prasad.labelstudio.CoreTests
 */
public class CoreTests {

    static int pass = 0, fail = 0;

    static void check(boolean ok, String name) {
        if (ok) { pass++; System.out.println("PASS " + name); }
        else { fail++; System.out.println("FAIL " + name); }
    }

    static void eq(Object got, Object want, String name) {
        boolean ok = got == null ? want == null : got.equals(want);
        if (ok) { pass++; System.out.println("PASS " + name); }
        else { fail++; System.out.println("FAIL " + name + "  got=[" + got + "] want=[" + want + "]"); }
    }

    // ---- fakes -------------------------------------------------------------------

    static class MemoryStore implements BarcodeSequence.Store {
        int next, pendingNumber; String pendingMeta; int saves;
        public int loadNext() { return next; }
        public int loadPendingNumber() { return pendingNumber; }
        public String loadPendingMeta() { return pendingMeta; }
        public void save(int n, int p, String m) { next = n; pendingNumber = p; pendingMeta = m; saves++; }
    }

    static class FakeLink implements PrintCoordinator.Link {
        boolean connected = true, probeOk = true, reconnectOk = true;
        int failWriteOnCall = -1;          // 1-based index of write() that throws
        int writes = 0, reconnects = 0;
        List<byte[]> sent = new ArrayList<byte[]>();
        public boolean isConnected() { return connected; }
        public boolean probe() { return probeOk; }
        public boolean reconnect() { reconnects++; if (reconnectOk) { connected = true; probeOk = true; } return reconnectOk; }
        public void write(byte[] d) throws IOException {
            writes++;
            if (writes == failWriteOnCall) { connected = false; throw new IOException("Broken pipe"); }
            sent.add(d);
        }
    }

    static class Recorder implements PrintCoordinator.Events {
        List<String> labels = new ArrayList<String>();
        String lastState = ""; int lastSent = -1; String lastMessage = "";
        String lastNext = ""; String lastPending = null;
        public void progress(String state, int sent, int total, String message) {
            if (!state.equals("printing")) { lastState = state; lastSent = sent; lastMessage = message; }
        }
        public void label(LabelJob job) { labels.add(job.barcode + "/" + job.size); }
        public void barcode(String next, String pendingMeta) { lastNext = next; lastPending = pendingMeta; }
    }

    static PrintCoordinator.Rasterizer okRaster = new PrintCoordinator.Rasterizer() {
        public byte[] render(LabelJob job, boolean last) { return new byte[]{1, 2, 3}; }
    };

    static List<LabelJob> jobs(LabelJob.BarcodeMode mode, String... sizes) {
        List<LabelJob> list = new ArrayList<LabelJob>();
        for (String s : sizes) {
            LabelJob j = new LabelJob();
            j.style = "PA-102"; j.model = "Kurta"; j.category = "Boys"; j.colour = "Maroon";
            j.size = s; j.mode = mode;
            list.add(j);
        }
        return list;
    }

    static final java.util.concurrent.Executor NOW = new java.util.concurrent.Executor() {
        public void execute(Runnable r) { r.run(); }
    };

    static PrintCoordinator coordinator(FakeLink l, PrintCoordinator.Rasterizer r, BarcodeSequence s, Recorder e) {
        return new PrintCoordinator(l, r, s, e, NOW, 0);
    }

    // ---- tests -------------------------------------------------------------------

    public static void main(String[] args) {
        sequenceBasics();
        tokens();
        autoNumbering();
        writeFailureIsUncertain();
        recoveryActions();
        deadLinkBeforeLabel();
        reconnectBeforeLabel();
        drawingFailureReleasesNumber();
        cancelStopsRemaining();
        limitReached();
        manualAndNoBarcode();
        reprintDoesNotTouchCounter();
        crashRecoveryFromDisk();
        rasterBytes();
        svg();
        System.out.println("\nCORE RESULT: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void sequenceBasics() {
        MemoryStore st = new MemoryStore();
        BarcodeSequence s = new BarcodeSequence(st);
        eq(s.peek(), "000001", "fresh counter starts at 000001");
        check(!s.setNext(0) && !s.setNext(1000000), "setNext rejects 0 and 1000000");
        check(s.setNext(250), "setNext accepts 250");
        eq(s.peek(), "000250", "next number shows 000250");
        eq(st.next, 250, "setNext is saved to disk");
    }

    static void tokens() {
        LabelJob j = new LabelJob();
        j.style = "PA-102"; j.model = "Kurta"; j.category = "Boys"; j.colour = "Maroon"; j.size = "12"; j.barcode = "000007";
        eq(Tokens.resolve("STYLE: {STYLE}", j), "STYLE: PA-102", "token STYLE");
        eq(Tokens.resolve("{size}/{Colour}/{color}", j), "12/Maroon/Maroon", "tokens are case-insensitive, COLOR too");
        eq(Tokens.resolve("{BARCODE}", j), "000007", "token BARCODE");
        eq(Tokens.resolve("{MODEL} {CATEGORY}", j), "Kurta Boys", "tokens MODEL CATEGORY");
        eq(Tokens.resolve("Price {MRP} {", j), "Price {MRP} {", "unknown braces left as typed");
        eq(Tokens.resolve("PA10228", j), "PA10228", "plain text unchanged");
        eq(Tokens.resolve(null, j), "", "null is empty");
        LabelJob d = new LabelJob(); d.style = "A$1\\B";
        eq(Tokens.resolve("{STYLE}|{MODEL}", d), "A$1\\B|", "special characters and empty fields are safe");
    }

    static void autoNumbering() {
        MemoryStore st = new MemoryStore(); BarcodeSequence seq = new BarcodeSequence(st);
        FakeLink link = new FakeLink(); Recorder ev = new Recorder();
        PrintCoordinator pc = coordinator(link, okRaster, seq, ev);
        check(pc.start(jobs(LabelJob.BarcodeMode.AUTO, "6", "6", "8", "10")), "bulk of 4 starts");
        eq(ev.labels.toString(), "[000001/6, 000002/6, 000003/8, 000004/10]", "auto numbers run 1,2,3,4 across sizes");
        eq(ev.lastState, "done", "batch finishes as done");
        eq(ev.lastNext, "000005", "next number is 000005 afterwards");
        eq(st.pendingNumber, 0, "nothing pending after success");
        eq(link.sent.size(), 4, "4 labels were written");
    }

    static void writeFailureIsUncertain() {
        MemoryStore st = new MemoryStore(); BarcodeSequence seq = new BarcodeSequence(st);
        FakeLink link = new FakeLink(); link.failWriteOnCall = 3; Recorder ev = new Recorder();
        PrintCoordinator pc = coordinator(link, okRaster, seq, ev);
        pc.start(jobs(LabelJob.BarcodeMode.AUTO, "6", "6", "8", "10"));
        eq(ev.lastState, "uncertain", "write failure ends as uncertain");
        eq(ev.lastSent, 2, "2 labels were confirmed before the failure");
        eq(st.next, 3, "counter did NOT skip past the unconfirmed number");
        eq(st.pendingNumber, 3, "number 3 is saved as pending");
        check(ev.lastPending != null && ev.lastPending.contains("000003") && ev.lastPending.contains("\"size\":\"8\""),
            "pending record names label 000003, size 8");
        check(!pcSetNext(pc, 50), "next-number change is refused while a label is unresolved");
        check(pc.start(jobs(LabelJob.BarcodeMode.AUTO, "6")) && ev.lastState.equals("stopped"),
            "a new auto batch will not start until the pending label is resolved");
        eq(link.sent.size(), 2, "no extra label was sent");
    }

    static boolean pcSetNext(PrintCoordinator pc, int n) { return pc.setNext(n); }

    static void recoveryActions() {
        // 1) "it printed" -> consume
        MemoryStore st = new MemoryStore(); BarcodeSequence seq = new BarcodeSequence(st);
        FakeLink link = new FakeLink(); link.failWriteOnCall = 1; Recorder ev = new Recorder();
        PrintCoordinator pc = coordinator(link, okRaster, seq, ev);
        pc.start(jobs(LabelJob.BarcodeMode.AUTO, "6"));
        check(pc.markPendingPrinted(), "operator marks the label as printed");
        eq(st.next, 2, "number consumed -> next is 2");
        eq(st.pendingNumber, 0, "pending cleared");
        eq(ev.lastPending, null, "UI told nothing is pending");

        // 2) "did not print, discard" -> number handed out again
        st = new MemoryStore(); seq = new BarcodeSequence(st);
        link = new FakeLink(); link.failWriteOnCall = 1; ev = new Recorder();
        pc = coordinator(link, okRaster, seq, ev);
        pc.start(jobs(LabelJob.BarcodeMode.AUTO, "6"));
        check(pc.discardPending(), "operator discards the label");
        eq(st.next, 1, "number 1 is handed out again");
        link.connected = true; link.probeOk = true; link.failWriteOnCall = -1;
        ev = new Recorder(); pc = coordinator(link, okRaster, seq, ev);
        pc.start(jobs(LabelJob.BarcodeMode.AUTO, "8"));
        eq(ev.labels.toString(), "[000001/8]", "next auto label reuses 000001 (no gap, no duplicate)");

        // 3) "reprint it" -> fixed barcode, consumes pending on success
        st = new MemoryStore(); seq = new BarcodeSequence(st);
        link = new FakeLink(); link.failWriteOnCall = 2; ev = new Recorder();
        pc = coordinator(link, okRaster, seq, ev);
        pc.start(jobs(LabelJob.BarcodeMode.AUTO, "6", "8"));
        eq(st.pendingNumber, 2, "label 2 pending after failure");
        link.connected = true; link.probeOk = true; link.failWriteOnCall = -1;
        LabelJob re = jobs(LabelJob.BarcodeMode.AUTO, "8").get(0);
        re.fixed = true; re.barcode = "000002"; re.resolvesPending = 2;
        List<LabelJob> one = new ArrayList<LabelJob>(); one.add(re);
        ev = new Recorder(); pc = coordinator(link, okRaster, seq, ev);
        pc.start(one);
        eq(ev.labels.toString(), "[000002/8]", "reprint uses the same number 000002");
        eq(st.next, 3, "after reprint the counter moves on to 000003");
        eq(st.pendingNumber, 0, "pending cleared by successful reprint");
    }

    static void deadLinkBeforeLabel() {
        MemoryStore st = new MemoryStore(); BarcodeSequence seq = new BarcodeSequence(st);
        FakeLink link = new FakeLink(); link.connected = false; link.reconnectOk = false; Recorder ev = new Recorder();
        PrintCoordinator pc = coordinator(link, okRaster, seq, ev);
        pc.start(jobs(LabelJob.BarcodeMode.AUTO, "6", "8"));
        eq(ev.lastState, "stopped", "unreachable printer stops the batch");
        eq(seq.peek(), "000001", "counter untouched");
        eq(st.pendingNumber, 0, "nothing reserved, so nothing to recover");
        eq(link.writes, 0, "nothing was written");
        check(ev.lastMessage.contains("Reconnect"), "message tells the operator to reconnect");
    }

    static void reconnectBeforeLabel() {
        MemoryStore st = new MemoryStore(); BarcodeSequence seq = new BarcodeSequence(st);
        FakeLink link = new FakeLink(); link.connected = false; link.reconnectOk = true; Recorder ev = new Recorder();
        PrintCoordinator pc = coordinator(link, okRaster, seq, ev);
        pc.start(jobs(LabelJob.BarcodeMode.AUTO, "6"));
        eq(link.reconnects, 1, "a dropped link is reconnected automatically");
        eq(ev.labels.toString(), "[000001/6]", "label prints after the automatic reconnect");
    }

    static void drawingFailureReleasesNumber() {
        MemoryStore st = new MemoryStore(); BarcodeSequence seq = new BarcodeSequence(st);
        FakeLink link = new FakeLink(); Recorder ev = new Recorder();
        PrintCoordinator.Rasterizer bad = new PrintCoordinator.Rasterizer() {
            public byte[] render(LabelJob j, boolean l) throws Exception { throw new IllegalArgumentException("bad barcode text"); }
        };
        PrintCoordinator pc = coordinator(link, bad, seq, ev);
        pc.start(jobs(LabelJob.BarcodeMode.AUTO, "6"));
        eq(ev.lastState, "stopped", "drawing failure stops the batch");
        eq(st.pendingNumber, 0, "reserved number was released");
        eq(st.next, 1, "counter unchanged");
        check(ev.lastMessage.contains("bad barcode text"), "reason is shown");
        eq(link.writes, 0, "nothing was sent");
    }

    static void cancelStopsRemaining() {
        MemoryStore st = new MemoryStore(); BarcodeSequence seq = new BarcodeSequence(st);
        FakeLink link = new FakeLink(); final Recorder ev = new Recorder();
        final PrintCoordinator[] holder = new PrintCoordinator[1];
        PrintCoordinator.Rasterizer cancelling = new PrintCoordinator.Rasterizer() {
            int n = 0;
            public byte[] render(LabelJob j, boolean l) { if (++n == 2) holder[0].cancel(); return new byte[]{1}; }
        };
        holder[0] = coordinator(link, cancelling, seq, ev);
        holder[0].start(jobs(LabelJob.BarcodeMode.AUTO, "6", "6", "6", "6"));
        eq(ev.lastState, "cancelled", "cancel ends the batch as cancelled");
        eq(ev.lastSent, 2, "the label being sent when cancel was pressed still completes");
        eq(st.next, 3, "counter reflects only labels that were sent");
    }

    static void limitReached() {
        MemoryStore st = new MemoryStore(); st.next = 999999;
        BarcodeSequence seq = new BarcodeSequence(st);
        FakeLink link = new FakeLink(); Recorder ev = new Recorder();
        PrintCoordinator pc = coordinator(link, okRaster, seq, ev);
        pc.start(jobs(LabelJob.BarcodeMode.AUTO, "6", "8"));
        eq(ev.labels.toString(), "[999999/6]", "the last number 999999 prints");
        eq(ev.lastState, "limit", "then the batch stops at the limit");
        eq(seq.peek(), "LIMIT", "counter shows LIMIT");
        check(seq.setNext(1), "operator can restart the sequence");
    }

    static void manualAndNoBarcode() {
        MemoryStore st = new MemoryStore(); BarcodeSequence seq = new BarcodeSequence(st);
        FakeLink link = new FakeLink(); Recorder ev = new Recorder();
        PrintCoordinator pc = coordinator(link, okRaster, seq, ev);
        List<LabelJob> list = jobs(LabelJob.BarcodeMode.MANUAL, "6", "8");
        for (LabelJob j : list) j.barcode = "ABC123";
        pc.start(list);
        eq(ev.labels.toString(), "[ABC123/6, ABC123/8]", "manual barcode prints as typed");
        eq(st.next, 0, "counter never touched in manual mode");
        ev = new Recorder(); pc = coordinator(link, okRaster, seq, ev);
        pc.start(jobs(LabelJob.BarcodeMode.NONE, "6"));
        eq(ev.labels.toString(), "[/6]", "no-barcode label carries an empty barcode");
    }

    static void reprintDoesNotTouchCounter() {
        MemoryStore st = new MemoryStore(); st.next = 50; BarcodeSequence seq = new BarcodeSequence(st);
        FakeLink link = new FakeLink(); Recorder ev = new Recorder();
        PrintCoordinator pc = coordinator(link, okRaster, seq, ev);
        LabelJob j = jobs(LabelJob.BarcodeMode.AUTO, "10").get(0); j.fixed = true; j.barcode = "000007";
        List<LabelJob> one = new ArrayList<LabelJob>(); one.add(j);
        pc.start(one);
        eq(ev.labels.toString(), "[000007/10]", "history reprint prints the old number");
        eq(st.next, 50, "counter stays at 50");
    }

    static void crashRecoveryFromDisk() {
        MemoryStore st = new MemoryStore(); BarcodeSequence seq = new BarcodeSequence(st);
        try { seq.reserve(new BarcodeSequence.MetaBuilder() { public String build(int n) { return "{\"barcode\":\"" + BarcodeSequence.format(n) + "\"}"; } }); }
        catch (Exception e) { check(false, "reserve threw " + e); }
        BarcodeSequence after = new BarcodeSequence(st);   // app restarted
        check(after.hasPending(), "pending reservation survives an app restart");
        eq(after.pendingNumber(), 1, "pending number is 1 after restart");
        check(after.pendingMeta().contains("000001"), "pending description survives a restart");
        after.release();
        eq(new BarcodeSequence(st).hasPending(), false, "release is durable");
    }

    static void rasterBytes() {
        // 16 x 2 image: left 8 pixels black on row 0, everything else white
        int[] lum = new int[32]; java.util.Arrays.fill(lum, 255);
        for (int x = 0; x < 8; x++) lum[x] = 0;
        byte[] b = RasterCodec.encode(16, 2, lum, 160, 0, 0);
        eq(b.length, 2 + 8 + 4, "no feed: init(2) + header(8) + 4 data bytes");
        eq(b[0] & 0xFF, 0x1B, "starts with ESC"); eq(b[1] & 0xFF, 0x40, "ESC @");
        eq(b[2] & 0xFF, 0x1D, "GS"); eq(b[3] & 0xFF, 0x76, "v"); eq(b[4] & 0xFF, 0x30, "0");
        eq(b[6] & 0xFF, 2, "2 bytes per row (xL)"); eq(b[8] & 0xFF, 2, "height 2 (yL)");
        eq(b[10] & 0xFF, 0xFF, "row 0, first byte all black"); eq(b[11] & 0xFF, 0x00, "row 0, second byte white");
        byte[] shifted = RasterCodec.encode(16, 2, lum, 160, 4, 0);
        eq(shifted[10] & 0xFF, 0x0F, "shift +4 moves the black run right (second half of byte 0)");
        eq(shifted[11] & 0xFF, 0xF0, "...and into the next byte");
        byte[] fed = RasterCodec.encode(16, 2, lum, 160, 0, 12);
        eq(fed.length, b.length + 3, "feed adds ESC J n");
        eq(fed[fed.length - 1] & 0xFF, 12, "feed distance is 12 dots");
        byte[] light = RasterCodec.encode(16, 2, lum, 0, 0, 0);
        eq(light[10] & 0xFF, 0x00, "darkness 0 prints nothing");
        byte[] wide = RasterCodec.encode(384, 240, new int[384 * 240], 160, 0, 0);
        eq(wide.length, 2 + 8 + 48 * 240, "50x30 mm label = 384 x 240 dots = 48 bytes per row");
        eq(wide[6] & 0xFF, 48, "48 bytes per row");
        eq(wide[8] & 0xFF, 240, "240 rows");
    }

    static void svg() {
        CodeSvg.Bits bars = new CodeSvg.Bits() {
            boolean[] row = {true, true, false, true, false, false, true};
            public int width() { return row.length; } public int height() { return 1; }
            public boolean get(int x, int y) { return row[x]; }
        };
        String s = CodeSvg.barcode(bars);
        check(s.contains("viewBox=\"0 0 7 1\"") && s.contains("M0 0h2v1h-2z") && s.contains("M3 0h1v1h-1z") && s.contains("M6 0h1v1h-1z"),
            "barcode SVG merges runs into bars");
        CodeSvg.Bits qr = new CodeSvg.Bits() {
            public int width() { return 3; } public int height() { return 2; }
            public boolean get(int x, int y) { return (x + y) % 2 == 0; }
        };
        String q = CodeSvg.matrix(qr);
        check(q.contains("viewBox=\"0 0 3 2\"") && q.contains("M0 0h1v1h-1z") && q.contains("M1 1h1v1h-1z"), "QR SVG draws modules by row");
    }
}
