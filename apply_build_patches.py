from pathlib import Path
import sys

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()

def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if new in text:
        print(f"SKIP already patched: {label}")
        return
    if old not in text:
        raise SystemExit(f"PATCH FAILED: {label}: source fragment not found in {path}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
    print(f"PATCHED: {label}")

js = ROOT / "app.js"
replace_once(
    js,
    "function downloadBlob(blob,name){const url=URL.createObjectURL(blob);const a=document.createElement('a');a.href=url;a.download=name;document.body.appendChild(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(url),30000);}",
    """async function downloadBlob(blob,name){
    const native=window.CUTLABNative;
    if(native&&typeof native.requestSave==='function'&&typeof native.writeChunk==='function'&&typeof native.finishSave==='function'){
      const saved=await new Promise(resolve=>{
        let settled=false;
        window.__cutlabNativeSaveReady=async ready=>{
          if(settled)return;settled=true;window.__cutlabNativeSaveReady=null;
          if(!ready){resolve(false);return;}
          try{
            const chunkSize=256*1024;
            for(let offset=0;offset<blob.size;offset+=chunkSize){
              const bytes=new Uint8Array(await blob.slice(offset,offset+chunkSize).arrayBuffer());
              let binary='';for(let i=0;i<bytes.length;i++)binary+=String.fromCharCode(bytes[i]);
              if(!native.writeChunk(btoa(binary)))throw new Error('הכתיבה לקובץ נכשלה');
            }
            native.finishSave(true);resolve(true);
          }catch(error){try{native.finishSave(false);}catch{}notify(error?.message||'לא ניתן לשמור את הקובץ');resolve(false);}
        };
        try{native.requestSave(name,blob.type||'application/octet-stream');}catch(error){window.__cutlabNativeSaveReady=null;resolve(false);}
      });
      if(saved)notify('הקובץ נשמר במכשיר');else notify('השמירה בוטלה או נכשלה');
      return saved;
    }
    const url=URL.createObjectURL(blob);const a=document.createElement('a');a.href=url;a.download=name;document.body.appendChild(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(url),30000);return true;
  }""",
    "Android-native file saving bridge",
)
replace_once(
    js,
    "downloadBlob(blob,(state.projectName||'cutlab-project').replace(/[^\\w\\u0590-\\u05FF-]+/g,'_')+'.cutproj');notify('קובץ הפרויקט נשמר, כולל קובצי המדיה');",
    "const saved=await downloadBlob(blob,(state.projectName||'cutlab-project').replace(/[^\\w\\u0590-\\u05FF-]+/g,'_')+'.cutproj');if(saved)notify('קובץ הפרויקט נשמר, כולל קובצי המדיה');",
    "wait for project file save",
)
replace_once(
    js,
    "downloadBlob(blobResult,'CUTLAB-'+tag+'.'+ext);$('exportTitle').textContent='הייצוא הושלם ✓';",
    "const saved=await downloadBlob(blobResult,'CUTLAB-'+tag+'.'+ext);if(!saved)throw new Error('השמירה בוטלה או נכשלה');$('exportTitle').textContent='הייצוא הושלם ✓';",
    "wait for exported video save",
)

kt = ROOT / "android/app/src/main/java/com/yby/cutlab/MainActivity.kt"
replace_once(
    kt,
    "import android.net.Uri\nimport android.os.Bundle\nimport android.view.View\nimport android.webkit.ValueCallback",
    "import android.net.Uri\nimport android.os.Bundle\nimport android.util.Base64\nimport android.view.View\nimport android.webkit.JavascriptInterface\nimport android.webkit.ValueCallback",
    "Android save bridge imports",
)
replace_once(
    kt,
    "import android.widget.FrameLayout\n\nclass MainActivity : Activity() {\n    private lateinit var webView: WebView\n    private var fileCallback: ValueCallback<Array<Uri>>? = null\n    private val fileRequestCode = 4107",
    """import android.widget.FrameLayout
import java.io.OutputStream

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val fileRequestCode = 4107
    private val saveRequestCode = 4108
    private val saveLock = Any()
    private var saveStream: OutputStream? = null
    private var pendingSaveName = "CUTLAB-export.bin"
    private var pendingSaveMime = "application/octet-stream"

    private inner class CutlabBridge {
        @JavascriptInterface
        fun requestSave(name: String, mimeType: String) {
            runOnUiThread { openSaveDialog(name, mimeType) }
        }

        @JavascriptInterface
        fun writeChunk(encodedChunk: String): Boolean = synchronized(saveLock) {
            val output = saveStream ?: return@synchronized false
            return@synchronized try {
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

    private fun openSaveDialog(name: String, mimeType: String) {
        pendingSaveName = name.substringAfterLast('/').substringAfterLast('\\\\')
            .replace(Regex("[\\\\r\\\\n\\\\u0000]"), "_").take(180).ifBlank { "CUTLAB-export.bin" }
        pendingSaveMime = mimeType.takeIf {
            it.matches(Regex("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+"))
        } ?: "application/octet-stream"
        synchronized(saveLock) {
            try { saveStream?.close() } catch (_: Exception) { }
            saveStream = null
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = pendingSaveMime
            putExtra(Intent.EXTRA_TITLE, pendingSaveName)
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
                webView.evaluateJavascript("if(window.__cutlabNativeSaveReady){window.__cutlabNativeSaveReady($ready);}", null)
            }
        }
    }""",
    "Android SAF chunked save bridge",
)
replace_once(
    kt,
    "        webView.webChromeClient = object : WebChromeClient() {",
    "        webView.addJavascriptInterface(CutlabBridge(), \"CUTLABNative\")\n        webView.webChromeClient = object : WebChromeClient() {",
    "register Android JavaScript bridge",
)
replace_once(
    kt,
    "    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {\n        super.onActivityResult(requestCode, resultCode, data)\n        if (requestCode == fileRequestCode) {",
    """    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == saveRequestCode) {
            val uri = if (resultCode == RESULT_OK) data?.data else null
            if (uri == null) {
                notifySaveReady(false)
            } else {
                try {
                    val output = contentResolver.openOutputStream(uri, "w")
                    synchronized(saveLock) { saveStream = output }
                    notifySaveReady(output != null)
                } catch (_: Exception) {
                    synchronized(saveLock) { saveStream = null }
                    notifySaveReady(false)
                }
            }
            return
        }
        if (requestCode == fileRequestCode) {""",
    "Android save document result",
)
replace_once(
    kt,
    "    override fun onDestroy() {\n        fileCallback?.onReceiveValue(null)\n        fileCallback = null\n        if (::webView.isInitialized) { webView.stopLoading(); webView.destroy() }\n        super.onDestroy()\n    }",
    """    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        synchronized(saveLock) {
            try { saveStream?.close() } catch (_: Exception) { }
            saveStream = null
        }
        if (::webView.isInitialized) { webView.stopLoading(); webView.destroy() }
        super.onDestroy()
    }""",
    "close active save when app closes",
)

print("All Android output patches applied.")
