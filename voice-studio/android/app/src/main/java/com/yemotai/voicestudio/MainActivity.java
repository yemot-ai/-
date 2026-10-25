package com.yemotai.voicestudio;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.File;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;
import android.util.Base64;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQ_AUDIO=4420, REQ_SAVE=4421, RATE=44100;
    private WebView webView;
    private File recordingsDir, recordingFile;
    private MediaRecorder recorder;
    private MediaPlayer player;
    private boolean pendingRecord=false, pendingPitch=false;
    private volatile boolean pitchRunning=false;
    private AudioRecord audioRecord;
    private Thread pitchThread;
    private byte[] pendingBytes;
    private String pendingName="voice-studio-file", pendingMime="application/octet-stream";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(android.graphics.Color.rgb(11,18,34));
        getWindow().setNavigationBarColor(android.graphics.Color.rgb(11,18,34));
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        webView=new WebView(this);
        webView.setBackgroundColor(android.graphics.Color.rgb(11,18,34));
        WebSettings s=webView.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true); s.setAllowContentAccess(true); s.setMediaPlaybackRequiresUserGesture(false);
        webView.setWebViewClient(new WebViewClient()); webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new Bridge(),"AndroidBridge");
        setContentView(webView); webView.loadUrl("file:///android_asset/index.html");
        recordingsDir=new File(getFilesDir(),"voice-recordings"); if(!recordingsDir.exists()) recordingsDir.mkdirs();
    }
    private boolean hasMic(){return Build.VERSION.SDK_INT<23||checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;}
    private void ensureMic(boolean rec){
        if(hasMic()){if(rec)beginRecording();else beginPitch();return;}
        if(rec)pendingRecord=true;else pendingPitch=true;
        if(Build.VERSION.SDK_INT>=23)requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},REQ_AUDIO);
    }
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results){
        super.onRequestPermissionsResult(requestCode,permissions,results); if(requestCode!=REQ_AUDIO)return;
        boolean granted=results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED, r=pendingRecord, p=pendingPitch; pendingRecord=false;pendingPitch=false;
        if(!granted){message("לא ניתנה הרשאת מיקרופון. אפשר להשתמש בשאר התכונות.");recordState(false,null);return;}
        if(r)beginRecording();if(p)beginPitch();
    }
    private void js(String code){runOnUiThread(()->{if(webView!=null)webView.evaluateJavascript(code,null);});}
    private void message(String text){js("window.onNativeMessage&&window.onNativeMessage("+JSONObject.quote(text)+");");}
    private void recordState(boolean on,String name){js("window.onNativeRecordingState&&window.onNativeRecordingState("+(on?"true":"false")+","+(name==null?"null":JSONObject.quote(name))+");");}
    private void releaseRecorder(){if(recorder!=null){try{recorder.reset();}catch(Exception ignored){}try{recorder.release();}catch(Exception ignored){}recorder=null;}}
    private void beginRecording(){runOnUiThread(()->{
        try{
            if(!hasMic()){pendingRecord=true;ensureMic(true);return;} if(recorder!=null)return;
            if(!recordingsDir.exists())recordingsDir.mkdirs();
            String stamp=new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(new Date());
            recordingFile=new File(recordingsDir,"Voice-"+stamp+".m4a");
            MediaRecorder r=new MediaRecorder();r.setAudioSource(MediaRecorder.AudioSource.MIC);r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);r.setAudioSamplingRate(RATE);r.setAudioEncodingBitRate(128000);r.setOutputFile(recordingFile.getAbsolutePath());r.prepare();r.start();recorder=r;recordState(true,recordingFile.getName());
        }catch(Exception e){releaseRecorder();if(recordingFile!=null&&recordingFile.exists())recordingFile.delete();recordingFile=null;message("לא ניתן להתחיל הקלטה. בדוק את המיקרופון ונסה שוב.");recordState(false,null);}
    });}
    private void finishRecording(){runOnUiThread(()->{
        String name=null;try{if(recorder!=null){recorder.stop();if(recordingFile!=null&&recordingFile.exists()&&recordingFile.length()>512)name=recordingFile.getName();}}catch(Exception e){if(recordingFile!=null&&recordingFile.exists())recordingFile.delete();}finally{releaseRecorder();recordingFile=null;recordState(false,name);listRecordings();}
    });}
    private void listRecordings(){
        try{JSONArray arr=new JSONArray();File[] fs=recordingsDir.listFiles();if(fs!=null){Arrays.sort(fs,(a,b)->Long.compare(b.lastModified(),a.lastModified()));for(File f:fs){if(!f.isFile()||f.length()<512)continue;JSONObject o=new JSONObject();o.put("name",f.getName());o.put("id",f.getName());o.put("size",f.length());o.put("date",new SimpleDateFormat("dd/MM/yyyy HH:mm",new Locale("he","IL")).format(new Date(f.lastModified())));arr.put(o);}}js("window.onNativeRecordings&&window.onNativeRecordings("+arr.toString()+");");}catch(Exception e){js("window.onNativeRecordings&&window.onNativeRecordings([]);");}
    }
    private File safeFile(String n){if(n==null||n.contains("/")||n.contains("\\")||n.contains(".."))return null;File f=new File(recordingsDir,n);try{if(!f.getCanonicalFile().getParentFile().equals(recordingsDir.getCanonicalFile()))return null;}catch(Exception e){return null;}return f;}
    private void playFile(String n){runOnUiThread(()->{try{File f=safeFile(n);if(f==null||!f.exists()){message("ההקלטה לא נמצאה.");return;}stopPlayer();player=new MediaPlayer();player.setDataSource(f.getAbsolutePath());player.setOnPreparedListener(mp->{mp.start();js("window.onNativePlaybackState&&window.onNativePlaybackState(true);");});player.setOnCompletionListener(mp->{stopPlayer();js("window.onNativePlaybackState&&window.onNativePlaybackState(false);");});player.setOnErrorListener((mp,w,x)->{stopPlayer();message("לא ניתן לנגן את ההקלטה.");return true;});player.prepareAsync();}catch(Exception e){stopPlayer();message("לא ניתן לפתוח את ההקלטה.");}});}
    private void stopPlayer(){if(player!=null){try{if(player.isPlaying())player.stop();}catch(Exception ignored){}try{player.reset();player.release();}catch(Exception ignored){}player=null;}}
    private void deleteFile(String n){runOnUiThread(()->{File f=safeFile(n);if(f!=null&&f.exists())f.delete();listRecordings();});}
    private void beginPitch(){
        if(!hasMic()){ensureMic(false);return;}stopPitch();
        try{int min=AudioRecord.getMinBufferSize(RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);audioRecord=new AudioRecord(MediaRecorder.AudioSource.MIC,RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min,8192));if(audioRecord.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException();audioRecord.startRecording();pitchRunning=true;pitchThread=new Thread(this::pitchLoop,"VoiceStudioPitch");pitchThread.start();}
        catch(Exception e){stopPitch();message("לא ניתן להפעיל כוונון. ייתכן שהמיקרופון בשימוש.");}
    }
    private void pitchLoop(){
        short[] data=new short[4096];long last=0;
        while(pitchRunning&&audioRecord!=null){int n;try{n=audioRecord.read(data,0,data.length);}catch(Exception e){break;}if(n<2048)continue;
            double sq=0;for(int i=0;i<n;i++){double v=data[i]/32768.0;sq+=v*v;}double rms=Math.sqrt(sq/n),freq=rms>.008?estimate(data,n):0;
            long now=System.currentTimeMillis();if(now-last>130){final double f=freq,l=rms;js("window.onNativePitch&&window.onNativePitch("+f+","+l+");");last=now;}
        }
    }
    private double estimate(short[] x,int len){
        int min=RATE/1000,max=Math.min(RATE/65,len/2),limit=Math.min(len-max,1800),bestLag=0;double best=-1;
        for(int lag=min;lag<=max;lag++){double c=0,a2=0,b2=0;for(int i=0;i<limit;i+=2){double a=x[i]/32768.0,b=x[i+lag]/32768.0;c+=a*b;a2+=a*a;b2+=b*b;}double v=c/Math.sqrt(a2*b2+1e-12);if(v>best){best=v;bestLag=lag;}}
        if(bestLag==0||best<.30)return 0;double lag=bestLag;
        if(bestLag>min&&bestLag<max){double a=corr(x,limit,bestLag-1),b=corr(x,limit,bestLag),c=corr(x,limit,bestLag+1),d=a-2*b+c;if(Math.abs(d)>1e-8)lag+=.5*(a-c)/d;}
        double f=RATE/lag;return f>=60&&f<=1100?f:0;
    }
    private double corr(short[] x,int limit,int lag){double c=0,a2=0,b2=0;for(int i=0;i<limit;i+=2){double a=x[i]/32768.0,b=x[i+lag]/32768.0;c+=a*b;a2+=a*a;b2+=b*b;}return c/Math.sqrt(a2*b2+1e-12);}
    private void stopPitch(){
        pitchRunning=false;AudioRecord ar=audioRecord;audioRecord=null;
        if(ar!=null){try{ar.stop();}catch(Exception ignored){}try{ar.release();}catch(Exception ignored){}}
        Thread t=pitchThread;if(t!=null)t.interrupt();pitchThread=null;
    }
    public final class Bridge{
        @JavascriptInterface public void startRecording(){runOnUiThread(()->ensureMic(true));}
        @JavascriptInterface public void stopRecording(){finishRecording();}
        @JavascriptInterface public void requestRecordings(){runOnUiThread(()->listRecordings());}
        @JavascriptInterface public void playRecording(String name){playFile(name);}
        @JavascriptInterface public void stopPlayback(){runOnUiThread(()->stopPlayer());}
        @JavascriptInterface public void deleteRecording(String name){deleteFile(name);}
        @JavascriptInterface public void startPitch(){runOnUiThread(()->ensureMic(false));}
        @JavascriptInterface public void stopPitch(){runOnUiThread(()->stopPitch());}
        @JavascriptInterface public void saveFile(String name,String mime,String encoded){runOnUiThread(()->{try{pendingName=(name==null||name.isEmpty())?"voice-studio-file":name.replaceAll("[^A-Za-z0-9._-]","_");pendingMime=(mime==null||mime.isEmpty())?"application/octet-stream":mime;pendingBytes=Base64.decode(encoded,Base64.DEFAULT);Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType(pendingMime);i.putExtra(Intent.EXTRA_TITLE,pendingName);startActivityForResult(i,REQ_SAVE);}catch(Exception e){message("לא ניתן להכין קובץ לשמירה.");}});}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==REQ_SAVE){if(result==RESULT_OK&&data!=null&&data.getData()!=null&&pendingBytes!=null){try(OutputStream out=getContentResolver().openOutputStream(data.getData())){if(out!=null){out.write(pendingBytes);out.flush();message("הקובץ נשמר.");}}catch(Exception e){message("השמירה נכשלה.");}}pendingBytes=null;}}
    @Override protected void onPause(){stopPitch();super.onPause();}
    @Override protected void onDestroy(){stopPitch();releaseRecorder();stopPlayer();if(webView!=null){webView.removeJavascriptInterface("AndroidBridge");webView.destroy();}super.onDestroy();}
}
