package com.yemotai.videostudio

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.Composition
import androidx.media3.ui.PlayerView
import java.io.File
import java.io.FileInputStream

class MainActivity : ComponentActivity() {
    private var pendingExportFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VideoStudioScreen() }
    }

    @Composable
    private fun VideoStudioScreen() {
        val context = LocalContext.current
        var videoUri by remember { mutableStateOf<Uri?>(null) }
        var durationMs by remember { mutableStateOf(0L) }
        var startFraction by remember { mutableFloatStateOf(0f) }
        var endFraction by remember { mutableFloatStateOf(1f) }
        var muted by remember { mutableStateOf(false) }
        var exporting by remember { mutableStateOf(false) }
        var exportReady by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf("בחר סרטון כדי להתחיל לערוך") }
        var outputFile by remember { mutableStateOf<File?>(null) }
        var player by remember { mutableStateOf<ExoPlayer?>(null) }

        val chooseVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) { }
                videoUri = uri
                startFraction = 0f
                endFraction = 1f
                exportReady = false
                status = "הסרטון נטען. גרור את המחוונים כדי לבחור קטע."
            }
        }

        val saveExport = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("video/mp4")
        ) { destination ->
            val source = outputFile
            if (destination != null && source != null && source.exists()) {
                try {
                    contentResolver.openOutputStream(destination)?.use { out ->
                        FileInputStream(source).use { input -> input.copyTo(out) }
                    }
                    status = "הסרטון נשמר בהצלחה"
                    Toast.makeText(context, "הייצוא נשמר", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    status = "שמירת הקובץ נכשלה: ${e.localizedMessage ?: "שגיאה"}"
                }
            }
        }

        LaunchedEffect(videoUri) {
            player?.release()
            player = null
            durationMs = 0L
            val uri = videoUri ?: return@LaunchedEffect
            val exo = ExoPlayer.Builder(context).build()
            exo.setMediaItem(MediaItem.fromUri(uri))
            exo.prepare()
            exo.playWhenReady = false
            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        durationMs = exo.duration.coerceAtLeast(0L)
                    }
                }
            })
            player = exo
        }

        DisposableEffect(Unit) {
            onDispose { player?.release() }
        }

        val exportVideo: () -> Unit = {
            val uri = videoUri
            if (uri == null) {
                status = "בחר סרטון תחילה"
            } else if (durationMs <= 0L) {
                status = "לא ניתן לקרוא את משך הסרטון. נסה קובץ אחר."
            } else {
                exporting = true
                exportReady = false
                status = "מכין את הסרטון לייצוא…"
                val startMs = (durationMs * startFraction).toLong().coerceIn(0L, durationMs - 1L)
                val endMs = (durationMs * endFraction).toLong().coerceIn(startMs + 1L, durationMs)
                val destination = File(cacheDir, "video-studio-export-${System.currentTimeMillis()}.mp4")
                outputFile = destination
                val clip = MediaItem.Builder()
                    .setUri(uri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(startMs)
                            .setEndPositionMs(endMs)
                            .build()
                    )
                    .build()
                val editedItem = EditedMediaItem.Builder(clip)
                    .setRemoveAudio(muted)
                    .build()
                val transformer = Transformer.Builder(this)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            exporting = false
                            exportReady = true
                            status = "הייצוא הושלם. אפשר לשמור את קובץ ה-MP4 במכשיר."
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            exporting = false
                            status = "הייצוא נכשל: ${exportException.message ?: "שגיאה לא ידועה"}"
                        }
                    })
                    .build()
                try {
                    transformer.start(editedItem, destination.absolutePath)
                } catch (e: Exception) {
                    exporting = false
                    status = "לא ניתן להתחיל ייצוא: ${e.localizedMessage ?: "שגיאה"}"
                }
            }
        }

        MaterialTheme(colorScheme = darkColorScheme(
            primary = Color(0xFF7C5CFF),
            secondary = Color(0xFF35D0BA),
            background = Color(0xFF10121A),
            surface = Color(0xFF1B1E2B)
        )) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("סטודיו וידאו", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                        Text("עריכת וידאו פשוטה — ישירות במכשיר", color = Color(0xFFB9BED1))
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1E2B))
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.Icon(Icons.Default.VideoLibrary, contentDescription = null, tint = Color(0xFF35D0BA))
                                Spacer(Modifier.width(8.dp))
                                Text("תצוגה מקדימה", style = MaterialTheme.typography.titleMedium, color = Color.White)
                            }
                            Box(
                                modifier = Modifier.fillMaxWidth().height(210.dp).background(Color.Black, RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (player != null) {
                                    AndroidView(
                                        modifier = Modifier.fillMaxSize(),
                                        factory = { viewContext ->
                                            PlayerView(viewContext).apply {
                                                this.player = player
                                                useController = true
                                            }
                                        },
                                        update = { it.player = player }
                                    )
                                } else {
                                    Text("הסרטון שלך יופיע כאן", color = Color(0xFF8D93A8))
                                }
                            }
                            Button(onClick = { chooseVideo.launch(arrayOf("video/*")) }, modifier = Modifier.fillMaxWidth()) {
                                androidx.compose.material3.Icon(Icons.Default.VideoLibrary, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("בחירת סרטון")
                            }
                        }
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1E2B))
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.material3.Icon(Icons.Default.ContentCut, contentDescription = null, tint = Color(0xFF7C5CFF))
                                Spacer(Modifier.width(8.dp))
                                Text("חיתוך הקטע", style = MaterialTheme.typography.titleMedium, color = Color.White)
                            }
                            Text("התחלה: ${formatTime((durationMs * startFraction).toLong())}", color = Color(0xFFCED2E2))
                            Slider(
                                value = startFraction,
                                onValueChange = { value -> startFraction = value.coerceAtMost((endFraction - 0.02f).coerceAtLeast(0f)) },
                                enabled = videoUri != null && durationMs > 1000L,
                                valueRange = 0f..1f
                            )
                            Text("סיום: ${formatTime((durationMs * endFraction).toLong())}", color = Color(0xFFCED2E2))
                            Slider(
                                value = endFraction,
                                onValueChange = { value -> endFraction = value.coerceAtLeast((startFraction + 0.02f).coerceAtMost(1f)) },
                                enabled = videoUri != null && durationMs > 1000L,
                                valueRange = 0f..1f
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = muted, onCheckedChange = { muted = it })
                                androidx.compose.material3.Icon(Icons.Default.MusicOff, contentDescription = null, tint = Color(0xFF35D0BA))
                                Spacer(Modifier.width(6.dp))
                                Text("השתקת השמע בייצוא", color = Color.White)
                            }
                            Text("משך מקור: ${formatTime(durationMs)}", color = Color(0xFF9DA4BB))
                        }
                    }

                    Button(
                        onClick = exportVideo,
                        enabled = videoUri != null && !exporting,
                        modifier = Modifier.fillMaxWidth().height(54.dp)
                    ) {
                        if (exporting) {
                            CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("מייצא…")
                        } else {
                            androidx.compose.material3.Icon(Icons.Default.ContentCut, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("ייצוא קטע כ־MP4")
                        }
                    }

                    if (exportReady) {
                        OutlinedButton(
                            onClick = { saveExport.launch("הסרטון-שלי.mp4") },
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                        ) {
                            androidx.compose.material3.Icon(Icons.Default.FileDownload, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("שמירת MP4 במכשיר")
                        }
                    }

                    Text(status, color = if (status.contains("נכשלה") || status.contains("נכשל")) Color(0xFFFF8A8A) else Color(0xFFB9BED1))
                    Text(
                        "הערה: זו גרסה ראשונית. היא תומכת בבחירת סרטון, תצוגה מקדימה, חיתוך בסיסי וייצוא MP4. עדיין אין בה שכבות מרובות, מעברים או אפקטים מתקדמים.",
                        color = Color(0xFF8189A2),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }

    private fun formatTime(milliseconds: Long): String {
        val totalSeconds = (milliseconds / 1000L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return "%02d:%02d".format(minutes, seconds)
    }
}
