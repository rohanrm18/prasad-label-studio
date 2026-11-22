package com.prasad.labelstudio;

import android.Manifest;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.SharedPreferences;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileOutputStream;
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
import android.util.Base64;
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
        String category;
        String colour;
        String size;
        String barcode;
        boolean autoBarcode;
        JSONObject design;

        LabelJob(String style, String model, String category, String colour,
                 String size, String barcode, boolean autoBarcode, JSONObject design) {

            this.style = style;
            this.model = model;
            this.category = category;
            this.colour = colour;
            this.size = size;
            this.barcode = barcode;
            this.autoBarcode = autoBarcode;
            this.design = design == null ? new JSONObject() : design;
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

    private void notifyWebLabelPrinted(LabelJob job) {
        try {
            JSONObject record = new JSONObject();
            record.put("barcode", job.barcode == null ? "" : job.barcode);
            record.put("style", job.style == null ? "" : job.style);
            record.put("model", job.model == null ? "" : job.model);
            record.put("category", job.category == null ? "" : job.category);
            record.put("colour", job.colour == null ? "" : job.colour);
            record.put("size", job.size == null ? "" : job.size);
            record.put("printedAt", String.valueOf(System.currentTimeMillis()));
            String argument = JSONObject.quote(record.toString());
            runOnUiThread(() -> {
                if (webView != null) webView.evaluateJavascript(
                    "if(window.onAndroidLabelPrinted){window.onAndroidLabelPrinted(" + argument + ");}", null);
            });
        } catch (Exception ignored) { }
    }

    // ANDROID JAVASCRIPT BRIDGE

    private class PrinterBridge {

        @JavascriptInterface
        public void saveBarcodeHistory(String json) {
            try {
                String filename = "prasad-barcode-history.json";
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                    values.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                    values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (uri == null) throw new Exception("Could not create download file");
                    try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
                        if (out == null) throw new Exception("Could not open download file");
                        out.write(json.getBytes(StandardCharsets.UTF_8));
                    }
                } else {
                    File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (dir == null) throw new Exception("Downloads folder unavailable");
                    File file = new File(dir, filename);
                    try (FileOutputStream out = new FileOutputStream(file)) {
                        out.write(json.getBytes(StandardCharsets.UTF_8));
                    }
                }
                runOnUiThread(() -> showMessage("Barcode history saved to Downloads"));
            } catch (Exception e) {
                runOnUiThread(() -> showMessage("Could not save barcode history"));
            }
        }

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
                    data.optString("category"),
                    data.optString("colour"),
                    data.optString("size"),
                    data.optString("barcode"),
                    data.optBoolean("autoBarcode", false),
                    data.optJSONObject("design")
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
                String category = data.optString("category");
                String colour = data.optString("colour");
                String barcode = data.optString("barcode");

                boolean autoBarcode =
                    data.optBoolean("autoBarcode", false);
                JSONObject design = data.optJSONObject("design");

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
                            style, model, category, colour, size,
                            barcode, autoBarcode, design
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
                    notifyWebLabelPrinted(job);

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

    // PRINT ONE LABEL AS A SINGLE 50 x 30 MM RASTER IMAGE
    private void printOneLabel(LabelJob job) throws Exception {
        BluetoothSocket socket = bluetoothSocket;
        if (socket == null || !socket.isConnected()) throw new Exception("Printer disconnected");
        final int width = 384, height = 240;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bitmap); c.drawColor(Color.WHITE);
        JSONObject d = job.design == null ? new JSONObject() : job.design;
        String t=d.optString("template","classic"), font=d.optString("font","sans");
        String align=d.optString("align","left"), layout=d.optString("layout","standard");
        int bs=Math.max(12,Math.min(30,d.optInt("brandSize",18)));
        int ts=Math.max(10,Math.min(22,d.optInt("textSize",13)));
        int ss=Math.max(20,Math.min(48,d.optInt("sizeSize",34)));
        int bh=Math.max(25,Math.min(80,d.optInt("barcodeHeight",48)));
        int pad=Math.max(5,Math.min(30,d.optInt("padding",6)*2));
        int ox=Math.max(-40,Math.min(40,d.optInt("offsetX",0)*2));
        JSONObject positions=d.optJSONObject("positions");
        JSONObject scales=d.optJSONObject("scales");
        JSONObject groupOffset=d.optJSONObject("groupOffset");
        int groupX=groupOffset==null?0:(int)Math.max(-128,Math.min(128,groupOffset.optDouble("x",0)*12.8));
        int groupY=groupOffset==null?0:(int)Math.max(-128,Math.min(128,groupOffset.optDouble("y",0)*12.8));
        float brandScale=scaleValue(scales,"brand"), detailsScale=scaleValue(scales,"details"), sizeScale=scaleValue(scales,"size"), barcodeScale=scaleValue(scales,"barcode");
        JSONObject brandPos=positions==null?null:positions.optJSONObject("brand");
        JSONObject detailsPos=positions==null?null:positions.optJSONObject("details");
        JSONObject sizePos=positions==null?null:positions.optJSONObject("size");
        JSONObject barcodePos=positions==null?null:positions.optJSONObject("barcode");
        int brandX=(brandPos==null?0:(int)Math.max(-128,Math.min(128,brandPos.optDouble("x",0)*12.8)))+groupX;
        int brandY=(brandPos==null?0:(int)Math.max(-128,Math.min(128,brandPos.optDouble("y",0)*12.8)))+groupY;
        int detailsX=(detailsPos==null?0:(int)Math.max(-128,Math.min(128,detailsPos.optDouble("x",0)*12.8)))+groupX;
        int detailsYOffset=(detailsPos==null?0:(int)Math.max(-128,Math.min(128,detailsPos.optDouble("y",0)*12.8)))+groupY;
        int sizeX=(sizePos==null?0:(int)Math.max(-128,Math.min(128,sizePos.optDouble("x",0)*12.8)))+groupX;
        int sizeY=(sizePos==null?0:(int)Math.max(-128,Math.min(128,sizePos.optDouble("y",0)*12.8)))+groupY;
        int barcodeX=(barcodePos==null?0:(int)Math.max(-128,Math.min(128,barcodePos.optDouble("x",0)*12.8)))+groupX;
        int barcodeY=(barcodePos==null?0:(int)Math.max(-128,Math.min(128,barcodePos.optDouble("y",0)*12.8)))+groupY;
        boolean brand=d.optBoolean("showBrand",true), sty=d.optBoolean("showStyle",true);
        boolean model=d.optBoolean("showModel",true), col=d.optBoolean("showColour",true);
        boolean size=d.optBoolean("showSize",true);
        boolean bar=d.optBoolean("showBarcode",true)&&job.barcode!=null&&!job.barcode.trim().isEmpty();
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(Color.BLACK);
        Typeface family="serif".equals(font)?Typeface.SERIF:Typeface.SANS_SERIF;
        boolean bold="bold".equals(font);

        // Distinct physical designs for all eight templates.
        if("frame".equals(t)){
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(4);c.drawRect(4,4,width-5,height-5,p);
            p.setStrokeWidth(1.5f);c.drawRect(11,11,width-12,height-12,p);p.setStyle(Paint.Style.FILL);
        }else if("editorial".equals(t)){
            p.setStrokeWidth(9);c.drawLine(7,5,7,height-5,p);
        }else if("retail".equals(t)){
            c.drawRect(0,0,width,49,p);
        }else if("luxury".equals(t)){
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(3);c.drawRoundRect(5,5,width-6,height-6,9,9,p);p.setStyle(Paint.Style.FILL);
        }else if("barcodefocus".equals(t)){
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2.5f);c.drawRect(4,4,width-5,height-5,p);p.setStyle(Paint.Style.FILL);
        }else if("sizefocus".equals(t)){
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2.5f);c.drawRect(4,4,width-5,height-5,p);p.setStyle(Paint.Style.FILL);
        }else{
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth("wholesale".equals(t)?1.5f:2);
            c.drawRect(4,4,width-5,height-5,p);p.setStyle(Paint.Style.FILL);
        }
        String border=d.optString("border","none");
        if("double".equals(border)){
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.5f);c.drawRect(8,8,width-9,height-9,p);p.setStyle(Paint.Style.FILL);
        }else if("solid".equals(border)){
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.5f);c.drawRect(6,6,width-7,height-7,p);p.setStyle(Paint.Style.FILL);
        }

        String name=d.optString("brand","PRASAD APPARELS");
        boolean brandLeft="wholesale".equals(t)||"editorial".equals(t)||"barcodefocus".equals(t);
        float y="retail".equals(t)?34:27;
        if(brand&&!name.trim().isEmpty()){
            p.setColor("retail".equals(t)?Color.WHITE:Color.BLACK);
            p.setTypeface(Typeface.create(family,Typeface.BOLD));
            p.setTextSize(("editorial".equals(t)?bs+2:("barcodefocus".equals(t)?bs-1:bs))*brandScale);
            p.setTextAlign(brandLeft?Paint.Align.LEFT:Paint.Align.CENTER);
            c.drawText(name,(brandLeft?pad+("editorial".equals(t)?9:0):width/2f)+brandX,y+brandY,p);
            p.setColor(Color.BLACK);y+=bs+("retail".equals(t)?19:12);
            if(!"retail".equals(t)){p.setStrokeWidth("luxury".equals(t)?2:1);c.drawLine(pad,y-5,width-pad,y-5,p);}
        }else y=15;

        p.setTypeface(Typeface.create(family,bold?Typeface.BOLD:Typeface.NORMAL));
        p.setTextSize(ts*detailsScale);
        Paint.Align a="center".equals(align)?Paint.Align.CENTER:("right".equals(align)?Paint.Align.RIGHT:Paint.Align.LEFT);
        p.setTextAlign(a);
        float x=a==Paint.Align.CENTER?width/2f+ox:(a==Paint.Align.RIGHT?width-pad+ox:pad+ox);
        float gap=(ts+7)*detailsScale, detailsBaseY=y+detailsYOffset, detailStep=0;
        x+=detailsX;
        JSONArray notepadLines=d.optJSONArray("notepadLines");
        boolean hasNotepadLines=notepadLines!=null&&notepadLines.length()>0;
        if(!hasNotepadLines){
            if(sty){c.drawText("STYLE: "+safe(job.style),x,detailsBaseY+detailStep,p);detailStep+=gap;}
            if(model&&!safe(job.model).isEmpty()){c.drawText("MODEL: "+safe(job.model),x,detailsBaseY+detailStep,p);detailStep+=gap;}
            if(!safe(job.category).isEmpty()){c.drawText("CATEGORY: "+safe(job.category),x,detailsBaseY+detailStep,p);detailStep+=gap;}
            if(col){c.drawText("COLOUR: "+safe(job.colour),x,detailsBaseY+detailStep,p);detailStep+=gap;}
        }else{
            p.setTextAlign(Paint.Align.LEFT);
            for(int i=0;i<notepadLines.length();i++){
                JSONObject line=notepadLines.optJSONObject(i);if(line==null)continue;
                String text=line.optString("text","");if(text.isEmpty())continue;
                float lineX=(float)Math.max(0,Math.min(width-4,line.optDouble("x",2)*7.68));
                float lineSize=(float)Math.max(8,Math.min(28,line.optDouble("size",14)));
                float lineY=(float)Math.max(lineSize*2.34,Math.min(height-2,line.optDouble("y",10)*8+lineSize*2.34));
                p.setTypeface(Typeface.create(family,line.optBoolean("bold",false)?Typeface.BOLD:(bold?Typeface.BOLD:Typeface.NORMAL)));
                p.setTextSize(lineSize*2.0f);
                c.drawText(text,lineX,lineY,p);
            }
            p.setTypeface(Typeface.create(family,bold?Typeface.BOLD:Typeface.NORMAL));
            p.setTextSize(ts*detailsScale);
            p.setTextAlign(a);
        }
        float detailsY=detailsBaseY+detailStep;

        if(size){
            boolean boxed="editorial".equals(t)||"sizefocus".equals(t)||"frame".equals(t);
            p.setTypeface(Typeface.create(family,Typeface.BOLD));
            if("stacked".equals(layout)){
                p.setTextAlign(Paint.Align.CENTER);
                float sy=Math.min(bar?150:210,detailsY+ss+5)+sizeY;
                if(boxed){
                    float bw="sizefocus".equals(t)?126:105,bh2=58;
                    float left=Math.max(8,Math.min(width-bw-8,width/2f-bw/2f))+sizeX;
                    float top=Math.max(y+4,sy-bh2+8);
                    if("frame".equals(t)){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);c.drawRect(left,top,left+bw,top+bh2,p);p.setStyle(Paint.Style.FILL);}
                    else{c.drawRect(left,top,left+bw,top+bh2,p);p.setColor(Color.WHITE);}
                    p.setTextSize(10);c.drawText("SIZE",left+bw/2,top+14,p);
                    p.setTextSize(ss*sizeScale);c.drawText(safe(job.size),left+bw/2,top+bh2-7,p);p.setColor(Color.BLACK);
                }else{p.setTextSize(Math.max(10,ss-4));c.drawText("SIZE  "+safe(job.size),width/2f+ox+sizeX,sy,p);}
            }else if(boxed){
                float bw="sizefocus".equals(t)?126:105,bh2="sizefocus".equals(t)?72:62;
                float left=Math.max(8,Math.min(width-bw-8,width-pad-bw))+sizeX;
                float top=Math.max(y+3,Math.min(130,detailsY-2))+sizeY;
                if("frame".equals(t)){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);c.drawRect(left,top,left+bw,top+bh2,p);p.setStyle(Paint.Style.FILL);}
                else{c.drawRect(left,top,left+bw,top+bh2,p);p.setColor(Color.WHITE);}
                p.setTextAlign(Paint.Align.CENTER);p.setTextSize(10);c.drawText("SIZE",left+bw/2,top+16,p);
                p.setTextSize(ss*sizeScale);c.drawText(safe(job.size),left+bw/2,top+bh2-8,p);p.setColor(Color.BLACK);
            }else{
                p.setTextSize(ss);p.setTextAlign(Paint.Align.RIGHT);
                c.drawText(safe(job.size),width-pad+ox+sizeX,Math.min(164,Math.max(145,detailsY+ss/2f))+sizeY,p);
            }
        }

        // Height slider is honoured; barcode and number stay inside the same 50 x 30 mm image.
        if(bar){
            int bw="barcodefocus".equals(t)?350:("wholesale".equals(t)?285:320);
            int barcodeH=Math.min(bh,76);
            int bx=Math.max(4,Math.min(width-bw-4,(width-bw)/2))+barcodeX;
            int by=Math.max(100,height-15-barcodeH)+barcodeY;
            BitMatrix matrix=new MultiFormatWriter().encode(job.barcode.trim(),BarcodeFormat.CODE_128,bw,barcodeH);
            for(int xx=0;xx<matrix.getWidth();xx++)for(int yy=0;yy<matrix.getHeight();yy++)
                if(matrix.get(xx,yy))c.drawRect(bx+xx,by+yy,bx+xx+1,by+yy+1,p);
            p.setColor(Color.BLACK);p.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
            p.setTextSize(13);p.setTextAlign(Paint.Align.CENTER);
            c.drawText(job.barcode.trim(),width/2f,height-2,p);
        }


        // Shreyans-style editor artwork: render the exact saved editor objects to the
        // same 384x240 raster sent to the P58D. Coordinates are stored in 0.1 mm units.
        if (d.optBoolean("editorMode", false)) {
            c.drawColor(Color.WHITE);
            JSONArray editorObjects = d.optJSONArray("editorObjects");
            JSONObject editorPage = d.optJSONObject("editorPage");
            float pageW = editorPage == null ? 50f : (float)Math.max(10, editorPage.optDouble("w",50));
            float pageH = editorPage == null ? 30f : (float)Math.max(10, editorPage.optDouble("h",30));
            float sx = width / (pageW * 10f), sy = height / (pageH * 10f);
            if (editorObjects != null) {
                for (int oi=0; oi<editorObjects.length(); oi++) {
                    JSONObject obj=editorObjects.optJSONObject(oi); if(obj==null) continue;
                    String type=obj.optString("type","text");
                    float ex=(float)obj.optDouble("x",0)*sx, ey=(float)obj.optDouble("y",0)*sy;
                    float w=(float)obj.optDouble("w",100)*sx, h=(float)obj.optDouble("h",40)*sy;
                    float rotation=(float)obj.optDouble("rotation",0);
                    c.save(); c.rotate(rotation,ex+w/2f,ey+h/2f);
                    Paint ep=new Paint(Paint.ANTI_ALIAS_FLAG); ep.setColor(Color.BLACK);
                    ep.setStrokeWidth(Math.max(1.2f,(float)obj.optDouble("strokeWidth",2)*sx));
                    if ("text".equals(type) || "icon".equals(type)) {
                        ep.setTypeface(Typeface.create("sans-serif",obj.optBoolean("bold",false)?Typeface.BOLD:Typeface.NORMAL));
                        ep.setTextSize(Math.max(8f,(float)obj.optDouble("size",20)*sx));
                        String al=obj.optString("align","left");
                        ep.setTextAlign("center".equals(al)?Paint.Align.CENTER:("right".equals(al)?Paint.Align.RIGHT:Paint.Align.LEFT));
                        float tx="center".equals(al)?x+w/2f:("right".equals(al)?x+w:x);
                        c.drawText(obj.optString("text",""),tx,ey+Math.min(h,ep.getTextSize()+2),ep);
                    } else if ("rect".equals(type)) {
                        ep.setStyle(Paint.Style.STROKE); c.drawRect(ex,ey,ex+w,ey+h,ep);
                    } else if ("line".equals(type)) {
                        c.drawLine(ex,ey,ex+w,ey+h,ep);
                    } else if ("barcode".equals(type)) {
                        String value=job.barcode==null||job.barcode.trim().isEmpty()?obj.optString("text","123456"):job.barcode;
                        if(d.optBoolean("showBarcode",true) && !value.trim().isEmpty()){
                            BitMatrix bm=new MultiFormatWriter().encode(value,BarcodeFormat.CODE_128,Math.max(30,(int)w),Math.max(20,(int)(h*.72f)));
                            Paint bp=new Paint();bp.setColor(Color.BLACK);
                            float cellW=w/bm.getWidth(),cellH=(h*.72f)/bm.getHeight();
                            for(int bx=0;bx<bm.getWidth();bx++)for(int by=0;by<bm.getHeight();by++)if(bm.get(bx,by))
                                c.drawRect(ex+bx*cellW,ey+by*cellH,ex+(bx+1)*cellW,ey+(by+1)*cellH,bp);
                            bp.setTextSize(Math.max(8,10*sx));bp.setTextAlign(Paint.Align.CENTER);
                            c.drawText(value,ex+w/2f,ey+h-1,bp);
                        }
                    } else if ("qr".equals(type)) {
                        String value=obj.optString("text","PRASAD APPARELS");
                        BitMatrix qm=new MultiFormatWriter().encode(value,BarcodeFormat.QR_CODE,Math.max(24,(int)w),Math.max(24,(int)h));
                        Paint qp=new Paint();qp.setColor(Color.BLACK);
                        float cellW=w/qm.getWidth(),cellH=h/qm.getHeight();
                        for(int qx=0;qx<qm.getWidth();qx++)for(int qy=0;qy<qm.getHeight();qy++)if(qm.get(qx,qy))
                            c.drawRect(ex+qx*cellW,ey+qy*cellH,ex+(qx+1)*cellW,ey+(qy+1)*cellH,qp);
                    } else if ("image".equals(type)) {
                        String src=obj.optString("src","");
                        try {
                            int comma=src.indexOf(",");
                            if(comma>=0 && src.substring(0,comma).contains("base64")){
                                byte[] raw=Base64.decode(src.substring(comma+1),Base64.DEFAULT);
                                Bitmap image=BitmapFactory.decodeByteArray(raw,0,raw.length);
                                if(image!=null){c.drawBitmap(image,null,new android.graphics.RectF(ex,ey,ex+w,ey+h),new Paint(Paint.FILTER_BITMAP_FLAG));image.recycle();}
                            }
                        } catch(Exception ignored) {}
                    }
                    c.restore();
                }
            }
        }

        int bpr=(width+7)/8;byte[] raster=new byte[bpr*height];
        for(int yy=0;yy<height;yy++)for(int xx=0;xx<width;xx++)
            if(Color.red(bitmap.getPixel(xx,yy))<160)raster[yy*bpr+(xx/8)]|=(byte)(0x80>>(xx%8));
        bitmap.recycle();
        OutputStream out=socket.getOutputStream();out.write(new byte[]{0x1B,0x40});
        out.write(new byte[]{0x1D,0x76,0x30,0x00,(byte)(bpr&255),(byte)((bpr>>8)&255),(byte)(height&255),(byte)((height>>8)&255)});
        out.write(raster);out.flush();
    }

    private float scaleValue(JSONObject scales, String key) {
        if (scales == null) return 1f;
        return (float)Math.max(0.5, Math.min(1.8, scales.optDouble(key, 1.0)));
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
