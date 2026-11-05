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
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val fileRequestCode = 4107
    private val saveRequestCode = 4108
    private val saveLock = Any()
    private var saveStream: OutputStream? = null

    private val mp4Lock = Any()
    private val mp4SaveRequestCode = 4109
    private var mp4InputStream: FileOutputStream? = null
    private var mp4InputFile: File? = null
    private var mp4OutputFile: File? = null
    private var mp4DisplayName: String = "סרטון.mp4"
    private var mp4SaveFile: File? = null
    private var mp4Transformer: Transformer? = null

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

        // The editor renders a WebM intermediate in WebView. Media3 converts it
        // with Android's native codecs and MP4 muxer, so the saved file is a real MP4.
        @JavascriptInterface
        fun beginMp4Export(name: String): Boolean = synchronized(mp4Lock) {
            try {
                cleanupMp4FilesLocked()
                val clean = name.substringAfterLast('/').substringAfterLast('\\')
                    .replace(Regex("[\\r\\n\\u0000]"), "_")
                    .let { if (it.endsWith(".mp4", true)) it else "$it.mp4" }
                    .ifBlank { "סרטון.mp4" }
                val id = System.currentTimeMillis()
                val input = File(cacheDir, "cutlab-$id.webm")
                val output = File(cacheDir, "cutlab-$id.mp4")
                mp4DisplayName = clean
                mp4InputFile = input
                mp4OutputFile = output
                mp4InputStream = FileOutputStream(input)
                true
            } catch (_: Exception) {
                cleanupMp4FilesLocked()
                false
            }
        }

        @JavascriptInterface
        fun writeMp4Chunk(encodedChunk: String): Boolean = synchronized(mp4Lock) {
            val output = mp4InputStream ?: return@synchronized false
            try {
                output.write(Base64.decode(encodedChunk, Base64.DEFAULT))
                true
            } catch (_: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun finishMp4Export(): Boolean {
            val files = synchronized(mp4Lock) {
                try {
                    mp4InputStream?.flush()
                    mp4InputStream?.close()
                    mp4InputStream = null
                } catch (_: Exception) {
                    cleanupMp4FilesLocked()
                    return false
                }
                val input = mp4InputFile
                val output = mp4OutputFile
                if (input == null || output == null || !input.isFile || input.length() < 128L) {
                    cleanupMp4FilesLocked()
                    return false
                }
                input to output
            }
            runOnUiThread { transcodeWebmToMp4(files.first, files.second) }
            return true
        }

        @JavascriptInterface
        fun cancelMp4Export() {
            runOnUiThread {
                try { mp4Transformer?.cancel() } catch (_: Exception) { }
                mp4Transformer = null
                synchronized(mp4Lock) { cleanupMp4FilesLocked() }
                notifyMp4Finished(false, "הייצוא בוטל")
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

    private fun transcodeWebmToMp4(input: File, output: File) {
        try {
            if (!input.isFile || input.length() < 128L) {
                notifyMp4Finished(false, "קובץ הביניים ריק או פגום")
                return
            }
            if (output.exists()) output.delete()
            val listener = object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    mp4Transformer = null
                    if (!output.isFile || output.length() < 128L) {
                        notifyMp4Finished(false, "מנוע ההמרה לא יצר קובץ MP4 תקין")
                        synchronized(mp4Lock) { cleanupMp4FilesLocked() }
                    } else {
                        openMp4SaveDialog(output)
                    }
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    mp4Transformer = null
                    val reason = exportException.message ?: exportException.errorCodeName
                    notifyMp4Finished(
                        false,
                        "המכשיר לא הצליח לקודד את הסרטון ל-MP4: $reason"
                    )
                    synchronized(mp4Lock) { cleanupMp4FilesLocked() }
                }
            }
            mp4Transformer = Transformer.Builder(this)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(listener)
                .build()
            mp4Transformer?.start(MediaItem.fromUri(Uri.fromFile(input)), output.absolutePath)
        } catch (e: Exception) {
            mp4Transformer = null
            notifyMp4Finished(false, e.message ?: "המרת MP4 נכשלה")
            synchronized(mp4Lock) { cleanupMp4FilesLocked() }
        }
    }

    private fun openMp4SaveDialog(file: File) {
        mp4SaveFile = file
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/mp4"
            putExtra(Intent.EXTRA_TITLE, mp4DisplayName)
        }
        try {
            startActivityForResult(intent, mp4SaveRequestCode)
        } catch (_: Exception) {
            notifyMp4Finished(false, "לא ניתן לפתוח חלון לשמירת MP4")
            synchronized(mp4Lock) { cleanupMp4FilesLocked() }
        }
    }

    private fun notifyMp4Finished(success: Boolean, message: String) {
        runOnUiThread {
            if (::webView.isInitialized) {
                val safeMessage = JSONObject.quote(message)
                webView.evaluateJavascript(
                    "window.__cutlabNativeMp4Finished?.($success, $safeMessage);",
                    null
                )
            }
        }
    }

    private fun cleanupMp4FilesLocked() {
        try { mp4InputStream?.close() } catch (_: Exception) { }
        mp4InputStream = null
        try { mp4InputFile?.delete() } catch (_: Exception) { }
        mp4InputFile = null
        try { mp4OutputFile?.delete() } catch (_: Exception) { }
        mp4OutputFile = null
        mp4SaveFile = null
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
        if (requestCode == mp4SaveRequestCode) {
            val uri = if (resultCode == RESULT_OK) data?.data else null
            val file = mp4SaveFile
            if (uri == null || file == null || !file.isFile) {
                notifyMp4Finished(false, "לא נבחר מיקום לשמירת קובץ ה-MP4")
                synchronized(mp4Lock) { cleanupMp4FilesLocked() }
                return
            }
            Thread {
                try {
                    contentResolver.openOutputStream(uri, "w")?.use { output ->
                        file.inputStream().use { input -> input.copyTo(output, 256 * 1024) }
                        output.flush()
                    } ?: throw IllegalStateException("לא ניתן לפתוח את קובץ היעד")
                    runOnUiThread {
                        notifyMp4Finished(true, "הסרטון נשמר בהצלחה כ-MP4")
                        synchronized(mp4Lock) { cleanupMp4FilesLocked() }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        notifyMp4Finished(false, e.message ?: "שמירת MP4 נכשלה")
                        synchronized(mp4Lock) { cleanupMp4FilesLocked() }
                    }
                }
            }.start()
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
        try { mp4Transformer?.cancel() } catch (_: Exception) { }
        mp4Transformer = null
        synchronized(mp4Lock) { cleanupMp4FilesLocked() }
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }
}