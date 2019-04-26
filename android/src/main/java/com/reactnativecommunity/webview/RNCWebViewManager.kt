package com.reactnativecommunity.webview

import android.annotation.TargetApi
import android.app.DownloadManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.view.View
import android.view.ViewGroup.LayoutParams
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebSettings
import android.webkit.WebView
import com.facebook.react.bridge.ReactContext
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.common.MapBuilder
import com.facebook.react.common.build.ReactBuildConfig
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.UIManagerModule
import com.facebook.react.uimanager.annotations.ReactProp
import com.facebook.react.uimanager.events.Event
import com.reactnativecommunity.webview.events.TopLoadingProgressEvent
import com.reactnativecommunity.webview.events.TopShouldStartLoadWithRequestEvent
import org.apache.cordova.engine.SystemWebView
import org.apache.cordova.engine.SystemWebViewEngine
import org.json.JSONException
import org.json.JSONObject
import java.io.UnsupportedEncodingException
import java.net.MalformedURLException
import java.net.URL
import java.util.HashMap
import java.util.Locale

/**
 * Manages instances of [WebView]
 *
 *
 * Can accept following commands:
 * - GO_BACK
 * - GO_FORWARD
 * - RELOAD
 * - LOAD_URL
 *
 *
 * [WebView] instances could emit following direct events:
 * - topLoadingFinish
 * - topLoadingStart
 * - topLoadingStart
 * - topLoadingProgress
 * - topShouldStartLoadWithRequest
 *
 *
 * Each event will carry the following properties:
 * - target - view's react tag
 * - url - url set for the webview
 * - loading - whether webview is in a loading state
 * - title - title of the current page
 * - canGoBack - boolean, whether there is anything on a history stack to go back
 * - canGoForward - boolean, whether it is possible to request GO_FORWARD command
 */
open class RNCWebViewManager(private val mWebViewConfig: WebViewConfig) : SimpleViewManager<WebView>() {
    protected var engine: SystemWebViewEngine? = null

    override fun getName(): String {
        return REACT_CLASS
    }

    open fun createRNCWebViewInstance(reactContext: ThemedReactContext): RNCWebView {
        return RNCWebView(reactContext)
    }

    open fun createRNCWebChromeClientInstance(reactContext: ThemedReactContext, engine: SystemWebViewEngine): RNCWebChromeClient {
        return RNCWebChromeClient(reactContext, engine)
    }

    open fun createRNCWebViewClientInstance(engine: SystemWebViewEngine): RNCWebViewClient {
        return RNCWebViewClient(engine)
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    override fun createViewInstance(reactContext: ThemedReactContext): WebView {
        val webView = createRNCWebViewInstance(reactContext)

        engine = SystemWebViewEngine(webView as SystemWebView)
        webView.webChromeClient = createRNCWebChromeClientInstance(reactContext, engine!!)
        reactContext.addLifecycleEventListener(webView)
        mWebViewConfig.configWebView(webView)
        val settings = webView.settings
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.domStorageEnabled = true

        settings.allowFileAccess = false
        settings.allowContentAccess = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            settings.allowFileAccessFromFileURLs = false
            setAllowUniversalAccessFromFileURLs(webView, false)
        }
        setMixedContentMode(webView, "never")

        // Fixes broken full-screen modals/galleries due to body height being 0.
        webView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT)

        setGeolocationEnabled(webView, false)
        if (ReactBuildConfig.DEBUG && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            val module = getModule(reactContext)

            val request = DownloadManager.Request(Uri.parse(url))

            val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
            val downloadMessage = "Downloading $fileName"

            // Attempt to add cookie, if it exists
            val urlObj: URL?
            try {
                urlObj = URL(url)
                val baseUrl = urlObj.protocol + "://" + urlObj.host
                val cookie = CookieManager.getInstance().getCookie(baseUrl)
                request.addRequestHeader("Cookie", cookie)
                println("Got cookie for DownloadManager: $cookie")
            } catch (e: MalformedURLException) {
                println("Error getting cookie for DownloadManager: $e")
                e.printStackTrace()
            }

            // Finish setting up request
            request.addRequestHeader("User-Agent", userAgent)
            request.setTitle(fileName)
            request.setDescription(downloadMessage)
            request.allowScanningByMediaScanner()
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)

            module.setDownloadRequest(request)

            if (module.grantFileDownloaderPermissions()) {
                module.downloadFile()
            }
        }

        return webView
    }

    @ReactProp(name = "javaScriptEnabled")
    fun setJavaScriptEnabled(view: WebView, enabled: Boolean) {
        view.settings.javaScriptEnabled = enabled
    }

    @ReactProp(name = "showsHorizontalScrollIndicator")
    fun setShowsHorizontalScrollIndicator(view: WebView, enabled: Boolean) {
        view.isHorizontalScrollBarEnabled = enabled
    }

    @ReactProp(name = "showsVerticalScrollIndicator")
    fun setShowsVerticalScrollIndicator(view: WebView, enabled: Boolean) {
        view.isVerticalScrollBarEnabled = enabled
    }

    @ReactProp(name = "cacheEnabled")
    fun setCacheEnabled(view: WebView, enabled: Boolean) {
        if (enabled) {
            val ctx = view.context
            if (ctx != null) {
                view.settings.setAppCachePath(ctx.cacheDir.absolutePath)
                view.settings.cacheMode = WebSettings.LOAD_DEFAULT
                view.settings.setAppCacheEnabled(true)
            }
        } else {
            view.settings.cacheMode = WebSettings.LOAD_NO_CACHE
            view.settings.setAppCacheEnabled(false)
        }
    }

    @ReactProp(name = "androidHardwareAccelerationDisabled")
    fun setHardwareAccelerationDisabled(view: WebView, disabled: Boolean) {
        if (disabled) {
            view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }
    }

    @ReactProp(name = "overScrollMode")
    fun setOverScrollMode(view: WebView, overScrollModeString: String) {
        val overScrollMode: Int?
        when (overScrollModeString) {
            "never" -> overScrollMode = View.OVER_SCROLL_NEVER
            "content" -> overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            "always" -> overScrollMode = View.OVER_SCROLL_ALWAYS
            else -> overScrollMode = View.OVER_SCROLL_ALWAYS
        }
        view.overScrollMode = overScrollMode
    }

    @ReactProp(name = "thirdPartyCookiesEnabled")
    fun setThirdPartyCookiesEnabled(view: WebView, enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, enabled)
        }
    }

    @ReactProp(name = "textZoom")
    fun setTextZoom(view: WebView, value: Int) {
        view.settings.textZoom = value
    }

    @ReactProp(name = "scalesPageToFit")
    fun setScalesPageToFit(view: WebView, enabled: Boolean) {
        view.settings.loadWithOverviewMode = enabled
        view.settings.useWideViewPort = enabled
    }

    @ReactProp(name = "domStorageEnabled")
    fun setDomStorageEnabled(view: WebView, enabled: Boolean) {
        view.settings.domStorageEnabled = enabled
    }

    @ReactProp(name = "userAgent")
    fun setUserAgent(view: WebView, userAgent: String?) {
        if (userAgent != null) {
            // TODO(8496850): Fix incorrect behavior when property is unset (uA == null)
            view.settings.userAgentString = userAgent
        }
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    @ReactProp(name = "mediaPlaybackRequiresUserAction")
    fun setMediaPlaybackRequiresUserAction(view: WebView, requires: Boolean) {
        view.settings.mediaPlaybackRequiresUserGesture = requires
    }

    @ReactProp(name = "allowUniversalAccessFromFileURLs")
    fun setAllowUniversalAccessFromFileURLs(view: WebView, allow: Boolean) {
        view.settings.allowUniversalAccessFromFileURLs = allow
    }

    @ReactProp(name = "saveFormDataDisabled")
    fun setSaveFormDataDisabled(view: WebView, disable: Boolean) {
        view.settings.saveFormData = !disable
    }

    @ReactProp(name = "injectedJavaScript")
    fun setInjectedJavaScript(view: WebView, injectedJavaScript: String?) {
        (view as RNCWebView).setInjectedJavaScript(injectedJavaScript)
    }

    @ReactProp(name = "messagingEnabled")
    fun setMessagingEnabled(view: WebView, enabled: Boolean) {
        (view as RNCWebView).setMessagingEnabled(enabled)
    }

    @ReactProp(name = "source")
    fun setSource(view: WebView, source: ReadableMap?) {
        if (source != null) {
            if (source.hasKey("html")) {
                val html = source.getString("html")
                val baseUrl = if (source.hasKey("baseUrl")) source.getString("baseUrl") else ""
                view.loadDataWithBaseURL(baseUrl, html, HTML_MIME_TYPE, HTML_ENCODING, null)
                return
            }
            if (source.hasKey("uri")) {
                val url = source.getString("uri")
                val previousUrl = view.url
                if (previousUrl != null && previousUrl == url) {
                    return
                }
                if (source.hasKey("method")) {
                    val method = source.getString("method")
                    if (method.equals(HTTP_METHOD_POST, ignoreCase = true)) {
                        var postData: ByteArray? = null
                        if (source.hasKey("body")) {
                            val body = source.getString("body")
                            try {
                                postData = body.toByteArray(charset("UTF-8"))
                            } catch (e: UnsupportedEncodingException) {
                                postData = body.toByteArray()
                            }
                        }
                        if (postData == null) {
                            postData = ByteArray(0)
                        }
                        view.postUrl(url, postData)
                        return
                    }
                }
                val headerMap = HashMap<String, String>()
                if (source.hasKey("headers")) {
                    val headers = source.getMap("headers")
                    val iter = headers.keySetIterator()
                    while (iter.hasNextKey()) {
                        val key = iter.nextKey()
                        if ("user-agent" == key.toLowerCase(Locale.ENGLISH)) {
                            if (view.settings != null) {
                                view.settings.userAgentString = headers.getString(key)
                            }
                        } else {
                            headerMap[key] = headers.getString(key)
                        }
                    }
                }
                view.loadUrl(url, headerMap)
                return
            }
        }
        view.loadUrl(BLANK_URL)
    }

    @ReactProp(name = "onContentSizeChange")
    fun setOnContentSizeChange(view: WebView, sendContentSizeChangeEvents: Boolean) {
        (view as RNCWebView).setSendContentSizeChangeEvents(sendContentSizeChangeEvents)
    }

    @ReactProp(name = "mixedContentMode")
    fun setMixedContentMode(view: WebView, mixedContentMode: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if (mixedContentMode == null || "never" == mixedContentMode) {
                view.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            } else if ("always" == mixedContentMode) {
                view.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            } else if ("compatibility" == mixedContentMode) {
                view.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }
        }
    }

    @ReactProp(name = "urlPrefixesForDefaultIntent")
    fun setUrlPrefixesForDefaultIntent(
        view: WebView,
        urlPrefixesForDefaultIntent: ReadableArray?
    ) {
        val client = (view as RNCWebView).rncWebViewClient
        if (client != null && urlPrefixesForDefaultIntent != null) {
            client.setUrlPrefixesForDefaultIntent(urlPrefixesForDefaultIntent)
        }
    }

    @ReactProp(name = "allowFileAccess")
    fun setAllowFileAccess(
        view: WebView,
        allowFileAccess: Boolean?
    ) {
        view.settings.allowFileAccess = allowFileAccess != null && allowFileAccess
    }

    @ReactProp(name = "geolocationEnabled")
    fun setGeolocationEnabled(
        view: WebView,
        isGeolocationEnabled: Boolean?
    ) {
        view.settings.setGeolocationEnabled(isGeolocationEnabled != null && isGeolocationEnabled)
    }

    override fun addEventEmitters(reactContext: ThemedReactContext?, view: WebView?) {
        // Do not register default touch emitter and let WebView implementation handle touches
        view?.webViewClient = engine?.let { createRNCWebViewClientInstance(it) }
    }

    override fun getExportedCustomDirectEventTypeConstants(): MutableMap<String, Any>? {
        var export: MutableMap<String, Any>? = super.getExportedCustomDirectEventTypeConstants()
        if (export == null) {
            export = MapBuilder.newHashMap<String, Any>()
        }
        export?.set(TopLoadingProgressEvent.EVENT_NAME, MapBuilder.of("registrationName", "onLoadingProgress"))
        export?.set(TopShouldStartLoadWithRequestEvent.EVENT_NAME, MapBuilder.of("registrationName", "onShouldStartLoadWithRequest"))
        return export
    }

    override fun getCommandsMap(): Map<String, Int>? {
        return MapBuilder.of(
                "goBack", COMMAND_GO_BACK,
                "goForward", COMMAND_GO_FORWARD,
                "reload", COMMAND_RELOAD,
                "stopLoading", COMMAND_STOP_LOADING,
                "postMessage", COMMAND_POST_MESSAGE,
                "injectJavaScript", COMMAND_INJECT_JAVASCRIPT,
                "loadUrl", COMMAND_LOAD_URL
        )
    }

    override fun receiveCommand(root: WebView?, commandId: Int, args: ReadableArray?) {
        when (commandId) {
            COMMAND_GO_BACK -> root!!.goBack()
            COMMAND_GO_FORWARD -> root!!.goForward()
            COMMAND_RELOAD -> root!!.reload()
            COMMAND_STOP_LOADING -> root!!.stopLoading()
            COMMAND_POST_MESSAGE -> try {
                val reactWebView = root as RNCWebView?
                val eventInitDict = JSONObject()
                eventInitDict.put("data", args!!.getString(0))
                reactWebView!!.evaluateJavascriptWithFallback("(function () {" +
                        "var event;" +
                        "var data = " + eventInitDict.toString() + ";" +
                        "try {" +
                        "event = new MessageEvent('message', data);" +
                        "} catch (e) {" +
                        "event = document.createEvent('MessageEvent');" +
                        "event.initMessageEvent('message', true, true, data.data, data.origin, data.lastEventId, data.source);" +
                        "}" +
                        "document.dispatchEvent(event);" +
                        "})();")
            } catch (e: JSONException) {
                throw RuntimeException(e)
            }

            COMMAND_INJECT_JAVASCRIPT -> {
                val reactWebView = root as RNCWebView?
                reactWebView!!.evaluateJavascriptWithFallback(args!!.getString(0))
            }
            COMMAND_LOAD_URL -> {
                if (args == null) {
                    throw RuntimeException("Arguments for loading an url are null!")
                }
                root!!.loadUrl(args.getString(0))
            }
        }
    }

    override fun onDropViewInstance(webView: WebView?) {
        super.onDropViewInstance(webView)
        (webView!!.context as ThemedReactContext).removeLifecycleEventListener(webView as RNCWebView?)
        webView.cleanupCallbacksAndDestroy()
    }

    private fun getModule(reactContext: ReactContext): RNCWebViewModule {
        return reactContext.getNativeModule(RNCWebViewModule::class.java)
    }

    companion object {
        const val COMMAND_GO_BACK = 1
        const val COMMAND_GO_FORWARD = 2
        const val COMMAND_RELOAD = 3
        const val COMMAND_STOP_LOADING = 4
        const val COMMAND_POST_MESSAGE = 5
        const val COMMAND_INJECT_JAVASCRIPT = 6
        const val COMMAND_LOAD_URL = 7
        const val REACT_CLASS = "RNCWebView"
        private const val HTML_ENCODING = "UTF-8"
        private const val HTML_MIME_TYPE = "text/html"
        internal const val JAVASCRIPT_INTERFACE = "ReactNativeWebView"
        private const val HTTP_METHOD_POST = "POST"
        // Use `webView.loadUrl("about:blank")` to reliably reset the view
        // state and release page resources (including any running JavaScript).
        private const val BLANK_URL = "about:blank"

        internal fun dispatchEvent(webView: WebView, event: Event<*>) {
            val reactContext = webView.context as ReactContext
            val eventDispatcher = reactContext.getNativeModule(UIManagerModule::class.java).eventDispatcher
            eventDispatcher.dispatchEvent(event)
        }
    }
}
