package com.reactnativecommunity.webview

import android.annotation.TargetApi
import android.graphics.Bitmap
import android.os.Build
import android.webkit.WebResourceRequest
import android.webkit.WebView
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.WritableMap
import com.reactnativecommunity.webview.events.TopLoadingErrorEvent
import com.reactnativecommunity.webview.events.TopLoadingFinishEvent
import com.reactnativecommunity.webview.events.TopLoadingStartEvent
import com.reactnativecommunity.webview.events.TopShouldStartLoadWithRequestEvent
import org.apache.cordova.engine.SystemWebViewClient
import org.apache.cordova.engine.SystemWebViewEngine

open class RNCWebViewClient(webViewEngine: SystemWebViewEngine) : SystemWebViewClient(webViewEngine) {

    private var mLastLoadFailed = false
    private var mUrlPrefixesForDefaultIntent: ReadableArray? = null

    override fun onPageFinished(webView: WebView, url: String) {
        super.onPageFinished(webView, url)

        if (!mLastLoadFailed) {
            val reactWebView = webView as RNCWebView

            reactWebView.callInjectedJavaScript()

            emitFinishEvent(webView, url)
        }
    }

    override fun onPageStarted(webView: WebView, url: String, favicon: Bitmap) {
        super.onPageStarted(webView, url, favicon)
        mLastLoadFailed = false

        RNCWebViewManager.dispatchEvent(
                webView,
                TopLoadingStartEvent(
                        webView.id,
                        createWebViewEvent(webView, url)))
    }

    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
        RNCWebViewManager.dispatchEvent(
                view,
                TopShouldStartLoadWithRequestEvent(
                        view.id,
                        createWebViewEvent(view, url)))
        return true
    }

    @TargetApi(Build.VERSION_CODES.N)
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        return this.shouldOverrideUrlLoading(view, url)
    }

    override fun onReceivedError(
        webView: WebView,
        errorCode: Int,
        description: String,
        failingUrl: String
    ) {
        super.onReceivedError(webView, errorCode, description, failingUrl)
        mLastLoadFailed = true

        // In case of an error JS side expect to get a finish event first, and then get an error event
        // Android WebView does it in the opposite way, so we need to simulate that behavior
        emitFinishEvent(webView, failingUrl)

        val eventData = createWebViewEvent(webView, failingUrl)
        eventData.putDouble("code", errorCode.toDouble())
        eventData.putString("description", description)

        RNCWebViewManager.dispatchEvent(
                webView,
                TopLoadingErrorEvent(webView.id, eventData))
    }

    private fun emitFinishEvent(webView: WebView, url: String) {
        RNCWebViewManager.dispatchEvent(
                webView,
                TopLoadingFinishEvent(
                        webView.id,
                        createWebViewEvent(webView, url)))
    }

    private fun createWebViewEvent(webView: WebView, url: String): WritableMap {
        val event = Arguments.createMap()
        event.putDouble("target", webView.id.toDouble())
        // Don't use webView.getUrl() here, the URL isn't updated to the new value yet in callbacks
        // like onPageFinished
        event.putString("url", url)
        event.putBoolean("loading", !mLastLoadFailed && webView.progress != 100)
        event.putString("title", webView.title)
        event.putBoolean("canGoBack", webView.canGoBack())
        event.putBoolean("canGoForward", webView.canGoForward())
        return event
    }

    fun setUrlPrefixesForDefaultIntent(specialUrls: ReadableArray) {
        mUrlPrefixesForDefaultIntent = specialUrls
    }
}
