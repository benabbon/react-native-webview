package com.reactnativecommunity.webview

import android.webkit.WebView

/**
 * Implement this interface in order to config your [WebView]. An instance of that
 * implementation will have to be given as a constructor argument to [RNCWebViewManager].
 */
interface WebViewConfig {
    fun configWebView(webView: WebView)
}
