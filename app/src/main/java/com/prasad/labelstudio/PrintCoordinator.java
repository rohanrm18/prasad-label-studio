package com.prasad.labelstudio;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Runs a batch of labels one by one and keeps the barcode counter honest.
 *
 * For every label: check the printer link (before any number is reserved), reserve the
 * barcode number, draw the label, send it, and only then consume the number.
 *   - link dead before the label  -> nothing reserved, nothing lost
 *   - drawing fails               -> number released, nothing was sent
 *   - sending fails midway        -> number stays PENDING for the operator to resolve
 *
 * Pure Java (no Android classes) so the whole flow is unit tested on a normal JVM.
 */
final class PrintCoordinator {

    interface Link {
        boolean isConnected();

        /** Sends a harmless printer-init so a dead link is noticed before a number is reserved. */
        boolean probe();

        /** Tries to restore the connection to the last printer. Blocks (with a timeout). */
        boolean reconnect();

        void write(byte[] data) throws IOException;
    }

    interface Rasterizer {
        byte[] render(LabelJob job, boolean lastLabel) throws Exception;
    }

    interface Events {
        /** state: printing | done | cancelled | stopped | uncertain | limit */
        void progress(String state, int sent, int total, String message);

        /** A label was fully sent. */
        void label(LabelJob job);

        /** Counter changed. pendingMeta is null when nothing is waiting to be resolved. */
        void barcode(String next, String pendingMeta);
    }

    private final Link link;
    private final Rasterizer rasterizer;
    private final BarcodeSequence sequence;
    private final Events events;
    private final Executor executor;
    private final long gapMillis;

    private volatile boolean printing = false;
    private volatile boolean cancelRequested = false;

    PrintCoordinator(Link link, Rasterizer rasterizer, BarcodeSequence sequence,
                     Events events, Executor executor, long gapMillis) {
        this.link = link;
        this.rasterizer = rasterizer;
        this.sequence = sequence;
        this.events = events;
        this.executor = executor;
        this.gapMillis = gapMillis;
    }

    boolean isPrinting() {
        return printing;
    }

    /** Returns false when a batch is already running or there is nothing to print. */
    boolean start(List<LabelJob> jobs) {
        if (jobs == null || jobs.isEmpty()) return false;
        synchronized (this) {
            if (printing) return false;
            printing = true;
            cancelRequested = false;
        }
        final List<LabelJob> copy = new ArrayList<>(jobs);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                runBatch(copy);
            }
        });
        return true;
    }

    /** Stops after the label currently being sent. */
    void cancel() {
        if (printing) cancelRequested = true;
    }

    // Operator decisions about an uncertain label -----------------------------------

    /** "It did print": consume the pending number. */
    synchronized boolean markPendingPrinted() {
        if (printing || !sequence.hasPending()) return false;
        sequence.confirmSent();
        notifyBarcode();
        return true;
    }

    /** "It did not print and I do not want it": the number will be handed out again. */
    synchronized boolean discardPending() {
        if (printing || !sequence.hasPending()) return false;
        sequence.release();
        notifyBarcode();
        return true;
    }

    synchronized boolean setNext(int number) {
        if (printing) return false;
        boolean ok = sequence.setNext(number);
        if (ok) notifyBarcode();
        return ok;
    }

    void notifyBarcode() {
        events.barcode(sequence.peek(), sequence.hasPending() ? sequence.pendingMeta() : null);
    }

    // The batch -----------------------------------------------------------------------

    private void runBatch(List<LabelJob> jobs) {
        final int total = jobs.size();
        int sent = 0;
        String state = "done";
        String message = "";

        try {
            events.progress("printing", 0, total, "");

            for (int i = 0; i < total; i++) {
                if (cancelRequested) {
                    state = "cancelled";
                    break;
                }

                final LabelJob job = jobs.get(i);
                final boolean last = i == total - 1;

                // 1. Is the printer there? Checked BEFORE a barcode number is reserved.
                if (!ensureLink()) {
                    state = "stopped";
                    message = "Printer not reachable. Switch it on and press Reconnect.";
                    break;
                }

                // 2. Barcode number.
                boolean reserved = false;
                if (job.mode == LabelJob.BarcodeMode.AUTO && !job.fixed) {
                    try {
                        final LabelJob meta = job;
                        int number = sequence.reserve(new BarcodeSequence.MetaBuilder() {
                            @Override
                            public String build(int n) {
                                return metaJson(meta, BarcodeSequence.format(n));
                            }
                        });
                        job.barcode = BarcodeSequence.format(number);
                        reserved = true;
                    } catch (BarcodeSequence.LimitReachedException e) {
                        state = "limit";
                        message = "All 999999 barcode numbers are used. Set a new next number.";
                        break;
                    } catch (BarcodeSequence.PendingException e) {
                        state = "stopped";
                        message = "An earlier label is still waiting for your decision. Resolve it first.";
                        break;
                    }
                }

                // 3. Draw. Nothing has been sent yet, so a failure here is a clean failure.
                byte[] data;
                try {
                    data = rasterizer.render(job, last);
                } catch (Exception e) {
                    if (reserved) sequence.release();
                    notifyBarcode();
                    state = "stopped";
                    message = "Could not draw the label: " + describe(e);
                    break;
                }

                // 4. Send. A failure here is uncertain: part of the label may have printed.
                try {
                    link.write(data);
                } catch (Exception e) {
                    notifyBarcode();
                    state = "uncertain";
                    message = "Connection lost while sending label " + (sent + 1)
                        + " of " + total + ". Check the last label that came out.";
                    break;
                }

                // 5. Sent: now (and only now) the number is used up.
                if (reserved || (job.resolvesPending > 0
                        && sequence.pendingNumber() == job.resolvesPending)) {
                    sequence.confirmSent();
                }
                sent++;
                events.label(job);
                notifyBarcode();
                events.progress("printing", sent, total, "");

                if (gapMillis > 0 && !last) {
                    try {
                        Thread.sleep(gapMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        state = "stopped";
                        message = "Printing was interrupted.";
                        break;
                    }
                }
            }
        } catch (RuntimeException e) {
            state = "stopped";
            message = "Unexpected error: " + describe(e);
        } finally {
            printing = false;
        }

        events.progress(state, sent, total, message);
    }

    private boolean ensureLink() {
        try {
            if (link.isConnected() && link.probe()) return true;
            return link.reconnect() && link.probe();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String describe(Exception e) {
        String m = e.getMessage();
        return m == null || m.length() == 0 ? e.getClass().getSimpleName() : m;
    }

    static String metaJson(LabelJob job, String barcode) {
        return "{\"barcode\":" + JsonText.quote(barcode)
            + ",\"style\":" + JsonText.quote(job.style)
            + ",\"model\":" + JsonText.quote(job.model)
            + ",\"category\":" + JsonText.quote(job.category)
            + ",\"colour\":" + JsonText.quote(job.colour)
            + ",\"size\":" + JsonText.quote(job.size) + "}";
    }
}
