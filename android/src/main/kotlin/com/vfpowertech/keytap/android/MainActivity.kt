package com.vfpowertech.keytap.android

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.almworks.sqlite4java.SQLite
import com.vfpowertech.keytap.core.BuildConfig
import com.vfpowertech.jsbridge.androidwebengine.AndroidWebEngineInterface
import com.vfpowertech.jsbridge.core.dispatcher.Dispatcher
import com.vfpowertech.keytap.android.services.AndroidPlatformInfoService
import com.vfpowertech.keytap.ui.services.createAppDirectories
import com.vfpowertech.keytap.ui.services.di.PlatformModule
import com.vfpowertech.keytap.ui.services.di.DaggerUIServicesComponent
import com.vfpowertech.keytap.ui.services.js.NavigationService
import com.vfpowertech.keytap.ui.services.js.javatojs.NavigationServiceToJSProxy
import com.vfpowertech.keytap.ui.services.registerServicesOnDispatcher
import nl.komponents.kovenant.android.androidUiDispatcher
import nl.komponents.kovenant.ui.KovenantUi
import org.slf4j.LoggerFactory

class MainActivity : Activity() {
    private var navigationService: NavigationService? = null
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView) as WebView
        webView.settings.javaScriptEnabled = true
        webView.settings.allowFileAccessFromFileURLs = true

        initJSLogging(webView)

        val engineInterface = AndroidWebEngineInterface(webView)
        val dispatcher = Dispatcher(engineInterface)

        val platformInfo = AndroidPlatformInfo(this)
        createAppDirectories(platformInfo)

        val platformModule = PlatformModule(
            AndroidPlatformInfoService(),
            BuildConfig.ANDROID_SERVER_URLS,
            platformInfo
        )

        val uiServicesComponent = DaggerUIServicesComponent.builder()
            .platformModule(platformModule)
            .build()

        registerServicesOnDispatcher(dispatcher, uiServicesComponent)

        //TODO should init this only once the webview has loaded the page
        webView.setWebViewClient(object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                navigationService = NavigationServiceToJSProxy(dispatcher)
            }
        })

        if (savedInstanceState == null)
            webView.loadUrl("file:///android_asset/ui/index.html")
    }

    override fun onSaveInstanceState(outState: Bundle?) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle?) {
        super.onRestoreInstanceState(savedInstanceState)
        webView.restoreState(savedInstanceState)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (navigationService != null) {
                navigationService!!.goBack()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Capture console.log output into android's log */
    private fun initJSLogging(webView: WebView) {
        val jsLog = LoggerFactory.getLogger("Javascript")
        webView.setWebChromeClient(object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                val msg = "[${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}] ${consoleMessage.message()}"
                when (consoleMessage.messageLevel()) {
                    ConsoleMessage.MessageLevel.DEBUG -> jsLog.debug(msg)
                    ConsoleMessage.MessageLevel.ERROR -> jsLog.error(msg)
                    ConsoleMessage.MessageLevel.LOG -> jsLog.info(msg)
                    ConsoleMessage.MessageLevel.TIP -> jsLog.info(msg)
                    ConsoleMessage.MessageLevel.WARNING -> jsLog.warn(msg)
                }
                return true;
            }
        })
    }
}