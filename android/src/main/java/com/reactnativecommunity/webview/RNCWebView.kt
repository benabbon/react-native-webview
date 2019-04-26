package com.reactnativecommunity.webview

import android.annotation.SuppressLint
import android.os.Build
import android.text.TextUtils
import android.webkit.JavascriptInterface
import android.webkit.WebViewClient
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.events.ContentSizeChangeEvent
import com.reactnativecommunity.webview.RNCWebViewManager.Companion.JAVASCRIPT_INTERFACE
import com.reactnativecommunity.webview.events.TopMessageEvent
import org.apache.cordova.engine.SystemWebView
import java.io.UnsupportedEncodingException
import java.net.URLEncoder

/**
 * Subclass of [WebView] that implements [LifecycleEventListener] interface in order
 * to call [WebView.destroy] on activity destroy event and also to clear the client
 */
/**
 * WebView must be created with an context of the current activity
 *
 *
 * Activity Context is required for creation of dialogs internally by WebView
 * Reactive Native needed for access to ReactNative internal system functionality
 */
open class RNCWebView(reactContext: ThemedReactContext) : SystemWebView(reactContext), LifecycleEventListener {
    private var injectedJS: String? = null
    private var messagingEnabled = false
    var rncWebViewClient: RNCWebViewClient? = null
    private var sendContentSizeChangeEvents = false

    fun setSendContentSizeChangeEvents(sendContentSizeChangeEvents: Boolean) {
        this.sendContentSizeChangeEvents = sendContentSizeChangeEvents
    }

    override fun onHostResume() {
        // do nothing
    }

    override fun onHostPause() {
        // do nothing
    }

    override fun onHostDestroy() {
        cleanupCallbacksAndDestroy()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)

        if (sendContentSizeChangeEvents) {
            RNCWebViewManager.dispatchEvent(
                    this,
                    ContentSizeChangeEvent(
                            this.id,
                            w,
                            h
                    )
            )
        }
    }

    override fun setWebViewClient(client: WebViewClient?) {
        super.setWebViewClient(client)
        rncWebViewClient = client as RNCWebViewClient?
    }

    fun setInjectedJavaScript(js: String?) {
        injectedJS = js
    }

    private fun createRNCWebViewBridge(webView: RNCWebView): RNCWebViewBridge {
        return RNCWebViewBridge(webView)
    }

    @SuppressLint("JavascriptInterface", "AddJavascriptInterface")
    fun setMessagingEnabled(enabled: Boolean) {
        if (messagingEnabled == enabled) {
            return
        }

        messagingEnabled = enabled

        if (enabled) {
            addJavascriptInterface(createRNCWebViewBridge(this), JAVASCRIPT_INTERFACE)
        } else {
            removeJavascriptInterface(JAVASCRIPT_INTERFACE)
        }
    }

    fun evaluateJavascriptWithFallback(script: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            evaluateJavascript(script, null)
            return
        }

        try {
            loadUrl("javascript:" + URLEncoder.encode(script, "UTF-8"))
        } catch (e: UnsupportedEncodingException) {
            // UTF-8 should always be supported
            throw RuntimeException(e)
        }
    }

    fun callInjectedJavaScript() {
        if (settings.javaScriptEnabled &&
                injectedJS != null &&
                !TextUtils.isEmpty(injectedJS)) {
            evaluateJavascriptWithFallback("(function() {\n$injectedJS;\n})();")
        }
    }

    fun onMessage(message: String) {
        RNCWebViewManager.dispatchEvent(this, TopMessageEvent(this.id, message))
    }

    fun cleanupCallbacksAndDestroy() {
        webViewClient = null
        destroy()
    }

    private inner class RNCWebViewBridge internal constructor(internal var mContext: RNCWebView) {
        /**
         * This method is called whenever JavaScript running within the web view calls:
         * - window[JAVASCRIPT_INTERFACE].postMessage
         */
        @JavascriptInterface
        fun postMessage(message: String) {
            mContext.onMessage(message)
        }
    }
}
