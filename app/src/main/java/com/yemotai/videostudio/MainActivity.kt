package com.yemotai.videostudio

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
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
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteOrder
import kotlin.math.max

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

    private val transcriptionLock = Any()
    private var transcriptionOutput: FileOutputStream? = null
    private var transcriptionInputFile: File? = null
    @Volatile private var transcriptionCancelled = false
    @Volatile private var transcriptionThread: Thread? = null
    @Volatile private var hebrewRecognizer: OnlineRecognizer? = null

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

        @JavascriptInterface
        fun beginTranscription(name: String, mimeType: String): Boolean = synchronized(transcriptionLock) {
            try {
                if (transcriptionThread?.isAlive == true) return@synchronized false
                try { transcriptionOutput?.close() } catch (_: Exception) { }
                transcriptionOutput = null
                try { transcriptionInputFile?.delete() } catch (_: Exception) { }
                val file = File(cacheDir, "social-caption-" + System.currentTimeMillis() + ".media")
                transcriptionInputFile = file
                transcriptionOutput = FileOutputStream(file)
                transcriptionCancelled = false
                true
            } catch (_: Exception) {
                transcriptionOutput = null
                transcriptionInputFile = null
                false
            }
        }

        @JavascriptInterface
        fun writeTranscriptionChunk(encodedChunk: String): Boolean = synchronized(transcriptionLock) {
            val output = transcriptionOutput ?: return@synchronized false
            try {
                output.write(Base64.decode(encodedChunk, Base64.DEFAULT))
                true
            } catch (_: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun finishTranscription(): Boolean {
            val file = synchronized(transcriptionLock) {
                try {
                    transcriptionOutput?.flush()
                    transcriptionOutput?.close()
                } catch (_: Exception) {
                    return false
                }
                transcriptionOutput = null
                val input = transcriptionInputFile
                if (input == null || !input.isFile || input.length() < 256L) return false
                input
            }
            transcriptionCancelled = false
            transcriptionThread = Thread {
                transcribeVideoAudio(file)
            }.also { it.start() }
            return true
        }

        @JavascriptInterface
        fun cancelTranscription() {
            transcriptionCancelled = true
            synchronized(transcriptionLock) {
                try { transcriptionOutput?.close() } catch (_: Exception) { }
                transcriptionOutput = null
            }
            try { transcriptionThread?.interrupt() } catch (_: Exception) { }
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

    private class StreamingLinearResampler(
        private val inputRate: Int,
        private val emit: (FloatArray) -> Unit
    ) {
        private val ratio = inputRate.toDouble() / 16000.0
        private var inputIndex = 0L
        private var nextOutputPosition = 0.0
        private var previous: Float? = null
        private val pending = FloatArray(3200)
        private var pendingCount = 0

        fun accept(samples: FloatArray) {
            for (sample in samples) {
                val prev = previous
                if (prev == null) {
                    push(sample)
                    nextOutputPosition = ratio
                } else {
                    val currentIndex = inputIndex.toDouble()
                    while (nextOutputPosition <= currentIndex) {
                        val fraction = (nextOutputPosition - (currentIndex - 1.0)).coerceIn(0.0, 1.0)
                        push((prev + (sample - prev) * fraction).toFloat())
                        nextOutputPosition += ratio
                    }
                }
                previous = sample
                inputIndex += 1L
            }
        }

        private fun push(value: Float) {
            pending[pendingCount++] = value
            if (pendingCount == pending.size) {
                emit(pending.copyOf(pendingCount))
                pendingCount = 0
            }
        }

        fun finish() {
            if (pendingCount > 0) {
                emit(pending.copyOf(pendingCount))
                pendingCount = 0
            }
        }
    }

    private fun copyHebrewModelAsset(fileName: String): File {
        val target = File(filesDir, "hebrew_asr/" + fileName)
        if (!target.isFile || target.length() == 0L) {
            target.parentFile?.mkdirs()
            assets.open("hebrew_asr/" + fileName).use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output, 256 * 1024) }
            }
        }
        return target
    }

    @Synchronized
    private fun getHebrewRecognizer(): OnlineRecognizer {
        hebrewRecognizer?.let { return it }
        val encoder = copyHebrewModelAsset("encoder.int8.onnx")
        val decoder = copyHebrewModelAsset("decoder.int8.onnx")
        val joiner = copyHebrewModelAsset("joiner.int8.onnx")
        val tokens = copyHebrewModelAsset("tokens.txt")
        val model = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = encoder.absolutePath,
                decoder = decoder.absolutePath,
                joiner = joiner.absolutePath
            ),
            tokens = tokens.absolutePath,
            numThreads = max(1, Runtime.getRuntime().availableProcessors() / 2).coerceAtMost(4),
            provider = "cpu",
            modelType = "zipformer2"
        )
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = model,
            endpointConfig = EndpointConfig(),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
            maxActivePaths = 4
        )
        return OnlineRecognizer(assetManager = null, config = config).also { hebrewRecognizer = it }
    }

    private fun notifyTranscriptionProgress(percent: Int, message: String) {
        runOnUiThread {
            if (::webView.isInitialized) {
                val code = "window.__socialTranscriptionProgress?.(" +
                    percent.coerceIn(0, 100) + "," + JSONObject.quote(message) + ");"
                webView.evaluateJavascript(code, null)
            }
        }
    }

    private fun notifyTranscriptionFinished(segments: JSONArray?, error: String?) {
        runOnUiThread {
            if (::webView.isInitialized) {
                val payload = JSONObject.quote(segments?.toString() ?: "[]")
                val errorValue = if (error == null) "null" else JSONObject.quote(error)
                val code = "window.__socialTranscriptionFinished?.(" + payload + "," + errorValue + ");"
                webView.evaluateJavascript(code, null)
            }
        }
    }

    private fun transcribeVideoAudio(input: File) {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var stream: com.k2fsa.sherpa.onnx.OnlineStream? = null
        try {
            if (!input.isFile || input.length() < 256L) {
                throw IllegalStateException("קובץ הווידאו ריק או לא תקין")
            }
            notifyTranscriptionProgress(2, "טוען את מנוע התמלול העברי…")
            val recognizer = getHebrewRecognizer()
            stream = recognizer.createStream()
            val captions = JSONArray()
            var processedSamples = 0L
            var segmentStart = 0.0

            fun consumeResampled(block: FloatArray) {
                if (block.isEmpty() || transcriptionCancelled) return
                stream!!.acceptWaveform(block, 16000)
                processedSamples += block.size
                while (recognizer.isReady(stream!!)) {
                    recognizer.decode(stream!!)
                }
                if (recognizer.isEndpoint(stream!!)) {
                    val text = recognizer.getResult(stream!!).text.trim()
                    val end = processedSamples.toDouble() / 16000.0
                    if (text.isNotBlank()) {
                        captions.put(
                            JSONObject()
                                .put("start", segmentStart)
                                .put("end", max(segmentStart + 0.25, end))
                                .put("text", text)
                        )
                    }
                    recognizer.reset(stream!!)
                    segmentStart = end
                }
            }

            extractor = MediaExtractor()
            extractor.setDataSource(input.absolutePath)
            var audioTrack = -1
            var inputFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrack = index
                    inputFormat = format
                    break
                }
            }
            if (audioTrack < 0 || inputFormat == null) {
                throw IllegalStateException("לא נמצא פס קול בסרטון")
            }

            extractor.selectTrack(audioTrack)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalStateException("פורמט האודיו אינו נתמך")
            val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                inputFormat.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(1L)
            } else 0L

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            var inputDone = false
            var outputDone = false
            var outputRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var outputChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var outputEncoding = AudioFormat.ENCODING_PCM_16BIT
            var activeResampleRate = 0
            var resampler: StreamingLinearResampler? = null
            var lastProgress = -1

            notifyTranscriptionProgress(5, "מפענח את פס הקול…")
            val info = MediaCodec.BufferInfo()
            while (!outputDone && !transcriptionCancelled) {
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(10000)
                    if (inputIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inputIndex)
                            ?: throw IllegalStateException("לא ניתן לקרוא את נתוני האודיו")
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            val time = extractor.sampleTime.coerceAtLeast(0L)
                            decoder.queueInputBuffer(inputIndex, 0, size, time, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = decoder.dequeueOutputBuffer(info, 10000)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val fmt = decoder.outputFormat
                        if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            outputRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            outputChannels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        if (fmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            outputEncoding = fmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    }
                    outputIndex >= 0 -> {
                        if (info.size > 0) {
                            val bytes = decoder.getOutputBuffer(outputIndex)
                            if (bytes != null) {
                                val pcm = bytes.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                                pcm.position(info.offset)
                                pcm.limit(info.offset + info.size)
                                val bytesPerSample = if (outputEncoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                                val channels = outputChannels.coerceAtLeast(1)
                                val frameCount = pcm.remaining() / (bytesPerSample * channels)
                                val mono = FloatArray(frameCount)
                                for (frame in 0 until frameCount) {
                                    var total = 0f
                                    for (channel in 0 until channels) {
                                        val value = if (outputEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                            pcm.float
                                        } else {
                                            pcm.short.toFloat() / 32768.0f
                                        }
                                        total += value
                                    }
                                    mono[frame] = total / channels.toFloat()
                                }
                                if (activeResampleRate != outputRate || resampler == null) {
                                    resampler?.finish()
                                    activeResampleRate = outputRate
                                    resampler = StreamingLinearResampler(outputRate, ::consumeResampled)
                                }
                                resampler?.accept(mono)
                            }
                        }
                        decoder.releaseOutputBuffer(outputIndex, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                        if (durationUs > 0L) {
                            val currentUs = extractor.sampleTime.coerceAtLeast(0L)
                            val progress = (5 + (currentUs.toDouble() / durationUs.toDouble() * 75.0)).toInt()
                                .coerceIn(5, 80)
                            if (progress > lastProgress) {
                                lastProgress = progress
                                notifyTranscriptionProgress(progress, "מתמלל בעברית… " + progress + "%")
                            }
                        }
                    }
                }
            }

            if (transcriptionCancelled) {
                throw InterruptedException("התמלול בוטל")
            }
            resampler?.finish()
            val audioDuration = processedSamples.toDouble() / 16000.0

            // Flush the streaming recognizer so it can return the final words.
            stream!!.acceptWaveform(FloatArray(24000), 16000)
            while (recognizer.isReady(stream!!)) recognizer.decode(stream!!)
            stream!!.inputFinished()
            while (recognizer.isReady(stream!!)) recognizer.decode(stream!!)
            val finalText = recognizer.getResult(stream!!).text.trim()
            if (finalText.isNotBlank()) {
                captions.put(
                    JSONObject()
                        .put("start", segmentStart)
                        .put("end", max(segmentStart + 0.25, audioDuration))
                        .put("text", finalText)
                )
            }

            if (transcriptionCancelled) throw InterruptedException("התמלול בוטל")
            notifyTranscriptionProgress(100, "התמלול הושלם")
            notifyTranscriptionFinished(captions, null)
        } catch (cancelled: InterruptedException) {
            notifyTranscriptionFinished(null, "התמלול בוטל")
        } catch (e: Exception) {
            notifyTranscriptionFinished(null, e.message ?: "התמלול נכשל")
        } finally {
            try { stream?.release() } catch (_: Exception) { }
            try { decoder?.stop() } catch (_: Exception) { }
            try { decoder?.release() } catch (_: Exception) { }
            try { extractor?.release() } catch (_: Exception) { }
            try { input.delete() } catch (_: Exception) { }
            synchronized(transcriptionLock) {
                if (transcriptionInputFile == input) transcriptionInputFile = null
            }
            transcriptionThread = null
        }
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
        transcriptionCancelled = true
        synchronized(transcriptionLock) {
            try { transcriptionOutput?.close() } catch (_: Exception) { }
            transcriptionOutput = null
        }
        try { transcriptionThread?.interrupt() } catch (_: Exception) { }
        try { mp4Transformer?.cancel() } catch (_: Exception) { }
        mp4Transformer = null
        try { hebrewRecognizer?.release() } catch (_: Exception) { }
        hebrewRecognizer = null
        synchronized(mp4Lock) { cleanupMp4FilesLocked() }
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }
}