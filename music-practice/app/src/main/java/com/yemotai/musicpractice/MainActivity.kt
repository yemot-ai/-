package com.yemotai.musicpractice
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(5, 15, 35)
        window.navigationBarColor = Color.rgb(4, 13, 31)
        val web = WebView(this)
        web.setBackgroundColor(Color.rgb(7, 20, 46))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.settings.allowContentAccess = true
        web.webChromeClient = WebChromeClient()
        setContentView(FrameLayout(this).apply { addView(web, FrameLayout.LayoutParams(-1, -1)) })
        web.loadUrl("file:///android_asset/index.html")
    }
}