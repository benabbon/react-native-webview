package com.reactnativecommunity.webview

import android.annotation.TargetApi
import android.net.Uri
import android.os.Build
import android.webkit.WebView
import android.webkit.ValueCallback
import android.webkit.GeolocationPermissions
import android.webkit.ConsoleMessage
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactContext
import com.facebook.react.common.build.ReactBuildConfig
import com.reactnativecommunity.webview.events.TopLoadingProgressEvent
import org.apache.cordova.engine.SystemWebChromeClient
import org.apache.cordova.engine.SystemWebViewEngine

open class RNCWebChromeClient(private val reactContext: ReactContext, webViewEngine: SystemWebViewEngine) : SystemWebChromeClient(webViewEngine) {
    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
        return if (ReactBuildConfig.DEBUG) {
            super.onConsoleMessage(message)
        } else true
        // Ignore console logs in non debug builds.
    }

    override fun onProgressChanged(webView: WebView, newProgress: Int) {
        super.onProgressChanged(webView, newProgress)
        val event = Arguments.createMap()
        event.putDouble("target", webView.id.toDouble())
        event.putString("title", webView.title)
        event.putBoolean("canGoBack", webView.canGoBack())
        event.putBoolean("canGoForward", webView.canGoForward())
        event.putDouble("progress", (newProgress.toFloat() / 100).toDouble())
        RNCWebViewManager.dispatchEvent(
                webView,
                TopLoadingProgressEvent(
                        webView.id,
                        event))
    }

    override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
        callback.invoke(origin, true, false)
    }

    override fun openFileChooser(filePathCallback: ValueCallback<Uri>, acceptType: String) {
        getModule(reactContext).startPhotoPickerIntent(filePathCallback, acceptType)
    }

    override fun openFileChooser(filePathCallback: ValueCallback<Uri>) {
        getModule(reactContext).startPhotoPickerIntent(filePathCallback, "")
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    override fun onShowFileChooser(webView: WebView, filePathCallback: ValueCallback<Array<Uri>>, fileChooserParams: FileChooserParams): Boolean {
        val acceptTypes = fileChooserParams.acceptTypes
        val allowMultiple = fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE
        fileChooserParams.createIntent()
        return getModule(reactContext).startPhotoPickerIntent(filePathCallback, acceptTypes, allowMultiple)
    }

    private fun getModule(reactContext: ReactContext): RNCWebViewModule {
        return reactContext.getNativeModule(RNCWebViewModule::class.java)
    }
}