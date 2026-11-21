package com.prasad.labelstudio;

import android.Manifest;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String WEBSITE =
        "https://rohanrm18.github.io/prasad-label-studio/";

    private static final UUID PRINTER_UUID =
        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private static final String PREFS = "PrasadLabelSettings";
    private static final String BARCODE_KEY = "next_barcode_number";

    private static final int MAX_BARCODE = 999999;

    private BluetoothAdapter bluetoothAdapter;
    private volatile BluetoothSocket bluetoothSocket;

    private WebView webView;
    private TextView statusText;
    private TextView progressText;
    private Button cancelButton;

    private SharedPreferences preferences;

    private final ExecutorService printExecutor =
        Executors.newSingleThreadExecutor();

    private volatile boolean printing = false;
    private volatile boolean cancelRequested = false;

    private static class LabelJob {
        String style;
        String model;
        String colour;
        String size;
        String barcode;
        boolean autoBarcode;

        LabelJob(String style, String model, String colour,
                 String size, String barcode, boolean autoBarcode) {

            this.style = style;
            this.model = model;
            this.colour = colour;
            this.size = size;
            this.barcode = barcode;
            this.autoBarcode = autoBarcode;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);

        if (!preferences.contains(BARCODE_KEY)) {
            preferences.edit().putInt(BARCODE_KEY, 1).apply();
        }

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(12, 8, 12, 8);

        statusText = new TextView(this);
        statusText.setText("Printer not connected");
        statusText.setTextSize(14);
        top.addView(statusText);

        Button connectButton = new Button(this);
        connectButton.setText("CONNECT P58D PRINTER");
        top.addView(connectButton);

        progressText = new TextView(this);
        progressText.setText("");
        progressText.setTextSize(13);
        top.addView(progressText);

        cancelButton = new Button(this);
        cancelButton.setText("CANCEL PRINTING");
        cancelButton.setVisibility(Button.GONE);
        cancelButton.setBackgroundColor(0xFFFFDDDD);
        top.addView(cancelButton);

        root.addView(top);

        webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);

        webView.addJavascriptInterface(
            new PrinterBridge(), "AndroidPrinter"
        );

        webView.setWebViewClient(new WebViewClient());

        root.addView(webView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0, 1
        ));

        setContentView(root);

        connectButton.setOnClickListener(v ->
            checkBluetoothPermission()
        );

        cancelButton.setOnClickListener(v ->
            cancelPrinting()
        );

        webView.loadUrl(WEBSITE);
    }

    // BARCODE NUMBER MANAGEMENT

    private synchronized int getNextBarcodeNumber() {
        return preferences.getInt(BARCODE_KEY, 1);
    }

    private synchronized String getFormattedNextBarcode() {
        int number = getNextBarcodeNumber();

        if (number > MAX_BARCODE) return "LIMIT";

        return String.format(java.util.Locale.US, "%06d", number);
    }

    private synchronized boolean advanceBarcodeNumber() {
        int current = getNextBarcodeNumber();

        if (current > MAX_BARCODE) return false;

        return preferences.edit()
            .putInt(BARCODE_KEY, current + 1)
            .commit();
    }

    private void notifyWebBarcodeUpdated() {
        String next = getFormattedNextBarcode();

        runOnUiThread(() -> {
            if (webView != null) {
                webView.evaluateJavascript(
                    "if(window.onAndroidBarcodeUpdated){" +
                    "window.onAndroidBarcodeUpdated('" + next + "');}",
                    null
                );
            }
        });
    }

    // ANDROID JAVASCRIPT BRIDGE

    private class PrinterBridge {

        @JavascriptInterface
        public String getNextBarcodeNumber() {
            return getFormattedNextBarcode();
        }

        @JavascriptInterface
        public void printSingle(String json) {
            try {
                JSONObject data = new JSONObject(json);

                LabelJob job = new LabelJob(
                    data.optString("style"),
                    data.optString("model"),
                    data.optString("colour"),
                    data.optString("size"),
                    data.optString("barcode"),
                    data.optBoolean("autoBarcode", false)
                );

                runOnUiThread(() -> {
                    if (printing) {
                        showMessage("A print job is already running");
                        return;
                    }

                    if (!printerReady()) return;

                    ArrayList<LabelJob> jobs = new ArrayList<>();
                    jobs.add(job);

                    startPrintQueue(jobs);
                });

            } catch (Exception e) {
                runOnUiThread(() ->
                    showMessage("Invalid label information")
                );
            }
        }

        @JavascriptInterface
        public void printBulk(String json) {
            try {
                JSONObject data = new JSONObject(json);

                String style = data.optString("style");
                String model = data.optString("model");
                String colour = data.optString("colour");
                String barcode = data.optString("barcode");

                boolean autoBarcode =
                    data.optBoolean("autoBarcode", false);

                JSONArray items = data.getJSONArray("items");

                ArrayList<LabelJob> jobs = new ArrayList<>();
                StringBuilder summary = new StringBuilder();

                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i);

                    String size = item.optString("size");
                    int quantity = item.optInt("quantity", 0);

                    if (quantity <= 0) continue;

                    summary.append("Size ")
                        .append(size)
                        .append(" - ")
                        .append(quantity)
                        .append(" labels\n");

                    for (int j = 0; j < quantity; j++) {
                        jobs.add(new LabelJob(
                            style, model, colour, size,
                            barcode, autoBarcode
                        ));
                    }
                }

                if (jobs.isEmpty()) {
                    runOnUiThread(() ->
                        showMessage("No labels selected")
                    );
                    return;
                }

                runOnUiThread(() -> {
                    if (printing) {
                        showMessage("A print job is already running");
                        return;
                    }

                    if (!printerReady()) return;

                    String mode = autoBarcode
                        ? "Automatic barcode numbering ON"
                        : "Manual barcode mode";

                    showFirstBulkConfirmation(
                        jobs, summary.toString(), mode
                    );
                });

            } catch (Exception e) {
                runOnUiThread(() ->
                    showMessage("Invalid bulk print information")
                );
            }
        }
    }

    // FIRST BULK CONFIRMATION

    private void showFirstBulkConfirmation(
        ArrayList<LabelJob> jobs,
        String summary,
        String mode) {

        new AlertDialog.Builder(this)
            .setTitle("Bulk print summary")
            .setMessage(
                summary + "\nTotal labels: " + jobs.size()
                + "\n\n" + mode
                + "\n\nContinue to the final confirmation?"
            )
            .setNegativeButton("CANCEL", (d, w) ->
                d.dismiss()
            )
            .setPositiveButton("CONTINUE", (d, w) ->
                showSecondBulkConfirmation(jobs)
            )
            .setCancelable(false)
            .show();
    }

    // SECOND BULK CONFIRMATION

    private void showSecondBulkConfirmation(
        ArrayList<LabelJob> jobs) {

        new AlertDialog.Builder(this)
            .setTitle("Final confirmation")
            .setMessage(
                "Are you sure you want to print "
                + jobs.size()
                + " labels on P58D?\n\n"
                + "The printer will start immediately."
            )
            .setNegativeButton("GO BACK", (d, w) ->
                d.dismiss()
            )
            .setPositiveButton("CONFIRM PRINT", (d, w) ->
                startPrintQueue(jobs)
            )
            .setCancelable(false)
            .show();
    }

    // CHECK PRINTER

    private boolean printerReady() {
        try {
            if (bluetoothSocket == null ||
                !bluetoothSocket.isConnected()) {

                showMessage("Connect P58D printer first");
                return false;
            }

            return true;

        } catch (Exception e) {
            showMessage("Printer connection unavailable");
            return false;
        }
    }

    // PRINT QUEUE

    private void startPrintQueue(ArrayList<LabelJob> jobs) {

        if (printing) {
            showMessage("Printing is already in progress");
            return;
        }

        if (!printerReady()) return;

        printing = true;
        cancelRequested = false;

        int total = jobs.size();

        cancelButton.setVisibility(Button.VISIBLE);
        progressText.setText("Preparing " + total + " labels...");
        statusText.setText("Printing on P58D...");

        printExecutor.execute(() -> {

            int completed = 0;
            boolean failed = false;
            boolean barcodeLimitReached = false;

            for (LabelJob job : jobs) {

                if (cancelRequested) break;

                try {
                    if (job.autoBarcode) {
                        int next = getNextBarcodeNumber();

                        if (next > MAX_BARCODE) {
                            barcodeLimitReached = true;
                            break;
                        }

                        job.barcode = String.format(
                            java.util.Locale.US,
                            "%06d",
                            next
                        );
                    }

                    printOneLabel(job);

                    if (job.autoBarcode) {
                        if (!advanceBarcodeNumber()) {
                            failed = true;
                            break;
                        }

                        notifyWebBarcodeUpdated();
                    }

                    completed++;

                    final int done = completed;
                    final int remaining = total - done;

                    runOnUiThread(() ->
                        progressText.setText(
                            "Sent: " + done
                            + " / " + total
                            + "    Remaining: " + remaining
                        )
                    );

                    Thread.sleep(250);

                } catch (Exception e) {
                    failed = true;
                    break;
                }
            }

            final int finalCompleted = completed;
            final int finalRemaining = total - completed;
            final boolean wasCancelled = cancelRequested;
            final boolean hadFailure = failed;
            final boolean limitReached = barcodeLimitReached;

            printing = false;

            runOnUiThread(() -> {

                cancelButton.setVisibility(Button.GONE);

                if (hadFailure) {
                    statusText.setText(
                        "Printing stopped - printer error"
                    );

                    progressText.setText(
                        "Sent: " + finalCompleted
                        + "    Not completed: " + finalRemaining
                    );

                    showMessage(
                        "Printing stopped. Check printer connection."
                    );

                } else if (limitReached) {
                    statusText.setText("Barcode limit reached");

                    progressText.setText(
                        "Sent: " + finalCompleted
                        + "    Remaining: " + finalRemaining
                    );

                    showMessage(
                        "6-digit barcode limit reached. Change the sequence to continue."
                    );

                } else if (wasCancelled) {
                    statusText.setText("Printing cancelled");

                    progressText.setText(
                        "Sent: " + finalCompleted
                        + "    Remaining: " + finalRemaining
                    );

                    showMessage("Remaining labels cancelled");

                } else {
                    statusText.setText("Printing completed");

                    progressText.setText(
                        "Successfully sent " + finalCompleted + " labels"
                    );

                    showMessage("Printing completed");
                }
            });
        });
    }

    // CANCEL PRINTING

    private void cancelPrinting() {
        if (!printing) return;

        cancelRequested = true;

        cancelButton.setEnabled(false);
        cancelButton.setText("CANCELLING...");

        showMessage(
            "Stopping remaining labels. The current label may finish."
        );

        cancelButton.postDelayed(() -> {
            cancelButton.setEnabled(true);
            cancelButton.setText("CANCEL PRINTING");
        }, 500);
    }

    // BLUETOOTH PERMISSION

    private void checkBluetoothPermission() {

        if (bluetoothAdapter == null) {
            showMessage("Bluetooth is not supported");
            return;
        }

        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {

            requestPermissions(
                new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT
                }, 100
            );

            return;
        }

        showPairedPrinters();
    }

    @Override
    public void onRequestPermissionsResult(
        int requestCode, String[] permissions, int[] results) {

        super.onRequestPermissionsResult(
            requestCode, permissions, results
        );

        if (requestCode == 100 &&
            results.length > 0 &&
            results[0] == PackageManager.PERMISSION_GRANTED) {

            showPairedPrinters();

        } else {
            showMessage("Bluetooth permission required");
        }
    }

    // SHOW PAIRED BLUETOOTH PRINTERS

    private void showPairedPrinters() {
        try {
            Set<BluetoothDevice> devices =
                bluetoothAdapter.getBondedDevices();

            ArrayList<BluetoothDevice> printerList =
                new ArrayList<>();

            ArrayList<String> names = new ArrayList<>();

            for (BluetoothDevice device : devices) {
                printerList.add(device);

                names.add(
                    device.getName() + " - " + device.getAddress()
                );
            }

            if (printerList.isEmpty()) {
                showMessage("No paired printers found");
                return;
            }

            new AlertDialog.Builder(this)
                .setTitle("Select Bluetooth printer")
                .setItems(
                    names.toArray(new String[0]),
                    (dialog, which) ->
                        connectPrinter(printerList.get(which))
                )
                .show();

        } catch (SecurityException e) {
            showMessage("Bluetooth permission required");
        }
    }

    // CONNECT P58D

    private void connectPrinter(BluetoothDevice device) {

        if (printing) {
            showMessage("Wait until printing finishes");
            return;
        }

        statusText.setText("Connecting to printer...");

        new Thread(() -> {
            try {
                BluetoothSocket oldSocket = bluetoothSocket;
                bluetoothSocket = null;

                if (oldSocket != null) oldSocket.close();

                BluetoothSocket newSocket =
                    device.createRfcommSocketToServiceRecord(
                        PRINTER_UUID
                    );

                newSocket.connect();
                bluetoothSocket = newSocket;

                runOnUiThread(() -> {
                    statusText.setText(
                        "Connected: " + device.getName()
                    );

                    showMessage("P58D connected successfully");
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    statusText.setText(
                        "Printer connection failed"
                    );

                    showMessage("Could not connect to printer");
                });
            }
        }).start();
    }

    // PRINT ONE LABEL AS A SINGLE 50 x 30 mm RASTER IMAGE
    // P58D printable area: 384 dots wide x 240 dots high at 203 dpi.
    private void printOneLabel(LabelJob job) throws Exception {
        BluetoothSocket socket = bluetoothSocket;
        if (socket == null || !socket.isConnected()) {
            throw new Exception("Printer disconnected");
        }

        final int width = 384;
        final int height = 240;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(22);
        canvas.drawText("PRASAD APPARELS", width / 2f, 25, paint);

        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        paint.setTextSize(17);
        canvas.drawText("STYLE: " + safe(job.style), 10, 55, paint);
        canvas.drawText("MODEL: " + safe(job.model), 10, 78, paint);
        canvas.drawText("COLOUR: " + safe(job.colour), 10, 101, paint);

        paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        paint.setTextSize(31);
        canvas.drawText("SIZE: " + safe(job.size), 10, 139, paint);

        if (job.barcode != null && !job.barcode.trim().isEmpty()) {
            BitMatrix matrix = new MultiFormatWriter().encode(
                job.barcode.trim(), BarcodeFormat.CODE_128, 350, 62
            );
            paint.setColor(Color.BLACK);
            for (int x = 0; x < matrix.getWidth(); x++) {
                for (int y = 0; y < matrix.getHeight(); y++) {
                    if (matrix.get(x, y)) {
                        canvas.drawRect(17 + x, 151 + y, 18 + x, 152 + y, paint);
                    }
                }
            }
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            paint.setTextSize(14);
            canvas.drawText(job.barcode.trim(), width / 2f, 231, paint);
        }

        // Convert each row to ESC/POS GS v 0 monochrome raster bytes.
        int bytesPerRow = (width + 7) / 8;
        byte[] raster = new byte[bytesPerRow * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (Color.red(bitmap.getPixel(x, y)) < 160) {
                    int index = y * bytesPerRow + (x / 8);
                    raster[index] |= (byte) (0x80 >> (x % 8));
                }
            }
        }
        bitmap.recycle();

        OutputStream out = socket.getOutputStream();
        out.write(new byte[]{0x1B, 0x40}); // Initialize printer
        // GS v 0, normal-density raster bit image.
        out.write(new byte[]{
            0x1D, 0x76, 0x30, 0x00,
            (byte) (bytesPerRow & 0xFF),
            (byte) ((bytesPerRow >> 8) & 0xFF),
            (byte) (height & 0xFF),
            (byte) ((height >> 8) & 0xFF)
        });
        out.write(raster);
        out.flush();
        // No per-label cut command: gap-label sensor should advance to next sticker.
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private void showMessage(String message) {
        Toast.makeText(
            this,
            message,
            Toast.LENGTH_LONG
        ).show();
    }

    // CLOSE APP

    @Override
    public void onDestroy() {
        super.onDestroy();

        cancelRequested = true;
        printExecutor.shutdownNow();

        try {
            if (bluetoothSocket != null) {
                bluetoothSocket.close();
            }
        } catch (Exception ignored) {
        }

        if (webView != null) {
            webView.destroy();
        }
    }
}
