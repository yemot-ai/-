package com.yemotai.posterstudio;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.JavascriptInterface;
import android.widget.Toast;
import java.io.OutputStream;

public class MainActivity extends Activity {
    private static final int REQUEST_OPEN = 4101;
    private static final int REQUEST_SAVE = 4102;
    private WebView webView;
    private ValueCallback<Uri[]> fileChooserCallback;
    private byte[] pendingBytes;
    private String pendingMime = "application/octet-stream";
    private String pendingName = "studio-file";

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(android.graphics.Color.rgb(11,16,32));
        getWindow().setNavigationBarColor(android.graphics.Color.rgb(11,16,32));
        webView = new WebView(this);
        webView.setBackgroundColor(android.graphics.Color.rgb(11,16,32));
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setDatabaseEnabled(true);
        webView.addJavascriptInterface(new NativeBridge(), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
                fileChooserCallback = callback;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");
                try { startActivityForResult(intent, REQUEST_OPEN); }
                catch (Exception ex) { fileChooserCallback = null; Toast.makeText(MainActivity.this, "לא ניתן לפתוח בורר קבצים", Toast.LENGTH_SHORT).show(); return false; }
                return true;
            }
        });
        setContentView(webView);
        if (savedInstanceState == null) webView.loadUrl("file:///android_asset/index.html");
        else webView.restoreState(savedInstanceState);
    }

    public final class NativeBridge {
        @JavascriptInterface public void saveFile(String name, String mime, String base64) {
            runOnUiThread(() -> {
                try {
                    pendingName = (name == null || name.trim().isEmpty()) ? "studio-file" : name.replaceAll("[^A-Za-z0-9._-]", "_");
                    pendingMime = (mime == null || mime.isEmpty()) ? "application/octet-stream" : mime;
                    pendingBytes = Base64.decode(base64, Base64.DEFAULT);
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType(pendingMime);
                    intent.putExtra(Intent.EXTRA_TITLE, pendingName);
                    startActivityForResult(intent, REQUEST_SAVE);
                } catch (Exception ex) {
                    Toast.makeText(MainActivity.this, "לא ניתן להכין את הקובץ לשמירה", Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OPEN) {
            if (fileChooserCallback != null) {
                Uri[] result = (resultCode == RESULT_OK && data != null && data.getData() != null) ? new Uri[]{data.getData()} : null;
                fileChooserCallback.onReceiveValue(result);
                fileChooserCallback = null;
            }
            return;
        }
        if (requestCode == REQUEST_SAVE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null && pendingBytes != null) {
                try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                    if (out != null) { out.write(pendingBytes); out.flush(); Toast.makeText(this, "הקובץ נשמר", Toast.LENGTH_SHORT).show(); }
                } catch (Exception ex) {
                    Toast.makeText(this, "השמירה נכשלה", Toast.LENGTH_LONG).show();
                }
            }
            pendingBytes = null;
        }
    }

    @Override protected void onSaveInstanceState(Bundle outState) { super.onSaveInstanceState(outState); if (webView != null) webView.saveState(outState); }
    @Override public void onBackPressed() { if (webView != null && webView.canGoBack()) webView.goBack(); else super.onBackPressed(); }
    @Override protected void onDestroy() { if (webView != null) webView.destroy(); super.onDestroy(); }
}
