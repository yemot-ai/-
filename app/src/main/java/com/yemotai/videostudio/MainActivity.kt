package com.yemotai.videostudio

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import java.io.OutputStream

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val fileRequestCode = 4107
    private val saveRequestCode = 4108
    private val saveLock = Any()
    private var saveStream: OutputStream? = null

    private inner class CutlabBridge {
        @JavascriptInterface
        fun requestSave(name: String, mimeType: String) {
            runOnUiThread { openSaveDialog(name, mimeType) }
        }

        @JavascriptInterface
        fun writeChunk(encodedChunk: String): Boolean = synchronized(saveLock) {
            val output = saveStream ?: return@synchronized false
            try {
                output.write(Base64.decode(encodedChunk, Base64.DEFAULT))
                true
            } catch (_: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun finishSave(success: Boolean) {
            synchronized(saveLock) {
                try { saveStream?.flush() } catch (_: Exception) { }
                try { saveStream?.close() } catch (_: Exception) { }
                saveStream = null
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(13, 14, 18)
        window.navigationBarColor = Color.rgb(13, 14, 18)
        window.decorView.systemUiVisibility = 0

        webView = WebView(this)
        webView.setBackgroundColor(Color.rgb(13, 14, 18))
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.mediaPlaybackRequiresUserGesture = true
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.allowFileAccessFromFileURLs = true
        settings.allowUniversalAccessFromFileURLs = false

        webView.addJavascriptInterface(CutlabBridge(), "CUTLABNative")
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val intent = try {
                    params?.createIntent() ?: Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                } catch (_: Exception) {
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                }
                intent.addCategory(Intent.CATEGORY_OPENABLE)
                intent.type = "*/*"
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                return try {
                    startActivityForResult(intent, fileRequestCode)
                    true
                } catch (_: Exception) {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = null
                    false
                }
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean = false
        }
        setContentView(FrameLayout(this).apply {
            addView(webView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        })
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun openSaveDialog(name: String, mimeType: String) {
        val safeName = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\r\\n\\u0000]"), "_")
            .take(180).ifBlank { "CUTLAB-export.bin" }
        val safeMime = mimeType.takeIf {
            it.matches(Regex("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+"))
        } ?: "application/octet-stream"
        synchronized(saveLock) {
            try { saveStream?.close() } catch (_: Exception) { }
            saveStream = null
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = safeMime
            putExtra(Intent.EXTRA_TITLE, safeName)
        }
        try {
            startActivityForResult(intent, saveRequestCode)
        } catch (_: Exception) {
            notifySaveReady(false)
        }
    }

    private fun notifySaveReady(ready: Boolean) {
        runOnUiThread {
            if (::webView.isInitialized) {
                webView.evaluateJavascript(
                    "if(window.__cutlabNativeSaveReady){window.__cutlabNativeSaveReady($ready);}",
                    null
                )
            }
        }
    }

    @Deprecated("Used for compatibility with WebView's file chooser and Storage Access Framework")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == saveRequestCode) {
            val uri = if (resultCode == RESULT_OK) data?.data else null
            if (uri == null) {
                notifySaveReady(false)
                return
            }
            try {
                val output = contentResolver.openOutputStream(uri, "w")
                    ?: throw IllegalStateException("Could not open the selected file")
                synchronized(saveLock) { saveStream = output }
                notifySaveReady(true)
            } catch (_: Exception) {
                synchronized(saveLock) { saveStream = null }
                notifySaveReady(false)
            }
            return
        }
        if (requestCode == fileRequestCode) {
            val result: Array<Uri>? = if (resultCode == RESULT_OK && data != null) {
                val clip = data.clipData
                if (clip != null) {
                    Array(clip.itemCount) { index -> clip.getItemAt(index).uri }
                } else {
                    data.data?.let { arrayOf(it) }
                }
            } else null
            fileCallback?.onReceiveValue(result)
            fileCallback = null
        }
    }

    @Deprecated("Use system back dispatch where available")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        synchronized(saveLock) {
            try { saveStream?.close() } catch (_: Exception) { }
            saveStream = null
        }
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }
}