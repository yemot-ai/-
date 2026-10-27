package com.example.offlinetts

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.naman14.androidlame.AndroidLame
import com.naman14.androidlame.LameBuilder
import java.io.*
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private var tts: TextToSpeech? = null
    private var voices: List<Voice> = emptyList()
    private val pending = ConcurrentHashMap<String, PendingSpeech>()
    private lateinit var editor: EditText
    private lateinit var voiceSpinner: Spinner
    private lateinit var speedBar: SeekBar
    private lateinit var pitchBar: SeekBar
    private lateinit var statusView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var convertButton: Button
    private var targetName = "הקראה.mp3"
    private var targetUri: Uri? = null

    private data class PendingSpeech(val latch: CountDownLatch = CountDownLatch(1), @Volatile var ok: Boolean = false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(11, 16, 32)
        window.navigationBarColor = Color.rgb(11, 16, 32)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        buildUi()
        tts = TextToSpeech(this, { result ->
            runOnUiThread {
                if (result == TextToSpeech.SUCCESS) initialiseVoices()
                else showStatus("לא ניתן להפעיל את מנוע ההקראה המובנה.")
            }
        }, packageName)
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) {}
            override fun onDone(id: String) { pending[id]?.let { it.ok = true; it.latch.countDown() } }
            @Deprecated("Deprecated by Android")
            override fun onError(id: String) { pending[id]?.let { it.ok = false; it.latch.countDown() } }
            override fun onError(id: String, errorCode: Int) { pending[id]?.let { it.ok = false; it.latch.countDown() } }
        })
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(10, 15, 29)) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(26))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        scroll.addView(root)
        setContentView(scroll)

        val title = TextView(this).apply {
            text = "🎙  מקריא ל‑MP3"
            textSize = 27f
            setTextColor(Color.rgb(91, 228, 211))
            gravity = Gravity.CENTER_VERTICAL
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        root.addView(title, lp(bottom = 4))
        val subtitle = TextView(this).apply {
            text = "הפיכת טקסט לקובץ שמע · מנוע וקולות לא־מקוונים"
            textSize = 13f
            setTextColor(Color.rgb(173, 190, 216))
        }
        root.addView(subtitle, lp(bottom = 20, top = 2))

        label(root, "הטקסט להקראה")
        editor = EditText(this).apply {
            hint = "הדבק כאן טקסט, או טען קובץ TXT…"
            setText("שלום וברוכים הבאים למקריא ל‑MP3. אפשר לבחור קול, להתאים את מהירות הדיבור ולשמור את ההקראה כקובץ MP3.")
            setTextColor(Color.rgb(244, 247, 255))
            setHintTextColor(Color.rgb(123, 143, 170))
            textSize = 17f
            gravity = Gravity.TOP or Gravity.START
            minLines = 8
            maxLines = 16
            setPadding(dp(14), dp(13), dp(14), dp(13))
            setBackgroundColor(Color.rgb(20, 31, 51))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        root.addView(editor, lp(bottom = 14))

        val importButton = button("📄  טעינת קובץ TXT", false)
        importButton.setOnClickListener { openTextFile() }
        root.addView(importButton, lp(bottom = 18))

        label(root, "בחירת קול מובנה")
        voiceSpinner = Spinner(this)
        voiceSpinner.background = roundedBackground(Color.rgb(20, 31, 51), Color.rgb(54, 76, 111))
        root.addView(voiceSpinner, lp(height = 52, bottom = 16))

        label(root, "מהירות דיבור")
        speedBar = SeekBar(this).apply { max = 16; progress = 5 }
        root.addView(speedBar, lp(bottom = 5))
        val speedNote = TextView(this).apply { text = "איטי  ←  רגיל  →  מהיר"; textSize = 12f; setTextColor(Color.rgb(155, 176, 206)) }
        root.addView(speedNote, lp(bottom = 14))

        label(root, "גובה צליל")
        pitchBar = SeekBar(this).apply { max = 12; progress = 4 }
        root.addView(pitchBar, lp(bottom = 5))
        val pitchNote = TextView(this).apply { text = "נמוך  ←  רגיל  →  גבוה"; textSize = 12f; setTextColor(Color.rgb(155, 176, 206)) }
        root.addView(pitchNote, lp(bottom = 20))

        val previewButton = button("▶  השמעת דוגמה", false)
        previewButton.setOnClickListener { previewVoice() }
        root.addView(previewButton, lp(bottom = 10))

        convertButton = button("⬇  יצירת קובץ MP3", true)
        convertButton.setOnClickListener { requestSave() }
        root.addView(convertButton, lp(bottom = 14))

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 0; visibility = View.GONE }
        root.addView(progressBar, lp(bottom = 10))
        statusView = TextView(this).apply {
            text = "מוכן. מנוע ההקראה וקובצי הקול משולבים בחבילת האפליקציה."
            textSize = 13f
            setTextColor(Color.rgb(194, 213, 238))
            setPadding(dp(13), dp(12), dp(13), dp(12))
            background = roundedBackground(Color.rgb(17, 43, 49), Color.rgb(40, 100, 101))
        }
        root.addView(statusView, lp(bottom = 8))
        val note = TextView(this).apply {
            text = "המרה לא־מקוונת. איכות קול eSpeak NG ממוחשבת יחסית. קובץ ה‑MP3 נשמר במיקום שתבחר."
            textSize = 11f
            setTextColor(Color.rgb(139, 159, 188))
            gravity = Gravity.CENTER
        }
        root.addView(note, lp(top = 8))
    }

    private fun initialiseVoices() {
        val all = tts?.voices?.toList().orEmpty()
        voices = all.filter { !it.isNetworkConnectionRequired && it.features.none { feature -> feature == TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED } }
            .sortedWith(compareBy<Voice>({ it.locale.displayName }, { it.name }))
        if (voices.isEmpty()) {
            showStatus("המנוע עלה, אך לא נמצאו קולות זמינים במכשיר.")
            return
        }
        voiceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            voices.map { it.locale.displayName + " · " + it.name })
        val hebrew = voices.indexOfFirst { it.locale.language.equals("he", true) }
        voiceSpinner.setSelection(if (hebrew >= 0) hebrew else 0)
        showStatus("המנוע העצמאי מוכן · " + voices.size + " קולות זמינים")
    }

    private fun previewVoice() {
        val engine = tts ?: return showStatus("מנוע ההקראה עדיין נטען.")
        val voice = voices.getOrNull(voiceSpinner.selectedItemPosition)
            ?: return showStatus("לא נמצא קול זמין.")
        val phrase = editor.text.toString().trim().take(250).ifBlank { "שלום, זוהי דוגמת קול." }
        engine.stop()
        engine.voice = voice
        engine.setSpeechRate(speechRate())
        engine.setPitch(pitchRate())
        engine.speak(phrase, TextToSpeech.QUEUE_FLUSH, Bundle(), "preview-voice")
        showStatus("משמיע דוגמה: " + voice.locale.displayName)
    }

    private fun openTextFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/*"
        }
        startActivityForResult(intent, 101)
    }

    private fun requestSave() {
        val text = editor.text.toString().trim()
        if (text.isEmpty()) return showStatus("הדבק טקסט או טען קובץ TXT לפני ההמרה.")
        if (voices.isEmpty()) return showStatus("עדיין לא נמצא קול זמין.")
        targetName = "הקראה.mp3"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/mpeg"
            putExtra(Intent.EXTRA_TITLE, targetName)
        }
        startActivityForResult(intent, 102)
    }

    @Deprecated("Deprecated by Android; compatible with Android 8+")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        when (requestCode) {
            101 -> try {
                contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8).use { reader ->
                    editor.setText(reader?.readText().orEmpty())
                }
                showStatus("קובץ הטקסט נטען.")
            } catch (e: Exception) {
                showStatus("לא ניתן לקרוא את הקובץ: " + (e.message ?: "שגיאה"))
            }
            102 -> {
                targetUri = uri
                val chosenVoice = voices.getOrNull(voiceSpinner.selectedItemPosition)
                val engine = tts
                if (chosenVoice == null || engine == null) return showStatus("אין מנוע או קול זמין.")
                convertButton.isEnabled = false
                progressBar.visibility = View.VISIBLE
                progressBar.progress = 0
                showStatus("מתחיל להכין את ההקראה…")
                val chosenRate = speechRate()
                val chosenPitch = pitchRate()
                Thread { convertToMp3(editor.text.toString().trim(), chosenVoice, uri, chosenRate, chosenPitch) }.start()
            }
        }
    }

    private fun convertToMp3(rawText: String, voice: Voice, uri: Uri, rate: Float, pitch: Float) {
        var output: OutputStream? = null
        var writer: Mp3Writer? = null
        try {
            val chunks = splitText(rawText)
            val engine = tts ?: throw IOException("מנוע ההקראה אינו זמין")
            val resultStream = BufferedOutputStream(contentResolver.openOutputStream(uri, "w")
                ?: throw IOException("לא ניתן לפתוח את קובץ היעד"), 64 * 1024)
            output = resultStream
            writer = Mp3Writer(resultStream, 128, "הקראה")
            engine.stop()
            engine.voice = voice
            engine.setSpeechRate(rate)
            engine.setPitch(pitch)
            val wav = File(cacheDir, "tts_chunk.wav")
            chunks.forEachIndexed { index, chunk ->
                val id = "export-" + System.currentTimeMillis() + "-" + index
                val wait = PendingSpeech()
                pending[id] = wait
                wav.delete()
                val result = engine.synthesizeToFile(chunk, Bundle(), wav, id)
                if (result != TextToSpeech.SUCCESS) {
                    pending.remove(id)
                    throw IOException("לא ניתן לסנתז את קטע " + (index + 1))
                }
                if (!wait.latch.await(180, TimeUnit.SECONDS) || !wait.ok) {
                    pending.remove(id)
                    throw IOException("ההקראה נכשלה בקטע " + (index + 1))
                }
                pending.remove(id)
                writer.appendWav(wav)
                wav.delete()
                val pct = ((index + 1) * 100 / chunks.size)
                runOnUiThread {
                    progressBar.progress = pct
                    showStatus("מעבד קטע " + (index + 1) + " מתוך " + chunks.size + "… " + pct + "%")
                }
            }
            writer.finish()
            output.flush()
            runOnUiThread {
                progressBar.progress = 100
                progressBar.visibility = View.GONE
                convertButton.isEnabled = true
                showStatus("ההמרה הושלמה בהצלחה. קובץ ה‑MP3 נשמר.")
            }
        } catch (e: Exception) {
            try { writer?.abort() } catch (_: Exception) {}
            try { output?.close() } catch (_: Exception) {}
            runOnUiThread {
                progressBar.visibility = View.GONE
                convertButton.isEnabled = true
                showStatus("ההמרה לא הושלמה: " + (e.message ?: "שגיאה"))
            }
        } finally {
            try { writer?.close() } catch (_: Exception) {}
            try { output?.close() } catch (_: Exception) {}
        }
    }

    private fun splitText(text: String): List<String> {
        val words = text.replace('\r', ' ').replace('\n', ' ').split(Regex("\\s+")).filter { it.isNotBlank() }
        val out = mutableListOf<String>()
        var part = StringBuilder()
        for (word in words) {
            if (part.isNotEmpty() && part.length + word.length + 1 > 1500) {
                out.add(part.toString())
                part = StringBuilder()
            }
            if (word.length > 1500) {
                var offset = 0
                while (offset < word.length) {
                    val end = minOf(offset + 1500, word.length)
                    if (part.isNotEmpty()) { out.add(part.toString()); part = StringBuilder() }
                    out.add(word.substring(offset, end))
                    offset = end
                }
            } else {
                if (part.isNotEmpty()) part.append(' ')
                part.append(word)
            }
        }
        if (part.isNotEmpty()) out.add(part.toString())
        return out
    }

    private fun speechRate(): Float = (0.60f + speedBar.progress * 0.075f).coerceIn(0.60f, 1.80f)
    private fun pitchRate(): Float = (0.70f + pitchBar.progress * 0.075f).coerceIn(0.70f, 1.60f)
    private fun showStatus(message: String) { statusView.text = message }
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun lp(top: Int = 0, bottom: Int = 0, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply { setMargins(0, dp(top), 0, dp(bottom)) }

    private fun label(parent: LinearLayout, text: String) {
        val v = TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.rgb(221, 232, 250))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, dp(7))
        }
        parent.addView(v, lp())
    }

    private fun button(text: String, primary: Boolean): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 15f
        setTextColor(if (primary) Color.rgb(7, 24, 38) else Color.rgb(238, 246, 255))
        background = roundedBackground(if (primary) Color.rgb(82, 222, 205) else Color.rgb(27, 42, 66),
            if (primary) Color.rgb(82, 222, 205) else Color.rgb(53, 75, 108))
        minHeight = dp(50)
    }

    private fun roundedBackground(fill: Int, stroke: Int): android.graphics.drawable.Drawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(fill)
            setStroke(dp(1), stroke)
        }
    }

    override fun onDestroy() {
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        super.onDestroy()
    }
}

private class Mp3Writer(private val out: OutputStream, private val bitrate: Int, private val title: String) {
    private var lame: AndroidLame? = null
    private var sampleRate: Int = 0
    private var mp3Buffer = ByteArray(64 * 1024)
    private var finished = false

    fun appendWav(file: File) {
        val b = FileInputStream(file).use { it.readBytes() }
        if (b.size < 44 || String(b, 0, 4, Charsets.US_ASCII) != "RIFF") throw IOException("קובץ WAV לא תקין")
        var channels = 1
        var rate = 22050
        var bits = 16
        var dataStart = -1
        var dataLength = 0
        var pos = 12
        while (pos + 8 <= b.size) {
            val chunk = String(b, pos, 4, Charsets.US_ASCII)
            val size = le32(b, pos + 4)
            val start = pos + 8
            if (size < 0 || start.toLong() + size > b.size.toLong()) break
            when (chunk) {
                "fmt " -> if (size >= 16) {
                    channels = le16(b, start + 2)
                    rate = le32(b, start + 4)
                    bits = le16(b, start + 14)
                }
                "data" -> {
                    dataStart = start
                    dataLength = size
                    break
                }
            }
            pos = start + size + (size and 1)
        }
        if (dataStart < 0 || dataLength <= 0 || channels !in 1..2 || bits != 16 || rate <= 0) {
            throw IOException("פורמט ה‑WAV אינו נתמך")
        }
        if (lame == null) {
            sampleRate = rate
            lame = LameBuilder().setInSampleRate(rate).setOutChannels(1).setOutSampleRate(rate)
                .setOutBitrate(bitrate).setMode(LameBuilder.Mode.MONO).setQuality(5)
                .setId3tagTitle(title.take(30)).build()
        } else if (sampleRate != rate) {
            throw IOException("קצב הדגימה השתנה בין הקטעים")
        }
        val frameBytes = channels * 2
        val frames = dataLength / frameBytes
        val mono = ShortArray(frames)
        for (i in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) {
                val p = dataStart + i * frameBytes + c * 2
                val sample = (((b[p + 1].toInt() and 0xff) shl 8) or (b[p].toInt() and 0xff)).toShort().toInt()
                sum += sample
            }
            mono[i] = (sum / channels).toShort()
        }
        mp3Buffer = ByteArray((frames * 1.25 + 7200).toInt().coerceAtLeast(16 * 1024))
        val written = lame!!.encode(mono, mono, frames, mp3Buffer)
        if (written < 0) throw IOException("קידוד MP3 נכשל: " + written)
        if (written > 0) out.write(mp3Buffer, 0, written)
    }

    fun finish() {
        if (finished) return
        finished = true
        val encoder = lame ?: throw IOException("לא הופקו נתוני שמע")
        val buffer = ByteArray(32 * 1024)
        val n = encoder.flush(buffer)
        if (n < 0) throw IOException("סגירת קידוד MP3 נכשלה")
        if (n > 0) out.write(buffer, 0, n)
        encoder.close()
        lame = null
    }

    fun abort() {
        try { lame?.close() } catch (_: Exception) {}
        lame = null
    }

    fun close() { if (!finished && lame != null) finish() }

    private fun le16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
    private fun le32(b: ByteArray, o: Int): Int = le16(b, o) or (le16(b, o + 2) shl 16)
}
