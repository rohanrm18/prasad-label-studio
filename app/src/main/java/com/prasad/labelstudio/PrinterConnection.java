package com.prasad.labelstudio;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Bluetooth serial (SPP) link to the P58D.
 *  - connecting gives up after 12 seconds instead of hanging
 *  - a second, "insecure" socket is tried if the first one fails
 *  - the last printer is remembered so it can be reconnected automatically
 *  - a write failure closes the socket, so the next label notices the dead link
 */
final class PrinterConnection implements PrintCoordinator.Link {

    interface Listener {
        /** state: connecting | connected | disconnected | error */
        void onState(String state, String name, String address, String message);
    }

    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final long CONNECT_TIMEOUT_MS = 12000;

    private static final String[] NAME_HINTS = {
        "P58", "PRINT", "POS", "MPT", "RPP", "SRS", "THERMAL", "LABEL", "TSC", "XP-", "GP-"
    };

    private final BluetoothAdapter adapter;
    private final Listener listener;
    private final Object connectLock = new Object();
    private final Object writeLock = new Object();

    private volatile BluetoothSocket socket;
    private volatile String address = "";
    private volatile String name = "";
    private volatile boolean connecting = false;

    PrinterConnection(BluetoothAdapter adapter, Listener listener) {
        this.adapter = adapter;
        this.listener = listener;
    }

    void remember(String savedAddress, String savedName) {
        address = savedAddress == null ? "" : savedAddress;
        name = savedName == null ? "" : savedName;
    }

    String address() {
        return address;
    }

    String name() {
        return name;
    }

    boolean hasTarget() {
        return address.length() > 0;
    }

    boolean isConnecting() {
        return connecting;
    }

    // PrintCoordinator.Link ----------------------------------------------------------

    @Override
    public boolean isConnected() {
        BluetoothSocket s = socket;
        return s != null && s.isConnected();
    }

    @Override
    public boolean probe() {
        try {
            write(RasterCodec.initCommand());
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public boolean reconnect() {
        return hasTarget() && connect(address);
    }

    @Override
    public void write(byte[] data) throws IOException {
        synchronized (writeLock) {
            BluetoothSocket s = socket;
            if (s == null) throw new IOException("Printer not connected");
            try {
                OutputStream out = s.getOutputStream();
                out.write(data);
                out.flush();
            } catch (IOException e) {
                dropSocket(s);
                listener.onState("disconnected", name, address, "The connection to the printer was lost.");
                throw e;
            }
        }
    }

    // Connecting ---------------------------------------------------------------------

    /** Blocks for at most about 25 seconds (two attempts of 12 seconds). */
    boolean connect(String targetAddress) {
        synchronized (connectLock) {
            connecting = true;
            try {
                return doConnect(targetAddress);
            } finally {
                connecting = false;
            }
        }
    }

    private boolean doConnect(String targetAddress) {
        if (adapter == null) {
            listener.onState("error", name, targetAddress, "This phone has no Bluetooth.");
            return false;
        }

        BluetoothDevice device;
        try {
            device = adapter.getRemoteDevice(targetAddress);
        } catch (IllegalArgumentException e) {
            listener.onState("error", name, targetAddress, "That is not a valid Bluetooth address.");
            return false;
        }

        String deviceName;
        try {
            String n = device.getName();
            deviceName = n == null || n.length() == 0 ? targetAddress : n;
        } catch (SecurityException e) {
            listener.onState("error", name, targetAddress, "Bluetooth permission is needed.");
            return false;
        }

        address = targetAddress;
        name = deviceName;
        listener.onState("connecting", deviceName, targetAddress, "");
        dropSocket(socket);

        try {
            adapter.cancelDiscovery();
        } catch (SecurityException ignored) {
            // not fatal
        }

        String reason = "";
        for (int attempt = 0; attempt < 2; attempt++) {
            BluetoothSocket s = null;
            try {
                s = attempt == 0
                    ? device.createRfcommSocketToServiceRecord(SPP)
                    : device.createInsecureRfcommSocketToServiceRecord(SPP);
                openWithTimeout(s);
                socket = s;
                listener.onState("connected", deviceName, targetAddress, "");
                return true;
            } catch (SecurityException e) {
                closeQuietly(s);
                listener.onState("error", deviceName, targetAddress, "Bluetooth permission is needed.");
                return false;
            } catch (IOException e) {
                closeQuietly(s);
                reason = e.getMessage() == null ? "" : e.getMessage();
            }
        }

        listener.onState("error", deviceName, targetAddress,
            "Could not connect. Switch the printer on, keep it close to the phone and try again."
                + (reason.length() > 0 ? " (" + reason + ")" : ""));
        return false;
    }

    private void openWithTimeout(final BluetoothSocket s) throws IOException {
        final Exception[] error = new Exception[1];
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    s.connect();
                } catch (Exception e) {
                    error[0] = e;
                }
            }
        }, "printer-connect");
        worker.setDaemon(true);
        worker.start();

        try {
            worker.join(CONNECT_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closeQuietly(s);
            throw new IOException("Interrupted");
        }

        if (worker.isAlive()) {
            closeQuietly(s);   // makes the blocked connect() fail
            throw new IOException("the printer did not answer within 12 seconds");
        }
        if (error[0] instanceof SecurityException) throw (SecurityException) error[0];
        if (error[0] instanceof IOException) throw (IOException) error[0];
        if (error[0] != null) throw new IOException(error[0].getMessage());
    }

    void disconnect() {
        dropSocket(socket);
        listener.onState("disconnected", name, address, "");
    }

    private void dropSocket(BluetoothSocket s) {
        if (s == null) return;
        if (s == socket) socket = null;
        closeQuietly(s);
    }

    private static void closeQuietly(BluetoothSocket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (Exception ignored) {
            // nothing to do
        }
    }

    // Paired devices -----------------------------------------------------------------

    /** Each entry: {name, address, "1" if it looks like a printer else "0"}. */
    List<String[]> listPaired() {
        List<String[]> out = new ArrayList<String[]>();
        if (adapter == null) return out;
        Set<BluetoothDevice> devices = adapter.getBondedDevices();
        if (devices == null) return out;
        for (BluetoothDevice d : devices) {
            String n = d.getName();
            if (n == null || n.length() == 0) n = d.getAddress();
            out.add(new String[]{n, d.getAddress(), looksLikePrinter(d, n) ? "1" : "0"});
        }
        return out;
    }

    private static boolean looksLikePrinter(BluetoothDevice d, String deviceName) {
        try {
            BluetoothClass c = d.getBluetoothClass();
            if (c != null && c.getMajorDeviceClass() == BluetoothClass.Device.Major.IMAGING) return true;
        } catch (SecurityException ignored) {
            // fall back to the name
        }
        String upper = deviceName.toUpperCase(Locale.US);
        for (String hint : NAME_HINTS) {
            if (upper.contains(hint)) return true;
        }
        return false;
    }
}
