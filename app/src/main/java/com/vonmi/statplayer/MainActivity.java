package com.vonmi.statplayer;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;

public class MainActivity extends Activity {
    private WebView web;
    private ValueCallback<Uri[]> fileCb;
    private static final int PICK = 42;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // Protection des données : bloque captures d'écran et aperçu dans les applis récentes
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true); // lecture du fichier de partage choisi par l'utilisateur
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setSaveFormData(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return true; } // aucune navigation externe
        });
        web.setWebChromeClient(new WebChromeClient() {
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
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);
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

        @JavascriptInterface
        public boolean shareFile(String name, String text) {
            try {
                File dir = new File(getCacheDir(), "partage");
                dir.mkdirs();
                File f = new File(dir, name.replaceAll("[^A-Za-z0-9._-]", "_"));
                FileOutputStream o = new FileOutputStream(f);
                o.write(text.getBytes("UTF-8"));
                o.close();
                Uri u = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fichiers", f);
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_STREAM, u);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(i, "Partager le compte"));
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    @Override public void onBackPressed() { if (web.canGoBack()) web.goBack(); else super.onBackPressed(); }
}
