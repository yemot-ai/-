from pathlib import Path
import sys

project = Path(sys.argv[1] if len(sys.argv) > 1 else "ProCameraStudio")
app = project / "app"

def replace_once(path: Path, old: str, new: str) -> None:
    source = path.read_text(encoding="utf-8")
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one match in {path}, found {count}")
    path.write_text(source.replace(old, new, 1), encoding="utf-8")

# Android 9 is API 28.
gradle = app / "build.gradle.kts"
replace_once(gradle, "minSdk = 29", "minSdk = 28")

manifest = app / "src/main/AndroidManifest.xml"
replace_once(
    manifest,
    '    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />',
    '    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />\n'
    '    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="28" />'
)

ui = app / "src/main/java/com/procamera/studio/ui"

(ui / "MediaStoreCompat.kt").write_text('''package com.procamera.studio.ui

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** Uses scoped-storage columns on Android 10+ and the legacy DATA path on Android 9. */
internal fun createMediaContentValues(
    publicDirectory: String,
    fileName: String,
    mimeType: String
): ContentValues {
    return ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$publicDirectory/PRO CAMERA STUDIO")
        } else {
            @Suppress("DEPRECATION")
            val root = Environment.getExternalStoragePublicDirectory(publicDirectory)
            val folder = File(root, "PRO CAMERA STUDIO")
            if (!folder.exists() && !folder.mkdirs() && !folder.isDirectory) {
                throw IllegalStateException("לא ניתן ליצור תיקיית שמירה")
            }
            put(MediaStore.MediaColumns.DATA, File(folder, fileName).absolutePath)
        }
    }
}
''', encoding="utf-8")

camera = ui / "CameraScreen.kt"
replace_once(
    camera,
    '''    var hasAudioPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        hasCameraPermission = result[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        hasAudioPermission = result[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
''',
    '''    var hasAudioPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    var hasStoragePermission by remember {
        mutableStateOf(Build.VERSION.SDK_INT >= 29 || ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        hasCameraPermission = result[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        hasAudioPermission = result[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        hasStoragePermission = Build.VERSION.SDK_INT >= 29 || result[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    fun requestLegacyStoragePermissionIfNeeded(): Boolean {
        if (Build.VERSION.SDK_INT >= 29 || hasStoragePermission) return true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            hasStoragePermission = true
            return true
        }
        permissionLauncher.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
        Toast.makeText(context, "כדי לשמור תמונות וסרטונים ב-Android 9 יש לאשר גישה לאחסון", Toast.LENGTH_LONG).show()
        return false
    }
''')
replace_once(
    camera,
    '''        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, buildMediaName("jpg", nameSuffix))
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "\${Environment.DIRECTORY_PICTURES}/PRO CAMERA STUDIO")
        }
''',
    '''        val values = createMediaContentValues(
            Environment.DIRECTORY_PICTURES,
            buildMediaName("jpg", nameSuffix),
            "image/jpeg"
        )
''')
replace_once(
    camera,
    '''    fun capturePhoto() {
        val primary = refs.imagePrimary
''',
    '''    fun capturePhoto() {
        if (!requestLegacyStoragePermissionIfNeeded()) return
        val primary = refs.imagePrimary
''')
replace_once(
    camera,
    '''    fun startVideo() {
        val primary = refs.videoPrimary
''',
    '''    fun startVideo() {
        if (!requestLegacyStoragePermissionIfNeeded()) return
        val primary = refs.videoPrimary
''')
replace_once(
    camera,
    '''            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, buildMediaName("mp4", suffix))
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "\${Environment.DIRECTORY_MOVIES}/PRO CAMERA STUDIO")
            }
''',
    '''            val values = createMediaContentValues(
                Environment.DIRECTORY_MOVIES,
                buildMediaName("mp4", suffix),
                "video/mp4"
            )
''')
replace_once(
    camera,
    '''                Button(onClick = { permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }) {
''',
    '''                Button(onClick = {
                    val requested = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT <= 28) requested.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    permissionLauncher.launch(requested.toTypedArray())
                }) {
''')

gallery = ui / "GalleryScreen.kt"
replace_once(
    gallery,
    "import android.graphics.Bitmap\n",
    "import android.graphics.Bitmap\nimport android.graphics.BitmapFactory\nimport android.media.ThumbnailUtils\n"
)
replace_once(
    gallery,
    '''                        runCatching {
                            val bitmap = context.contentResolver.loadThumbnail(uri, Size(420, 420), null)
                            found.add(GalleryImage(uri, bitmap))
                        }
''',
    '''                        runCatching {
                            val bitmap = if (Build.VERSION.SDK_INT >= 29) {
                                context.contentResolver.loadThumbnail(uri, Size(420, 420), null)
                            } else {
                                val options = BitmapFactory.Options().apply { inSampleSize = 4 }
                                val decoded = context.contentResolver.openInputStream(uri)?.use { input ->
                                    BitmapFactory.decodeStream(input, null, options)
                                }
                                decoded?.let { source ->
                                    ThumbnailUtils.extractThumbnail(source, 420, 420).also { source.recycle() }
                                }
                            }
                            if (bitmap != null) found.add(GalleryImage(uri, bitmap))
                        }
''')

editor = ui / "EditorScreen.kt"
replace_once(editor, "import android.content.ContentValues\n", "import android.Manifest\nimport android.content.pm.PackageManager\n")
replace_once(editor, "import android.os.Environment\n", "import android.os.Build\nimport android.os.Environment\n")
replace_once(editor, "import androidx.compose.foundation.Image\n", "import androidx.activity.compose.rememberLauncherForActivityResult\nimport androidx.activity.result.contract.ActivityResultContracts\nimport androidx.compose.foundation.Image\n")
replace_once(editor, "import androidx.compose.ui.unit.dp\n", "import androidx.compose.ui.unit.dp\nimport androidx.core.content.ContextCompat\n")
replace_once(
    editor,
    '''    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(imageUri) {
''',
    '''    var saving by remember { mutableStateOf(false) }
    var hasLegacyStoragePermission by remember {
        mutableStateOf(Build.VERSION.SDK_INT > 28 || ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
    }
    val storagePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasLegacyStoragePermission = granted || ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    LaunchedEffect(imageUri) {
''')
replace_once(
    editor,
    '''                onClick = {
                    val bitmap = source ?: return@TextButton
                    saving = true
''',
    '''                onClick = {
                    if (Build.VERSION.SDK_INT <= 28 && !hasLegacyStoragePermission) {
                        storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        Toast.makeText(context, "כדי לשמור עותק ערוך ב-Android 9 יש לאשר גישה לאחסון", Toast.LENGTH_LONG).show()
                        return@TextButton
                    }
                    val bitmap = source ?: return@TextButton
                    saving = true
''')
replace_once(
    editor,
    '''        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "PSC_EDIT_$stamp.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "\${Environment.DIRECTORY_PICTURES}/PRO CAMERA STUDIO")
        }
''',
    '''        val contentValues = createMediaContentValues(
            Environment.DIRECTORY_PICTURES,
            "PSC_EDIT_$stamp.jpg",
            "image/jpeg"
        )
''')

print("Android 9 compatibility patch applied.")
