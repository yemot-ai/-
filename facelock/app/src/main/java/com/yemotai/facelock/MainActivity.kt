package com.yemotai.facelock

import android.Manifest
import android.app.*
import android.app.admin.DeviceAdminReceiver
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); render() }
    override fun onResume() { super.onResume(); if (contentView != null) render() }
    private var contentView: ScrollView? = null

    private fun render() {
        window.statusBarColor = Color.rgb(8,18,38)
        window.navigationBarColor = Color.rgb(8,18,38)
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.rgb(8,18,38)); isFillViewport = true }
        contentView = scroll
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20),dp(26),dp(20),dp(30)) }
        scroll.addView(root)
        root.addView(label("SECURE ACCESS  •  ANDROID 10+",11f,Color.rgb(69,225,208),true))
        val title = label("FaceLock Pro",34f,Color.WHITE,true); title.setPadding(0,dp(8),0,dp(4)); root.addView(title)
        root.addView(label("הגנה חכמה על המכשיר ועל האפליקציות החשובות שלך",15f,Color.rgb(186,202,222),false))

        val status = card()
        status.addView(label("מצב האבטחה",18f,Color.WHITE,true))
        val biometric = BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
        val secure = isDeviceSecure()
        status.addView(label((if (biometric) "● ביומטריה זמינה במערכת" else "● ביומטריה אינה זמינה כרגע") + "\n" +
            (if (secure) "● קוד / תבנית גיבוי מוגדרים" else "● חסר קוד / תבנית גיבוי"),
            14f,if (biometric && secure) Color.rgb(105,232,190) else Color.rgb(255,191,105),false))
        status.addView(label("האימות מתבצע ב-Android. האפליקציה אינה שומרת תמונות פנים.",12f,Color.rgb(171,188,211),false))
        root.addView(status)

        addButton(root,"🔒  נעל את המכשיר עכשיו",true) { requestDeviceLock() }
        addButton(root,"◉  בדוק אימות פנים / טביעת אצבע",false) { openAuthTest() }
        addButton(root,"＋  הוסף או נהל פנים בהגדרות Android",false) { openBiometricSettings() }

        val appCard = card()
        appCard.addView(label("נעילת אפליקציות",19f,Color.WHITE,true))
        appCard.addView(label("בחר אפליקציות שידרשו אימות לפני הכניסה. ההגנה פועלת רק לאחר הפעלה מפורשת.",13f,Color.rgb(184,201,221),false))
        appCard.addView(label("אפליקציות שנבחרו: " + protectedPackages().size,14f,Color.rgb(69,225,208),true))
        root.addView(appCard)
        addButton(root,"בחר אפליקציות להגנה",true) { showAppPicker() }
        addButton(root,"אישור גישה לנתוני שימוש",false) { openUsageSettings() }
        addButton(root,"אישור הצגה מעל אפליקציות",false) { openOverlaySettings() }
        addButton(root,"הפעל הגנת אפליקציות",true) { startProtection() }
        addButton(root,"עצור הגנת אפליקציות",false) { stopService(Intent(this,AppLockMonitorService::class.java)); toast("הגנת האפליקציות נעצרה") }

        val note = card()
        note.addView(label("חשוב לדעת",17f,Color.WHITE,true))
        note.addView(label("• נעילת המכשיר משתמשת במסך הנעילה המקורי של Android.\n" +
            "• ריבוי פנים, אם קיים, מנוהל בהגדרות Android ולא מתוך FaceLock.\n" +
            "• נעילת אפליקציות תלויה בהרשאות נתוני שימוש והצגה מעל אפליקציות.\n" +
            "• מומלץ לא להגן על הגדרות, מסך הבית או אפליקציות טלפון חיוניות.",
            12f,Color.rgb(184,201,221),false))
        root.addView(note)
        root.addView(label("FACELOCK PRO  •  LOCAL-FIRST  •  NO FACE PHOTOS STORED",10f,Color.rgb(104,127,153),true))
        setContentView(scroll)
    }

    private fun label(value:String,size:Float,color:Int,bold:Boolean) = TextView(this).apply {
        text=value; textSize=size; setTextColor(color); if (bold) typeface=Typeface.DEFAULT_BOLD
        textDirection=View.TEXT_DIRECTION_FIRST_STRONG; setPadding(0,dp(6),0,dp(7))
    }
    private fun card() = LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL; setPadding(dp(17),dp(14),dp(17),dp(14))
        background=GradientDrawable().apply { setColor(Color.rgb(17,34,58)); cornerRadius=dp(20).toFloat(); setStroke(dp(1),Color.rgb(34,62,88)) }
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(18); bottomMargin=dp(8) }
    }
    private fun addButton(parent:LinearLayout,title:String,primary:Boolean,action:()->Unit) {
        val v=TextView(this).apply {
            text=title; textSize=15f; typeface=Typeface.DEFAULT_BOLD; gravity=Gravity.CENTER
            setTextColor(if(primary) Color.rgb(7,16,31) else Color.WHITE); setPadding(dp(14),dp(15),dp(14),dp(15))
            background=GradientDrawable().apply { setColor(if(primary) Color.rgb(69,225,208) else Color.rgb(24,45,70)); cornerRadius=dp(16).toFloat(); if(!primary)setStroke(dp(1),Color.rgb(44,73,101)) }
            isClickable=true; isFocusable=true; setOnClickListener { action() }
        }
        parent.addView(v,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(9) })
    }

    private fun showAppPicker() {
        val apps=launchableApps()
        if(apps.isEmpty()){toast("לא נמצאו אפליקציות לבחירה");return}
        val selected=protectedPackages().toMutableSet()
        val names=apps.map{it.first}.toTypedArray()
        val checked=BooleanArray(apps.size){selected.contains(apps[it].second)}
        AlertDialog.Builder(this).setTitle("בחר אפליקציות מוגנות")
            .setMultiChoiceItems(names,checked,DialogInterface.OnMultiChoiceClickListener{_,which,on->if(on)selected.add(apps[which].second) else selected.remove(apps[which].second)})
            .setPositiveButton("שמור"){_,_->getSharedPreferences(PREFS,MODE_PRIVATE).edit().putStringSet(KEY_PROTECTED_PACKAGES,selected.toSet()).apply();toast("נשמרו "+selected.size+" אפליקציות");render()}
            .setNegativeButton("ביטול",null).show()
    }
    private fun launchableApps():List<Pair<String,String>> {
        val intent=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches:List<ResolveInfo>=packageManager.queryIntentActivities(intent,0)
        val home=packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),0)?.activityInfo?.packageName
        val excluded=setOf(packageName,"com.android.settings","com.android.systemui","com.android.phone","com.android.dialer","com.android.emergency")
        val unique=LinkedHashMap<String,String>()
        for(item in matches){val pkg=item.activityInfo.packageName;if(pkg !in excluded && pkg!=home && !unique.containsKey(pkg))unique[pkg]=item.loadLabel(packageManager)?.toString()?:pkg}
        return unique.map{Pair(it.value,it.key)}.sortedBy{it.first.lowercase()}
    }
    private fun protectedPackages():Set<String> = getSharedPreferences(PREFS,MODE_PRIVATE).getStringSet(KEY_PROTECTED_PACKAGES,emptySet())?.toSet()?:emptySet()

    private fun requestDeviceLock() {
        if(!isDeviceSecure()){toast("הגדר תחילה קוד או תבנית ב-Android כדי שתמיד תהיה דרך לפתוח את המכשיר");startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS));return}
        val dpm=getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin=ComponentName(this,FaceLockAdminReceiver::class.java)
        if(dpm.isAdminActive(admin)) dpm.lockNow() else {
            val ask=Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN,admin)
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,"FaceLock מבקש הרשאה להפעיל את מסך הנעילה המאובטח של Android.")
            startActivityForResult(ask,REQ_ADMIN)
        }
    }
    @Deprecated("legacy activity result")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){super.onActivityResult(requestCode,resultCode,data);if(requestCode==REQ_ADMIN){val dpm=getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager;val admin=ComponentName(this,FaceLockAdminReceiver::class.java);if(dpm.isAdminActive(admin)&&isDeviceSecure())dpm.lockNow() else toast("לא הופעלה הרשאת נעילה. המכשיר לא ננעל.")}}
    private fun openAuthTest(){if(!isDeviceSecure()){toast("הגדר תחילה קוד או תבנית גיבוי");startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS));return};startActivity(Intent(this,BiometricAuthActivity::class.java))}
    private fun openBiometricSettings(){try{val i=if(Build.VERSION.SDK_INT>=30)Intent(Settings.ACTION_BIOMETRIC_ENROLL).putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL) else Intent(Settings.ACTION_SECURITY_SETTINGS);startActivity(i)}catch(_:Exception){startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))}}
    private fun openUsageSettings(){try{startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))}catch(_:Exception){toast("לא ניתן לפתוח את הגדרות נתוני השימוש")}}
    private fun openOverlaySettings(){try{startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+packageName)))}catch(_:Exception){toast("לא ניתן לפתוח את הגדרות ההצגה מעל אפליקציות")}}
    private fun startProtection(){
        if(!isDeviceSecure()){toast("הגדר תחילה קוד או תבנית במכשיר");startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS));return}
        if(protectedPackages().isEmpty()){toast("בחר תחילה לפחות אפליקציה אחת");return}
        if(!hasUsageAccess(this)){toast("אשר תחילה גישה לנתוני שימוש");openUsageSettings();return}
        if(!Settings.canDrawOverlays(this)){toast("אשר תחילה הצגה מעל אפליקציות");openOverlaySettings();return}
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),REQ_NOTIFICATIONS);return}
        val i=Intent(this,AppLockMonitorService::class.java);if(Build.VERSION.SDK_INT>=26)startForegroundService(i) else startService(i);toast("הגנת האפליקציות הופעלה")
    }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray){super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==REQ_NOTIFICATIONS){if(grantResults.isNotEmpty()&&grantResults[0]==PackageManager.PERMISSION_GRANTED)startProtection() else toast("התראת השירות לא אושרה")}}
    private fun isDeviceSecure()=(getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_LONG).show()
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()

    companion object {
        const val PREFS="facelock_settings"
        const val KEY_PROTECTED_PACKAGES="protected_packages"
        private const val REQ_ADMIN=5101
        private const val REQ_NOTIFICATIONS=5102
        fun hasUsageAccess(context:Context):Boolean=try{val ops=context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager;ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,Process.myUid(),context.packageName)==AppOpsManager.MODE_ALLOWED}catch(_:Exception){false}
    }
}

class BiometricAuthActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        val keyguard=getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if(!keyguard.isDeviceSecure){Toast.makeText(this,"הגדר קוד או תבנית ב-Android",Toast.LENGTH_LONG).show();finish();return}
        val target=intent.getStringExtra(EXTRA_TARGET)
        val callback=object:BiometricPrompt.AuthenticationCallback(){
            override fun onAuthenticationSucceeded(result:BiometricPrompt.AuthenticationResult){super.onAuthenticationSucceeded(result);if(!target.isNullOrBlank())LockSession.allow(target);Toast.makeText(this@BiometricAuthActivity,if(target.isNullOrBlank())"האימות הצליח" else "האפליקציה נפתחה",Toast.LENGTH_SHORT).show();setResult(RESULT_OK);finish()}
            override fun onAuthenticationError(code:Int,message:CharSequence){super.onAuthenticationError(code,message);if(code!=BiometricPrompt.ERROR_USER_CANCELED&&code!=BiometricPrompt.ERROR_CANCELED&&code!=BiometricPrompt.ERROR_NEGATIVE_BUTTON)Toast.makeText(this@BiometricAuthActivity,message,Toast.LENGTH_LONG).show();finish()}
        }
        val prompt=BiometricPrompt(this,ContextCompat.getMainExecutor(this),callback)
        val info=BiometricPrompt.PromptInfo.Builder().setTitle("FaceLock Pro").setSubtitle("אימות באמצעות הביומטריה של Android")
            .setDescription("זיהוי פנים, טביעת אצבע או קוד המכשיר — לפי התמיכה במכשיר.")
            .setConfirmationRequired(false)
        if(Build.VERSION.SDK_INT>=30)info.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL) else info.setDeviceCredentialAllowed(true)
        prompt.authenticate(info.build())
    }
    companion object{const val EXTRA_TARGET="target_package"}
}

object LockSession{@Volatile var authorizedPackage:String?=null;fun allow(p:String){authorizedPackage=p};fun clear(){authorizedPackage=null}}

class FaceLockAdminReceiver:DeviceAdminReceiver()

class AppLockMonitorService:Service(){
    private val handler=Handler(Looper.getMainLooper())
    private var wm:WindowManager?=null
    private var overlay:LinearLayout?=null
    private var target:String?=null
    private var lastTime=0L
    private var lastPackage:String?=null
    private var running=false
    private val poller=object:Runnable{override fun run(){if(!running)return;poll();handler.postDelayed(this,800)}}
    override fun onCreate(){super.onCreate();wm=getSystemService(WINDOW_SERVICE) as WindowManager;channel();foreground()}
    override fun onStartCommand(intent:Intent?,flags:Int,id:Int):Int{running=true;if(lastTime==0L)lastTime=System.currentTimeMillis()-60_000;handler.removeCallbacks(poller);handler.post(poller);return START_STICKY}
    private fun channel(){if(Build.VERSION.SDK_INT>=26){val c=NotificationChannel(CHANNEL,"FaceLock – הגנת אפליקציות",NotificationManager.IMPORTANCE_LOW);c.description="התראה בזמן שהגנת האפליקציות פועלת";getSystemService(NotificationManager::class.java).createNotificationChannel(c)}}
    private fun foreground(){
        val pi=PendingIntent.getActivity(this,1,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n=Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_lock_lock).setContentTitle("FaceLock Pro פעיל").setContentText("הגנת האפליקציות פועלת").setContentIntent(pi).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE).build()
        if(Build.VERSION.SDK_INT>=34)startForeground(NOTIF,n,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(NOTIF,n)
    }
    private fun poll(){
        if(!Settings.canDrawOverlays(this)||!MainActivity.hasUsageAccess(this)){removeOverlay();stopSelf();return}
        val now=System.currentTimeMillis();val stats=getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events=stats.queryEvents((lastTime-1000).coerceAtLeast(now-60_000),now);val e=UsageEvents.Event();var pkg=lastPackage;var stamp=lastTime
        while(events.hasNextEvent()){events.getNextEvent(e);if(e.timeStamp>=stamp&&e.eventType==UsageEvents.Event.ACTIVITY_RESUMED){stamp=e.timeStamp;pkg=e.packageName}}
        if(stamp>lastTime){lastTime=stamp;lastPackage=pkg}
        val current=pkg?:return
        if(current==packageName){removeOverlay();return}
        if(LockSession.authorizedPackage!=null&&LockSession.authorizedPackage!=current)LockSession.clear()
        val protected=getSharedPreferences(MainActivity.PREFS,MODE_PRIVATE).getStringSet(MainActivity.KEY_PROTECTED_PACKAGES,emptySet()).orEmpty()
        if(protected.contains(current)&&LockSession.authorizedPackage!=current)showOverlay(current) else removeOverlay()
    }
    private fun showOverlay(app:String){
        if(overlay!=null&&target==app)return
        removeOverlay();if(!Settings.canDrawOverlays(this))return
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(28),dp(34),dp(28),dp(34));background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(Color.rgb(7,16,35),Color.rgb(14,35,57),Color.rgb(10,22,42)))}
        box.addView(TextView(this).apply{text="◉";textSize=54f;gravity=Gravity.CENTER;setTextColor(Color.rgb(69,225,208))},lp())
        box.addView(TextView(this).apply{text="האפליקציה מוגנת";textSize=27f;typeface=Typeface.DEFAULT_BOLD;gravity=Gravity.CENTER;setTextColor(Color.WHITE);setPadding(0,dp(14),0,dp(8))},lp())
        box.addView(TextView(this).apply{text="כדי להמשיך, יש לאמת באמצעות מנגנון האבטחה של Android.";textSize=15f;gravity=Gravity.CENTER;setTextColor(Color.rgb(190,207,224));setPadding(0,0,0,dp(26))},lp())
        box.addView(Button(this).apply{text="אימות ופתיחה";textSize=17f;setTextColor(Color.rgb(7,16,35));background=GradientDrawable().apply{setColor(Color.rgb(69,225,208));cornerRadius=dp(18).toFloat()};setOnClickListener{startActivity(Intent(this@AppLockMonitorService,BiometricAuthActivity::class.java).putExtra(BiometricAuthActivity.EXTRA_TARGET,app).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))}},lp())
        val params=WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_SECURE,PixelFormat.TRANSLUCENT).apply{gravity=Gravity.TOP or Gravity.START}
        try{wm?.addView(box,params);overlay=box;target=app}catch(_:Exception){overlay=null;target=null}
    }
    private fun lp()=LinearLayout.LayoutParams(-1,-2)
    private fun removeOverlay(){val v=overlay?:return;try{wm?.removeView(v)}catch(_:Exception){};overlay=null;target=null}
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    override fun onDestroy(){running=false;handler.removeCallbacksAndMessages(null);removeOverlay();super.onDestroy()}
    override fun onBind(intent:Intent?):IBinder?=null
    companion object{private const val CHANNEL="facelock_monitor";private const val NOTIF=9041}
}
