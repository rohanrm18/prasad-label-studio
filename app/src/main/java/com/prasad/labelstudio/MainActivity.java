package com.prasad.labelstudio;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hosts the label studio web app (bundled in assets/www, so the app and its screens are always
 * the same version) and connects it to the P58D printer through the "AndroidPrinter" bridge.
 */
public class MainActivity extends Activity {

    private static final int REQ_BLUETOOTH = 11;
    private static final int REQ_FILE = 21;

    private static final int MAX_PER_SIZE = 200;
    private static final int MAX_BATCH = 500;
    private static final Set<String> SIZES = new HashSet<String>(Arrays.asList(
        "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "12", "14"));

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;

    private BluetoothAdapter adapter;
    private PrinterConnection printer;
    private PrintCoordinator coordinator;
    private BarcodeSequence sequence;

    private final ExecutorService printExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService linkExecutor = Executors.newSingleThreadExecutor();

    private volatile String printerState = "disconnected";
    private volatile String printerMessage = "";
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        prefs = getSharedPreferences("prasad_printer", Context.MODE_PRIVATE);

        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();

        printer = new PrinterConnection(adapter, new PrinterConnection.Listener() {
            @Override
            public void onState(String state, String name, String address, String message) {
                printerState = state;
                printerMessage = message == null ? "" : message;
                if ("connected".equals(state)) {
                    prefs.edit().putString("address", address).putString("name", name).apply();
                }
                emit("printer", printerJson());
            }
        });
        printer.remember(prefs.getString("address", ""), prefs.getString("name", ""));

        sequence = new BarcodeSequence(new PrefsStore(getSharedPreferences("prasad_barcode", Context.MODE_PRIVATE)));

        coordinator = new PrintCoordinator(printer, new LabelRenderer(), sequence,
            new PrintCoordinator.Events() {
                @Override
                public void progress(String state, int sent, int total, String message) {
                    emit("progress", "{\"state\":" + JsonText.quote(state) + ",\"sent\":" + sent
                        + ",\"total\":" + total + ",\"message\":" + JsonText.quote(message) + "}");
                }

                @Override
                public void label(LabelJob job) {
                    emit("label", PrintCoordinator.metaJson(job, job.barcode));
                }

                @Override
                public void barcode(String next, String pendingMeta) {
                    emit("barcode", barcodeJson(next, pendingMeta));
                }
            },
            new Executor() {
                @Override
                public void execute(Runnable r) {
                    printExecutor.execute(r);
                }
            },
            250);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setBuiltInZoomControls(false);
        s.setSupportZoom(false);

        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "AndroidPrinter");
        web.loadUrl("file:///android_asset/www/index.html");

        if (hasBluetoothPermission() && printer.hasTarget()) connectInBackground(printer.address());
    }

    // Permissions ---------------------------------------------------------------------

    private boolean hasBluetoothPermission() {
        if (Build.VERSION.SDK_INT < 31) return true;
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void askBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BLUETOOTH);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code != REQ_BLUETOOTH) return;
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        emit("permission", "{\"granted\":" + granted + "}");
        if (granted && printer.hasTarget()) connectInBackground(printer.address());
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != REQ_FILE || fileCallback == null) return;
        Uri[] uris = null;
        if (result == RESULT_OK && data != null) {
            uris = WebChromeClient.FileChooserParams.parseResult(result, data);
        }
        fileCallback.onReceiveValue(uris);
        fileCallback = null;
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript(
            "(function(){return !!(window.onAndroidBack && window.onAndroidBack());})()",
            new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String handled) {
                    if (!"true".equals(handled)) finish();
                }
            });
    }

    @Override
    protected void onDestroy() {
        printExecutor.shutdown();
        linkExecutor.shutdown();
        try {
            printer.disconnect();
        } catch (RuntimeException ignored) {
            // closing anyway
        }
        super.onDestroy();
    }

    // Events to the web page ------------------------------------------------------------

    private void emit(final String type, final String json) {
        final String js = "window.onAndroidEvent&&window.onAndroidEvent(" + JsonText.quote(type) + ","
            + JsonText.quote(json) + ")";
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (web != null) web.evaluateJavascript(js, null);
            }
        });
    }

    private String printerJson() {
        return "{\"state\":" + JsonText.quote(printerState)
            + ",\"name\":" + JsonText.quote(printer.name())
            + ",\"address\":" + JsonText.quote(printer.address())
            + ",\"message\":" + JsonText.quote(printerMessage) + "}";
    }

    private String barcodeJson(String next, String pendingMeta) {
        return "{\"next\":" + JsonText.quote(next)
            + ",\"pending\":" + (pendingMeta == null || pendingMeta.length() == 0 ? "null" : pendingMeta) + "}";
    }

    private void connectInBackground(final String address) {
        if (coordinator.isPrinting() || printer.isConnecting()) return;
        linkExecutor.execute(new Runnable() {
            @Override
            public void run() {
                printer.connect(address);
            }
        });
    }

    // The bridge ------------------------------------------------------------------------

    private final class Bridge {

        @JavascriptInterface
        public String getState() {
            return "{\"printer\":" + printerJson()
                + ",\"barcode\":" + barcodeJson(sequence.peek(), sequence.hasPending() ? sequence.pendingMeta() : null)
                + ",\"permission\":" + hasBluetoothPermission()
                + ",\"bluetooth\":" + (adapter != null)
                + ",\"printing\":" + coordinator.isPrinting() + "}";
        }

        @JavascriptInterface
        public String listPrinters() {
            if (!hasBluetoothPermission()) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        askBluetoothPermission();
                    }
                });
                return "[]";
            }
            List<String[]> all;
            try {
                all = printer.listPaired();
            } catch (SecurityException e) {
                return "[]";
            }
            StringBuilder out = new StringBuilder("[");
            // likely printers first, then the rest
            boolean first = true;
            for (int pass = 0; pass < 2; pass++) {
                for (String[] d : all) {
                    boolean likely = "1".equals(d[2]);
                    if ((pass == 0) != likely) continue;
                    if (!first) out.append(',');
                    first = false;
                    out.append("{\"name\":").append(JsonText.quote(d[0]))
                        .append(",\"address\":").append(JsonText.quote(d[1]))
                        .append(",\"likely\":").append(likely).append('}');
                }
            }
            return out.append(']').toString();
        }

        @JavascriptInterface
        public void connect(final String address) {
            if (!hasBluetoothPermission()) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        askBluetoothPermission();
                    }
                });
                return;
            }
            connectInBackground(address);
        }

        @JavascriptInterface
        public void disconnect() {
            if (coordinator.isPrinting()) return;
            printer.disconnect();
        }

        @JavascriptInterface
        public void cancel() {
            coordinator.cancel();
        }

        /** Returns "ok" or a message that explains why nothing was started. */
        @JavascriptInterface
        public String print(String json) {
            try {
                List<LabelJob> jobs = buildJobs(new JSONObject(json));
                if (!coordinator.start(jobs)) return "A print is already running.";
                return "ok";
            } catch (IllegalArgumentException e) {
                return e.getMessage();
            } catch (Exception e) {
                return "Could not read the print request.";
            }
        }

        @JavascriptInterface
        public boolean setNext(int number) {
            return coordinator.setNext(number);
        }

        /** action: "used" (it did print) or "discard" (it did not). */
        @JavascriptInterface
        public boolean resolvePending(String action) {
            if ("used".equals(action)) return coordinator.markPendingPrinted();
            if ("discard".equals(action)) return coordinator.discardPending();
            return false;
        }

        @JavascriptInterface
        public String codeSvg(String kind, String text) {
            return LabelRenderer.codeSvg(kind, text);
        }

        @JavascriptInterface
        public boolean saveFile(String name, String mime, String content) {
            return saveToDownloads(name, mime, content);
        }
    }

    private List<LabelJob> buildJobs(JSONObject req) {
        String mode = req.optString("mode", "single");
        JSONObject product = req.optJSONObject("product");
        JSONObject design = req.optJSONObject("design");
        if (design == null || design.optJSONArray("objects") == null) {
            throw new IllegalArgumentException("There is no label design to print.");
        }

        LabelJob.BarcodeMode bm = LabelJob.BarcodeMode.NONE;
        String barcodeMode = req.optString("barcodeMode", "none");
        if ("auto".equals(barcodeMode)) bm = LabelJob.BarcodeMode.AUTO;
        else if ("manual".equals(barcodeMode)) bm = LabelJob.BarcodeMode.MANUAL;

        String fixed = req.optString("fixedBarcode", "").trim();
        boolean reprint = "reprint".equals(mode);
        String manual = req.optString("manualBarcode", "").trim();
        if (bm == LabelJob.BarcodeMode.MANUAL && manual.length() == 0 && !reprint) {
            throw new IllegalArgumentException("Type the barcode text first.");
        }
        if (manual.length() > 40 || fixed.length() > 40) {
            throw new IllegalArgumentException("The barcode text is too long (40 characters at most).");
        }
        if (reprint && fixed.length() == 0) {
            throw new IllegalArgumentException("There is no barcode number to reprint.");
        }

        JSONObject context = new JSONObject();
        try {
            context.put("design", design);
            JSONObject options = req.optJSONObject("options");
            context.put("options", options == null ? new JSONObject() : options);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not read the print settings.");
        }

        int pending = 0;
        if (reprint && req.optBoolean("recoverPending", false) && sequence.hasPending()) {
            pending = sequence.pendingNumber();
        }

        JSONArray items = req.optJSONArray("items");
        if (items == null || items.length() == 0) throw new IllegalArgumentException("Choose a size and quantity.");

        List<LabelJob> jobs = new ArrayList<LabelJob>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String size = item.optString("size", "");
            int qty = item.optInt("quantity", 0);
            if (qty <= 0) continue;
            if (!SIZES.contains(size)) throw new IllegalArgumentException("Size " + size + " is not allowed.");
            if (qty > MAX_PER_SIZE) throw new IllegalArgumentException("At most " + MAX_PER_SIZE + " labels per size.");
            for (int k = 0; k < qty; k++) {
                LabelJob job = new LabelJob();
                if (product != null) {
                    job.style = product.optString("style", "");
                    job.model = product.optString("model", "");
                    job.category = product.optString("category", "");
                    job.colour = product.optString("colour", "");
                }
                job.size = size;
                job.mode = bm;
                job.context = context;
                if (reprint) {
                    job.fixed = true;
                    job.barcode = fixed;
                    // only the first label of a recovery reprint settles the pending number
                    if (pending > 0 && jobs.isEmpty()) job.resolvesPending = pending;
                } else if (bm == LabelJob.BarcodeMode.MANUAL) {
                    job.barcode = manual;
                }
                jobs.add(job);
            }
        }
        if (jobs.isEmpty()) throw new IllegalArgumentException("Choose a size and quantity.");
        if (jobs.size() > MAX_BATCH) throw new IllegalArgumentException("At most " + MAX_BATCH + " labels in one print.");
        return jobs;
    }

    // Saving files --------------------------------------------------------------------------

    private boolean saveToDownloads(String name, String mime, String content) {
        try {
            String safe = name == null ? "labels.txt" : name.replaceAll("[^A-Za-z0-9._-]", "_");
            byte[] bytes = content.getBytes(Charset.forName("UTF-8"));
            String type = mime == null || mime.length() == 0 ? "text/plain" : mime;

            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, safe);
                v.put(MediaStore.Downloads.MIME_TYPE, type);
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) return false;
                OutputStream out = getContentResolver().openOutputStream(uri);
                if (out == null) return false;
                try {
                    out.write(bytes);
                } finally {
                    out.close();
                }
            } else {
                File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null) return false;
                dir.mkdirs();
                FileOutputStream out = new FileOutputStream(new File(dir, safe));
                try {
                    out.write(bytes);
                } finally {
                    out.close();
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // Barcode counter storage ---------------------------------------------------------------

    private static final class PrefsStore implements BarcodeSequence.Store {
        private final SharedPreferences p;

        PrefsStore(SharedPreferences p) {
            this.p = p;
        }

        @Override
        public int loadNext() {
            return p.getInt("next", 0);
        }

        @Override
        public int loadPendingNumber() {
            return p.getInt("pending", 0);
        }

        @Override
        public String loadPendingMeta() {
            return p.getString("pendingMeta", null);
        }

        @Override
        public void save(int next, int pendingNumber, String pendingMeta) {
            // commit() (not apply()) so the number is on disk before the label is sent
            p.edit().putInt("next", next).putInt("pending", pendingNumber)
                .putString("pendingMeta", pendingMeta).commit();
        }
    }
}
