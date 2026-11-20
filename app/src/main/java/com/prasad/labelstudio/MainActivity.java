package com.prasad.labelstudio;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends Activity {

    private static final String WEBSITE =
        "https://rohanrm18.github.io/prasad-label-studio/";

    private static final UUID PRINTER_UUID =
        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket bluetoothSocket;

    private TextView statusText;
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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

        root.addView(top);

        webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);

        webView.setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(
                WebView view, WebResourceRequest request) {

                return handleUrl(request.getUrl().toString());
            }

            @Override
            public boolean shouldOverrideUrlLoading(
                WebView view, String url) {

                return handleUrl(url);
            }
        });

        root.addView(webView, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0, 1
        ));

        setContentView(root);

        connectButton.setOnClickListener(v ->
            checkBluetoothPermission()
        );

        webView.loadUrl(WEBSITE);
    }

    private boolean handleUrl(String url) {

        if (url == null || !url.startsWith("bprint://")) {
            return false;
        }

        String responseUrl = url.substring("bprint://".length());

        try {
            Uri data = Uri.parse(responseUrl);

            String style = data.getQueryParameter("style");
            String model = data.getQueryParameter("model");
            String colour = data.getQueryParameter("colour");
            String size = data.getQueryParameter("size");
            String barcode = data.getQueryParameter("barcode");

            printLabel(
                style, model, colour, size, barcode
            );

        } catch (Exception e) {
            showMessage("Invalid print request");
        }

        return true;
    }

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
        int requestCode,
        String[] permissions,
        int[] results) {

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

    private void connectPrinter(BluetoothDevice device) {

        statusText.setText("Connecting to printer...");

        new Thread(() -> {

            try {
                if (bluetoothSocket != null) {
                    bluetoothSocket.close();
                }

                bluetoothSocket =
                    device.createRfcommSocketToServiceRecord(
                        PRINTER_UUID
                    );

                bluetoothSocket.connect();

                runOnUiThread(() -> {
                    statusText.setText(
                        "Connected: " + device.getName()
                    );
                    showMessage("P58D connected successfully");
                });

            } catch (Exception e) {

                runOnUiThread(() -> {
                    statusText.setText("Printer connection failed");
                    showMessage(e.getMessage());
                });
            }

        }).start();
    }

    private void printLabel(
        String style,
        String model,
        String colour,
        String size,
        String barcode) {

        if (bluetoothSocket == null ||
            !bluetoothSocket.isConnected()) {

            showMessage("Connect P58D printer first");
            return;
        }

        new Thread(() -> {

            try {
                OutputStream out =
                    bluetoothSocket.getOutputStream();

                out.write(new byte[]{0x1B, 0x40});

                // Center brand name
                out.write(new byte[]{0x1B, 0x61, 0x01});
                out.write(new byte[]{0x1B, 0x45, 0x01});
                out.write("PRASAD APPARELS\n".getBytes(
                    StandardCharsets.UTF_8
                ));
                out.write(new byte[]{0x1B, 0x45, 0x00});

                // Left aligned product details
                out.write(new byte[]{0x1B, 0x61, 0x00});

                out.write(("STYLE: " + safe(style) + "\n")
                    .getBytes(StandardCharsets.UTF_8));

                out.write(("MODEL: " + safe(model) + "\n")
                    .getBytes(StandardCharsets.UTF_8));

                out.write(("COLOUR: " + safe(colour) + "\n")
                    .getBytes(StandardCharsets.UTF_8));

                // Highlight size
                out.write(new byte[]{0x1B, 0x45, 0x01});
                out.write(("SIZE: " + safe(size) + "\n")
                    .getBytes(StandardCharsets.UTF_8));
                out.write(new byte[]{0x1B, 0x45, 0x00});

                // Code 128 barcode
                if (barcode != null && !barcode.isEmpty()) {

                    out.write(new byte[]{0x1D, 0x48, 0x02});
                    out.write(new byte[]{0x1D, 0x68, 55});
                    out.write(new byte[]{0x1D, 0x77, 2});

                    byte[] code =
                        ("{B" + barcode).getBytes(
                            StandardCharsets.US_ASCII
                        );

                    out.write(new byte[]{
                        0x1D, 0x6B, 0x49,
                        (byte) code.length
                    });

                    out.write(code);
                    out.write('\n');
                }

                out.write('\n');
                out.write('\n');
                out.write(new byte[]{0x1D, 0x56, 0x00});
                out.flush();

                runOnUiThread(() ->
                    showMessage("Print sent to P58D")
                );

            } catch (Exception e) {

                runOnUiThread(() ->
                    showMessage("Printing failed: " + e.getMessage())
                );
            }

        }).start();
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

    @Override
    public void onDestroy() {
        super.onDestroy();

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
