package com.prasad.labelstudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends Activity {

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket bluetoothSocket;
    private TextView statusText;
    private Button connectButton;
    private Button printButton;

    private final UUID printerUUID =
        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(30, 50, 30, 30);

        TextView heading = new TextView(this);
        heading.setText("PRASAD LABEL STUDIO");
        heading.setTextSize(24);
        heading.setPadding(0, 0, 0, 30);
        layout.addView(heading);

        statusText = new TextView(this);
        statusText.setText("Printer not connected");
        statusText.setTextSize(17);
        statusText.setPadding(0, 0, 0, 25);
        layout.addView(statusText);

        connectButton = new Button(this);
        connectButton.setText("CONNECT P58D PRINTER");
        layout.addView(connectButton);

        printButton = new Button(this);
        printButton.setText("TEST PRINT");
        printButton.setEnabled(false);
        layout.addView(printButton);

        setContentView(layout);

        connectButton.setOnClickListener(v -> checkPermissionAndConnect());

        printButton.setOnClickListener(v -> printTestLabel());
    }

    private void checkPermissionAndConnect() {
        if (bluetoothAdapter == null) {
            statusText.setText("Bluetooth is not supported");
            return;
        }

        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {

            requestPermissions(
                new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 100
            );
            return;
        }

        showPairedPrinters();
    }

    @Override
    public void onRequestPermissionsResult(
        int requestCode, String[] permissions, int[] grantResults) {

        super.onRequestPermissionsResult(
            requestCode, permissions, grantResults
        );

        if (requestCode == 100 &&
            grantResults.length > 0 &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            showPairedPrinters();
        } else {
            statusText.setText("Bluetooth permission denied");
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
                names.add(device.getName() + " - " + device.getAddress());
            }

            if (printerList.isEmpty()) {
                statusText.setText("No paired Bluetooth devices found");
                return;
            }

            new AlertDialog.Builder(this)
                .setTitle("Select your printer")
                .setItems(
                    names.toArray(new String[0]),
                    (dialog, which) -> connectToPrinter(
                        printerList.get(which)
                    )
                )
                .show();

        } catch (SecurityException e) {
            statusText.setText("Bluetooth permission required");
        }
    }

    private void connectToPrinter(BluetoothDevice device) {
        statusText.setText("Connecting to " + device.getName() + "...");

        new Thread(() -> {
            try {
                if (bluetoothSocket != null) {
                    bluetoothSocket.close();
                }

                bluetoothSocket =
                    device.createRfcommSocketToServiceRecord(printerUUID);

                bluetoothSocket.connect();

                runOnUiThread(() -> {
                    statusText.setText("Printer connected: " + device.getName());
                    printButton.setEnabled(true);
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    statusText.setText("Connection failed: " + e.getMessage());
                    printButton.setEnabled(false);
                });
            }
        }).start();
    }

    private void printTestLabel() {
        new Thread(() -> {
            try {
                if (bluetoothSocket == null ||
                    !bluetoothSocket.isConnected()) {
                    throw new Exception("Printer is not connected");
                }

                OutputStream output =
                    bluetoothSocket.getOutputStream();

                output.write(new byte[]{0x1B, 0x40});

                String label =
                    "       PRASAD APPARELS\n" +
                    "------------------------------\n" +
                    "STYLE: TEST-001\n" +
                    "MODEL: KURTA PYJAMA\n" +
                    "COLOUR: WHITE\n" +
                    "SIZE: 8\n\n\n";

                output.write(label.getBytes("UTF-8"));
                output.write(new byte[]{0x1D, 0x56, 0x00});
                output.flush();

                runOnUiThread(() ->
                    Toast.makeText(
                        this,
                        "Test print sent",
                        Toast.LENGTH_SHORT
                    ).show()
                );

            } catch (Exception e) {
                runOnUiThread(() ->
                    Toast.makeText(
                        this,
                        "Print failed: " + e.getMessage(),
                        Toast.LENGTH_LONG
                    ).show()
                );
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        try {
            if (bluetoothSocket != null) {
                bluetoothSocket.close();
            }
        } catch (Exception ignored) {
        }
    }
}
