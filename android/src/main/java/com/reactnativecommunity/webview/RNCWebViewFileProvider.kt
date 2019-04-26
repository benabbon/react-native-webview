package com.reactnativecommunity.webview

import android.support.v4.content.FileProvider

/**
 * Providing a custom `FileProvider` prevents manifest `<provider>` name collisions.
 *
 *
 * See https://developer.android.com/guide/topics/manifest/provider-element.html for details.
 */
// This class intentionally left blank.
class RNCWebViewFileProvider : FileProvider()
