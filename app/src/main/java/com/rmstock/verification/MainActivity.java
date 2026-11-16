package com.rmstock.verification;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Map;

/**
 * Hosts the RM Physical Stock Verification web app (assets/index.html) inside a WebView and adds
 * the native things a web page cannot do reliably on every phone:
 *  - Camera permission (asked at first start and again whenever scanning is started)
 *  - Native QR / barcode scanner (ZXing, no Google Play Services needed)
 *  - Saving the Excel / backup file into the Downloads folder
 *  - Sharing the Excel file (WhatsApp, e-mail, Drive ...)
 *  - File picker (master upload / restore), native alert + confirm dialogs, Back button
 */
public class MainActivity extends AppCompatActivity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/assets/index.html";

    private WebView web;
    private ValueCallback<Uri[]> filePathCallback;
    private Runnable onCameraGranted;
    private Runnable onCameraDenied;

    // ---------------------------------------------------------------- activity result launchers
    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), this::onPermissionResult);

    private final ActivityResultLauncher<ScanOptions> scanLauncher =
            registerForActivityResult(new ScanContract(), result -> {
                final String code = result.getContents();
                if (code != null) {
                    js("window.onNativeScan && window.onNativeScan(" + JSONObject.quote(code) + ")");
                } else {
                    js("window.onNativeScanCancel && window.onNativeScanCancel()");
                }
            });

    private final ActivityResultLauncher<Intent> fileLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), res -> {
                if (filePathCallback == null) return;
                Uri[] uris = WebChromeClient.FileChooserParams.parseResult(res.getResultCode(), res.getData());
                filePathCallback.onReceiveValue(uris);
                filePathCallback = null;
            });

    // ---------------------------------------------------------------- lifecycle
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        setContentView(web);

        // Serves assets/index.html from a secure https origin, so camera, localStorage etc. behave
        // exactly like a normal https website, with no internet needed.
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // localStorage = where counts are saved
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setTextZoom(100);                    // ignore phone font-size setting so the layout stays intact

        web.addJavascriptInterface(new Bridge(), "AndroidApp");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) { }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            // Web camera fallback (getUserMedia) -> only allowed after the Android CAMERA permission is granted
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean wantsVideo = false;
                    for (String r : request.getResources()) {
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) wantsVideo = true;
                    }
                    if (!wantsVideo) { request.deny(); return; }
                    withCamera(
                            () -> request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE}),
                            request::deny);
                });
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = cb;
                try {
                    fileLauncher.launch(params.createIntent());
                } catch (ActivityNotFoundException e) {
                    filePathCallback = null;
                    toast("No file manager found on this phone");
                    return false;
                }
                return true;
            }

            @Override
            public boolean onJsAlert(WebView v, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message).setCancelable(false)
                        .setPositiveButton(android.R.string.ok, (d, w) -> result.confirm())
                        .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView v, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message).setCancelable(false)
                        .setPositiveButton(android.R.string.ok, (d, w) -> result.confirm())
                        .setNegativeButton(android.R.string.cancel, (d, w) -> result.cancel())
                        .show();
                return true;
            }
        });

        // Hardware / gesture Back: let the page close a sheet or go Home first; only then offer to exit
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                web.evaluateJavascript(
                        "(function(){return window.onAndroidBack?window.onAndroidBack():false})()",
                        value -> {
                            if ("true".equals(value)) return;
                            new AlertDialog.Builder(MainActivity.this)
                                    .setMessage("Exit RM Stock? Your counts stay saved on this phone.")
                                    .setPositiveButton("Exit", (d, w) -> finish())
                                    .setNegativeButton("Stay", null)
                                    .show();
                        });
            }
        });

        web.loadUrl(START_URL);

        // Ask for the permissions right at first start (camera + storage on Android 9 and older)
        requestStartupPermissions();
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.removeJavascriptInterface("AndroidApp");
            web.destroy();
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- permissions
    private boolean hasCamera() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean needsLegacyStorage() {
        return Build.VERSION.SDK_INT <= Build.VERSION_CODES.P;
    }

    private boolean hasLegacyStorage() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStartupPermissions() {
        if (needsLegacyStorage() && !hasLegacyStorage()) {
            if (hasCamera()) {
                permissionLauncher.launch(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE});
            } else {
                permissionLauncher.launch(new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE});
            }
        } else if (!hasCamera()) {
            permissionLauncher.launch(new String[]{Manifest.permission.CAMERA});
        }
    }

    /** Runs onGranted when the camera permission is available, asking the user first if needed. */
    private void withCamera(Runnable granted, Runnable denied) {
        if (hasCamera()) {
            granted.run();
            return;
        }
        onCameraGranted = granted;
        onCameraDenied = denied;
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            new AlertDialog.Builder(this)
                    .setTitle("Camera permission")
                    .setMessage("RM Stock needs the camera only to scan the QR code on each bag / label.")
                    .setCancelable(false)
                    .setPositiveButton("Allow", (d, w) -> permissionLauncher.launch(new String[]{Manifest.permission.CAMERA}))
                    .setNegativeButton("Not now", (d, w) -> {
                        Runnable r = onCameraDenied;
                        onCameraGranted = null;
                        onCameraDenied = null;
                        if (r != null) r.run();
                    })
                    .show();
        } else {
            permissionLauncher.launch(new String[]{Manifest.permission.CAMERA});
        }
    }

    private void onPermissionResult(Map<String, Boolean> result) {
        if (!result.containsKey(Manifest.permission.CAMERA)) return; // storage-only result
        boolean ok = Boolean.TRUE.equals(result.get(Manifest.permission.CAMERA));
        Runnable g = onCameraGranted;
        Runnable d = onCameraDenied;
        onCameraGranted = null;
        onCameraDenied = null;
        if (ok) {
            if (g != null) g.run();
            return;
        }
        if (d != null) d.run();
        if (!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            // "Don't ask again" was chosen -> only the phone's Settings screen can enable it now
            new AlertDialog.Builder(this)
                    .setTitle("Camera is blocked")
                    .setMessage("Scanning needs the camera. Open Settings > Permissions > Camera and choose Allow.")
                    .setPositiveButton("Open Settings", (dlg, w) -> openAppSettings())
                    .setNegativeButton("Cancel", null)
                    .show();
        } else {
            toast("Camera permission denied - scanning will not work");
        }
    }

    private void openAppSettings() {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    // ---------------------------------------------------------------- native scanner
    private void launchScanner() {
        ScanOptions o = new ScanOptions();
        o.setDesiredBarcodeFormats(Arrays.asList(ScanOptions.QR_CODE, ScanOptions.CODE_128, ScanOptions.CODE_39, ScanOptions.DATA_MATRIX));
        o.setPrompt("Point at the QR code  |  Volume up = torch  |  Back = stop");
        o.setBeepEnabled(false);          // the web page plays its own OK / error beep
        o.setOrientationLocked(false);
        o.setBarcodeImageEnabled(false);
        scanLauncher.launch(o);
    }

    // ---------------------------------------------------------------- helpers
    private void js(final String script) {
        // small delay: the result callback can arrive just before the WebView is resumed
        web.postDelayed(() -> web.evaluateJavascript(script, null), 150);
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_LONG).show();
    }

    private static String safeName(String name) {
        String n = name == null ? "file" : name.replaceAll("[^A-Za-z0-9._-]", "_");
        return n.isEmpty() ? "file" : n;
    }

    // ---------------------------------------------------------------- JS bridge (window.AndroidApp)
    private class Bridge {

        /** Opens the full-screen scanner. Result comes back through window.onNativeScan(code). */
        @JavascriptInterface
        public void scanQR() {
            runOnUiThread(() -> withCamera(
                    MainActivity.this::launchScanner,
                    () -> js("window.onNativeScanError && window.onNativeScanError("
                            + JSONObject.quote("Camera permission is needed to scan. Tap 'Camera / app permissions' on the Home screen.") + ")")));
        }

        @JavascriptInterface
        public void openSettings() {
            runOnUiThread(MainActivity.this::openAppSettings);
        }

        /** Saves a file (sent as base64) into the phone's Downloads folder. Returns "OK: ..." or "ERR: ...". */
        @JavascriptInterface
        public String saveFile(String name, String mime, String base64) {
            try {
                byte[] data = Base64.decode(base64, Base64.DEFAULT);
                String fn = safeName(name);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, fn);
                    v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) return "ERR: could not create file in Downloads";
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                        if (os == null) return "ERR: could not open file";
                        os.write(data);
                    }
                    return "OK: Saved in Downloads / " + fn;
                } else {
                    File dir = hasLegacyStorage()
                            ? Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                            : getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (dir == null) return "ERR: storage not available";
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                    File f = new File(dir, fn);
                    try (FileOutputStream os = new FileOutputStream(f)) {
                        os.write(data);
                    }
                    return "OK: Saved: " + f.getAbsolutePath();
                }
            } catch (Exception e) {
                return "ERR: " + e.getMessage();
            }
        }

        /** Opens the Android share sheet with the file attached. */
        @JavascriptInterface
        public void shareFile(String name, final String mime, String base64) {
            try {
                byte[] data = Base64.decode(base64, Base64.DEFAULT);
                File dir = new File(getCacheDir(), "exports");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                File f = new File(dir, safeName(name));
                try (FileOutputStream os = new FileOutputStream(f)) {
                    os.write(data);
                }
                final Uri uri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fileprovider", f);
                runOnUiThread(() -> {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType(mime == null || mime.isEmpty() ? "*/*" : mime);
                    send.putExtra(Intent.EXTRA_STREAM, uri);
                    send.putExtra(Intent.EXTRA_SUBJECT, "RM Stock Count");
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(send, "Share Excel"));
                });
            } catch (Exception e) {
                runOnUiThread(() -> toast("Share failed: " + e.getMessage()));
            }
        }
    }
}
