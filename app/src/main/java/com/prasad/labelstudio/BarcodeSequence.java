package com.prasad.labelstudio;

import java.util.Locale;

/**
 * Persistent six-digit barcode counter (000001 - 999999).
 *
 * A number is first RESERVED (saved as "pending"), then the label is sent, and only
 * after the whole label has been written to the printer is the number CONSUMED.
 * If the app dies or the Bluetooth link drops in between, the pending number is still
 * on disk, so the operator can decide: it printed (consume), reprint it, or discard it
 * (the number is handed out again). Numbers are therefore never silently skipped or reused.
 *
 * Pure Java (no Android classes) so it can be unit tested on a normal JVM.
 */
final class BarcodeSequence {

    /** Where the counter lives. Android: SharedPreferences. Tests: memory. */
    interface Store {
        /** Next number to hand out, or 0 if nothing was ever saved. */
        int loadNext();

        /** Reserved-but-unconfirmed number, or 0 if none. */
        int loadPendingNumber();

        /** Description of the pending label (JSON text), or null. */
        String loadPendingMeta();

        /** Must be durable when it returns (SharedPreferences.commit). */
        void save(int next, int pendingNumber, String pendingMeta);
    }

    /** Builds the description saved with a reservation once its number is known. */
    interface MetaBuilder {
        String build(int number);
    }

    static final class LimitReachedException extends Exception {
        private static final long serialVersionUID = 1L;
    }

    static final class PendingException extends Exception {
        private static final long serialVersionUID = 1L;
    }

    static final int MAX = 999999;

    private final Store store;
    private int next;
    private int pendingNumber;
    private String pendingMeta;

    BarcodeSequence(Store store) {
        this.store = store;
        int saved = store.loadNext();
        this.next = saved < 1 ? 1 : saved;
        this.pendingNumber = store.loadPendingNumber();
        if (pendingNumber > 0) {
            String meta = store.loadPendingMeta();
            this.pendingMeta = meta == null ? "" : meta;
        } else {
            this.pendingNumber = 0;
            this.pendingMeta = null;
        }
    }

    static String format(int number) {
        return String.format(Locale.US, "%06d", number);
    }

    synchronized int next() {
        return next;
    }

    /** "000123", or "LIMIT" when all numbers are used. */
    synchronized String peek() {
        return next > MAX ? "LIMIT" : format(next);
    }

    synchronized boolean hasPending() {
        return pendingNumber > 0;
    }

    synchronized int pendingNumber() {
        return pendingNumber;
    }

    synchronized String pendingMeta() {
        return pendingMeta;
    }

    /** Operator override. Refused while an uncertain label is waiting to be resolved. */
    synchronized boolean setNext(int number) {
        if (number < 1 || number > MAX) return false;
        if (pendingNumber > 0) return false;
        next = number;
        persist();
        return true;
    }

    /** Hands out the next number WITHOUT consuming it. */
    synchronized int reserve(MetaBuilder meta) throws LimitReachedException, PendingException {
        if (pendingNumber > 0) throw new PendingException();
        if (next > MAX) throw new LimitReachedException();
        pendingNumber = next;
        pendingMeta = meta == null ? "" : meta.build(pendingNumber);
        persist();
        return pendingNumber;
    }

    /** The label for the pending number was fully written (or the operator says it printed). */
    synchronized void confirmSent() {
        if (pendingNumber <= 0) return;
        if (pendingNumber >= next) next = pendingNumber + 1;
        pendingNumber = 0;
        pendingMeta = null;
        persist();
    }

    /** Nothing was printed for the pending number: it will be handed out again. */
    synchronized void release() {
        if (pendingNumber <= 0) return;
        pendingNumber = 0;
        pendingMeta = null;
        persist();
    }

    private void persist() {
        store.save(next, pendingNumber, pendingMeta);
    }
}
