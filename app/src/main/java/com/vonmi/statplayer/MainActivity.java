package com.vonmi.statplayer;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.annotation.SuppressLint;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Environment;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.DisplayCutout;
import android.graphics.Rect;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;
import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import com.google.common.util.concurrent.ListenableFuture;
import android.view.WindowManager;
import android.Manifest;
import android.content.pm.PackageManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.print.PrintAttributes;
import android.print.PrintManager;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;

public class MainActivity extends Activity implements LifecycleOwner {
    private WebView web;
    private ValueCallback<Uri[]> fileCb;
    private static final int PICK = 42, CAM = 43;
    private PermissionRequest camReq; // demande caméra en attente (scan QR code)
    private WebView printView; // gardée en mémoire pendant l'impression

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        life.handleLifecycleEvent(Lifecycle.Event.ON_CREATE);
        // Protection des données : bloque captures d'écran et aperçu dans les applis récentes
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        tauxMax(); // affichage fluide : 120 Hz si l'écran le permet
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true); // lecture du fichier de partage choisi par l'utilisateur
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setSaveFormData(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return true; } // aucune navigation externe
            @Override public void onPageFinished(WebView v, String url) { pushCut(); }
        });
        web.setWebChromeClient(new WebChromeClient() {
            // Caméra : uniquement pour scanner le QR code de synchronisation, après accord de l'utilisateur
            @Override
            public void onPermissionRequest(final PermissionRequest req) {
                runOnUiThread(() -> {
                    boolean video = false;
                    for (String r : req.getResources()) if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) video = true;
                    if (!video) { req.deny(); return; }
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        req.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                    } else {
                        camReq = req;
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, CAM);
                    }
                });
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try { startActivityForResult(Intent.createChooser(i, "Choisir le fichier de partage"), PICK); }
                catch (Exception e) { fileCb = null; return false; }
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        web.addJavascriptInterface(new PrintBridge(), "AndroidPrint");
        web.addJavascriptInterface(new VideoBridge(), "AndroidVideo");
        web.loadUrl("file:///android_asset/index.html");
        // Zone vidéo (aperçu caméra ou lecture) au-dessus de l'appli, ou à gauche en mode paysage
        // Affichage jusque sous l'encoche (plus de bande noire en haut)
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams wlp = getWindow().getAttributes();
            wlp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(wlp);
        }
        root = new LinearLayout(this);
        root.setBackgroundColor(0xFF020617);
        root.setOnApplyWindowInsetsListener((v, in) -> { readCut(in); return in; });
        panel = new FrameLayout(this);
        panel.setBackgroundColor(Color.BLACK);
        panel.setVisibility(View.GONE);
        root.addView(panel);
        root.addView(web);
        applyLayout();
        setContentView(root);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req == VID) {
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) vidBegin();
            else { String id = vidPending; vidPending = null; js("err", id, "'Autorise la caméra pour filmer le match'"); }
        } else if (req == CAM && camReq != null) {
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) camReq.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
            else camReq.deny();
            camReq = null;
        } else super.onRequestPermissionsResult(req, perms, res);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == PICK && fileCb != null) {
            Uri[] r = (res == RESULT_OK && data != null && data.getData() != null) ? new Uri[]{data.getData()} : null;
            fileCb.onReceiveValue(r);
            fileCb = null;
        } else super.onActivityResult(req, res, data);
    }

    class Bridge {
        @JavascriptInterface
        public void share(String text) {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, text);
            startActivity(Intent.createChooser(i, "Partager"));
        }

        // Partage d'une image (PNG envoyée en base64 par la page), par ex. le bilan du match
        @JavascriptInterface
        public boolean shareImage(String name, String b64) {
            try {
                File dir = new File(getCacheDir(), "partage");
                dir.mkdirs();
                File f = new File(dir, name.replaceAll("[^A-Za-z0-9._-]", "_"));
                FileOutputStream o = new FileOutputStream(f);
                o.write(android.util.Base64.decode(b64, android.util.Base64.DEFAULT));
                o.close();
                Uri u = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fichiers", f);
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("image/png");
                i.putExtra(Intent.EXTRA_STREAM, u);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(i, "Partager le bilan"));
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public boolean shareFile(String name, String text) { return shareFileType(name, text, "text/plain"); }

        @JavascriptInterface
        public boolean shareFileType(String name, String text, String mime) {
            try {
                File dir = new File(getCacheDir(), "partage");
                dir.mkdirs();
                File f = new File(dir, name.replaceAll("[^A-Za-z0-9._-]", "_"));
                FileOutputStream o = new FileOutputStream(f);
                o.write(text.getBytes("UTF-8"));
                o.close();
                Uri u = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fichiers", f);
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType(mime);
                i.putExtra(Intent.EXTRA_STREAM, u);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(i, "Partager le compte"));
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    // Impression / enregistrement en PDF via le service d'impression Android
    class PrintBridge {
        @JavascriptInterface
        public void print(final String html, final String title) {
            runOnUiThread(() -> {
                WebView pv = new WebView(MainActivity.this);
                pv.setWebViewClient(new WebViewClient() {
                    @Override public void onPageFinished(WebView v, String url) {
                        PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
                        pm.print(title, v.createPrintDocumentAdapter(title), new PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build());
                    }
                    @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return true; }
                });
                pv.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
                printView = pv;
            });
        }
    }

    @Override public void onBackPressed() { if (web.canGoBack()) web.goBack(); else super.onBackPressed(); }

    // Demande au téléphone la fréquence d'écran la plus élevée disponible (120 Hz sur les écrans compatibles),
    // à la même résolution que l'affichage actuel
    private void tauxMax() {
        try {
            Display d = getWindowManager().getDefaultDisplay();
            Display.Mode cur = d.getMode(), best = cur;
            for (Display.Mode m : d.getSupportedModes()) {
                if (m.getPhysicalWidth() == cur.getPhysicalWidth() && m.getPhysicalHeight() == cur.getPhysicalHeight()
                        && m.getRefreshRate() > best.getRefreshRate()) best = m;
            }
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.preferredDisplayModeId = best.getModeId();
            lp.preferredRefreshRate = best.getRefreshRate();
            getWindow().setAttributes(lp);
        } catch (Exception ignored) { }
    }

    @Override
    protected void onResume() {
        super.onResume();
        life.handleLifecycleEvent(Lifecycle.Event.ON_RESUME);
        tauxMax();
        pleinEcran();
    }

    // Plein écran : barre d'état et barre de navigation masquées (glisser depuis un bord pour les faire réapparaître)
    @SuppressWarnings("deprecation")
    private void pleinEcran() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            pleinEcran();
            root.post(() -> { WindowInsets in = getWindow().getDecorView().getRootWindowInsets(); if (in != null) readCut(in); });
        }
    }

    // ===== Encoche : position de la caméra transmise à la page pour que le bandeau s'y adapte =====
    private int cutT, cutL, cutR;
    private Rect hole;

    private void readCut(WindowInsets in) {
        int t = 0, l = 0, r = 0;
        Rect h = null;
        if (Build.VERSION.SDK_INT >= 28) {
            DisplayCutout dc = in.getDisplayCutout();
            if (dc != null) {
                t = dc.getSafeInsetTop(); l = dc.getSafeInsetLeft(); r = dc.getSafeInsetRight();
                for (Rect b : dc.getBoundingRects()) if (b.top <= 0 && t > 0 && b.bottom <= t + 4) h = b;
            }
        }
        if (t == cutT && l == cutL && r == cutR && (h == null ? hole == null : h.equals(hole))) return;
        cutT = t; cutL = l; cutR = r; hole = h;
        applyLayout();
        pushCut();
    }

    private void pushCut() {
        if (web == null) return;
        float d = getResources().getDisplayMetrics().density;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        boolean land = dm.widthPixels > dm.heightPixels;
        boolean topFree = land || panel == null || panel.getVisibility() != View.VISIBLE;
        float t = topFree ? cutT / d : 0, hl = 0, hr = 0;
        if (topFree && hole != null) { hl = (hole.left - cutL) / d; hr = (hole.right - cutL) / d; }
        // Côtés (paysage) : la page s'étend sous la caméra, seuls les contenus sont décalés
        boolean panelLeft = land && panel != null && panel.getVisibility() == View.VISIBLE;
        float sl = panelLeft ? 0 : cutL / d, sr = cutR / d;
        final String code = "window.setCut&&setCut(" + t + "," + hl + "," + hr + "," + sl + "," + sr + ")";
        web.post(() -> web.evaluateJavascript(code, null));
    }

    @Override protected void onStart() { super.onStart(); life.handleLifecycleEvent(Lifecycle.Event.ON_START); }
    @Override protected void onPause() { life.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE); super.onPause(); }
    @Override protected void onStop() {
        // L'appli passe en arrière-plan : la vidéo est arrêtée et enregistrée proprement
        if (recording != null) recording.stop();
        if (vv != null) vv.pause();
        life.handleLifecycleEvent(Lifecycle.Event.ON_STOP);
        super.onStop();
    }
    @Override protected void onDestroy() { life.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY); super.onDestroy(); }

    @Override
    public void onConfigurationChanged(@NonNull Configuration c) {
        super.onConfigurationChanged(c);
        applyLayout();
        if (vcap != null && recording == null) vcap.setTargetRotation(getWindowManager().getDefaultDisplay().getRotation());
    }

    // ===== Vidéo du match : enregistrement (CameraX) et relecture, fichiers gardés dans l'espace privé de l'appli =====
    private final LifecycleRegistry life = new LifecycleRegistry(this);
    private LinearLayout root;
    private FrameLayout panel;
    private VideoView vv;
    private ProcessCameraProvider camProv;
    private VideoCapture<Recorder> vcap;
    private Recording recording;
    private String vidPending;
    private static final int VID = 44;

    @NonNull @Override public Lifecycle getLifecycle() { return life; }

    private void applyLayout() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        boolean land = dm.widthPixels > dm.heightPixels;
        root.setOrientation(land ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        LinearLayout.LayoutParams pl, wl;
        if (land) {
            pl = new LinearLayout.LayoutParams((int) (dm.widthPixels * 0.33f), ViewGroup.LayoutParams.MATCH_PARENT);
            wl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        } else {
            pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) Math.min(dm.widthPixels * 9f / 16f, dm.heightPixels * 0.2f));
            wl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        }
        if (!land) pl.topMargin = cutT; // la vidéo ne passe pas sous l'encoche
        panel.setLayoutParams(pl);
        web.setLayoutParams(wl);
    }

    private File vidFile(String id) {
        File d = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (d == null) d = new File(getFilesDir(), "videos");
        d.mkdirs();
        return new File(d, id.replaceAll("[^A-Za-z0-9_-]", "_") + ".mp4");
    }

    private void js(String type, String id, String val) {
        if (id == null) return;
        final String code = "window.vidCb&&vidCb('" + type + "','" + id.replaceAll("[^A-Za-z0-9_-]", "") + "'," + val + ")";
        runOnUiThread(() -> web.evaluateJavascript(code, null));
    }

    private void showPanel(View v) {
        panel.removeAllViews();
        panel.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        panel.setVisibility(View.VISIBLE);
        pushCut();
    }

    private void hidePanel() {
        panel.removeAllViews();
        panel.setVisibility(View.GONE);
        pushCut();
    }

    private void vidStartReq(String id) {
        if (recording != null) return;
        vidPending = id;
        boolean cam = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        if (cam && mic) vidBegin();
        else requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, VID);
    }

    @SuppressLint("MissingPermission")
    private void vidBegin() {
        final String id = vidPending;
        vidPending = null;
        if (id == null || recording != null) return;
        vidClose();
        final PreviewView pv = new PreviewView(this);
        pv.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        FrameLayout box = new FrameLayout(this);
        box.addView(pv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView dot = new TextView(this);
        dot.setText("● REC");
        dot.setTextColor(Color.WHITE);
        dot.setBackgroundColor(0xCCDC2626);
        dot.setPadding(14, 4, 14, 4);
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        dl.setMargins(12, 12, 0, 0);
        box.addView(dot, dl);
        showPanel(box);
        final ListenableFuture<ProcessCameraProvider> f = ProcessCameraProvider.getInstance(this);
        f.addListener(() -> {
            try {
                camProv = f.get();
                Preview prev = new Preview.Builder().build();
                prev.setSurfaceProvider(pv.getSurfaceProvider());
                Recorder rc = new Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)))
                        .build();
                vcap = VideoCapture.withOutput(rc);
                vcap.setTargetRotation(getWindowManager().getDefaultDisplay().getRotation());
                camProv.unbindAll();
                camProv.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, prev, vcap);
                PendingRecording pr = vcap.getOutput().prepareRecording(this, new FileOutputOptions.Builder(vidFile(id)).build());
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) pr = pr.withAudioEnabled();
                recording = pr.start(ContextCompat.getMainExecutor(this), e -> onVidEvent(id, e));
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } catch (Exception ex) {
                if (camProv != null) camProv.unbindAll();
                recording = null;
                hidePanel();
                js("err", id, "'Caméra indisponible'");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void onVidEvent(String id, VideoRecordEvent e) {
        if (e instanceof VideoRecordEvent.Start) {
            js("start", id, String.valueOf(System.currentTimeMillis()));
        } else if (e instanceof VideoRecordEvent.Finalize) {
            long dur = e.getRecordingStats().getRecordedDurationNanos() / 1000000L;
            recording = null;
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (camProv != null) camProv.unbindAll();
            vcap = null;
            hidePanel();
            File f = vidFile(id);
            if (f.exists() && f.length() > 0 && dur > 0) js("end", id, String.valueOf(dur));
            else { f.delete(); js("err", id, "'La vidéo n\\'a pas pu être enregistrée'"); }
        }
    }

    private void vidStop() {
        if (recording != null) recording.stop();
    }

    private void vidPlay(String id) {
        if (recording != null) return;
        vidClose();
        File f = vidFile(id);
        if (!f.exists()) return;
        final VideoView v = new VideoView(this);
        MediaController mc = new MediaController(this);
        mc.setAnchorView(v);
        v.setMediaController(mc);
        v.setOnPreparedListener(mp -> v.start());
        v.setVideoURI(Uri.fromFile(f));
        vv = v;
        showPanel(v);
    }

    private void vidClose() {
        if (vv != null) { vv.stopPlayback(); vv = null; }
        if (recording == null) hidePanel();
    }

    class VideoBridge {
        @JavascriptInterface public void start(String id) { runOnUiThread(() -> vidStartReq(id)); }
        @JavascriptInterface public void stop() { runOnUiThread(MainActivity.this::vidStop); }
        @JavascriptInterface public boolean recording() { return recording != null; }
        @JavascriptInterface public void play(String id) { runOnUiThread(() -> vidPlay(id)); }
        @JavascriptInterface public void seek(int ms) { runOnUiThread(() -> { if (vv != null) { vv.seekTo(ms); vv.start(); } }); }
        @JavascriptInterface public void close() { runOnUiThread(MainActivity.this::vidClose); }
        @JavascriptInterface public long size(String id) { File f = vidFile(id); return f.exists() ? f.length() : -1; }
        @JavascriptInterface public boolean del(String id) { return vidFile(id).delete(); }
        @JavascriptInterface public void share(String id) {
            runOnUiThread(() -> {
                try {
                    Uri u = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fichiers", vidFile(id));
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("video/mp4");
                    i.putExtra(Intent.EXTRA_STREAM, u);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(i, "Partager la vidéo"));
                } catch (Exception ignored) { }
            });
        }
    }
}
