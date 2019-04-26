package com.reactnativecommunity.webview

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Parcelable
import android.provider.MediaStore
import android.support.annotation.RequiresApi
import android.support.v4.content.ContextCompat
import android.support.v4.content.FileProvider
import android.util.Log
import android.webkit.MimeTypeMap
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.Toast

import com.facebook.react.bridge.ActivityEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.modules.core.PermissionAwareActivity
import com.facebook.react.modules.core.PermissionListener

import java.io.File
import java.io.IOException
import java.util.ArrayList

import android.app.Activity.RESULT_OK

class RNCWebViewModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext), ActivityEventListener {
    private var filePathCallbackLegacy: ValueCallback<Uri>? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var outputFileUri: Uri? = null
    private var downloadRequest: DownloadManager.Request? = null
    private val webviewFileDownloaderPermissionListener = PermissionListener { requestCode, _, grantResults ->
        when (requestCode) {
            FILE_DOWNLOAD_PERMISSION_REQUEST -> {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    if (downloadRequest != null) {
                        downloadFile()
                    }
                } else {
                    Toast.makeText(currentActivity!!.applicationContext, "Cannot download files as permission was denied. Please provide permission to write to storage, in order to download files.", Toast.LENGTH_LONG).show()
                }
                return@PermissionListener true
            }
        }
        false
    }

    private val photoIntent: Intent
        get() {
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            outputFileUri = getOutputUri(MediaStore.ACTION_IMAGE_CAPTURE)
            intent.putExtra(MediaStore.EXTRA_OUTPUT, outputFileUri)
            return intent
        }

    // @todo from experience, for Videos we get the data onActivityResult
    // so there's no need to store the Uri
    private val videoIntent: Intent
        get() {
            val intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE)
            val outputVideoUri = getOutputUri(MediaStore.ACTION_VIDEO_CAPTURE)
            intent.putExtra(MediaStore.EXTRA_OUTPUT, outputVideoUri)
            return intent
        }

    private val permissionAwareActivity: PermissionAwareActivity
        get() {
            val activity = currentActivity
            if (activity == null) {
                throw IllegalStateException("Tried to use permissions API while not attached to an Activity.")
            } else if (activity !is PermissionAwareActivity) {
                throw IllegalStateException("Tried to use permissions API but the host Activity doesn't implement PermissionAwareActivity.")
            }
            return activity
        }

    init {
        reactContext.addActivityEventListener(this)
    }

    override fun getName(): String {
        return MODULE_NAME
    }

    @ReactMethod
    fun isFileUploadSupported(promise: Promise) {
        var result: Boolean? = false
        val current = Build.VERSION.SDK_INT
        if (current >= Build.VERSION_CODES.LOLLIPOP) {
            result = true
        }
        if (current >= Build.VERSION_CODES.JELLY_BEAN && current <= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            result = true
        }
        promise.resolve(result)
    }

    override fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {

        if (filePathCallback == null && filePathCallbackLegacy == null) {
            return
        }

        // based off of which button was pressed, we get an activity result and a file
        // the camera activity doesn't properly return the filename* (I think?) so we use
        // this filename instead
        when (requestCode) {
            PICKER -> if (resultCode != RESULT_OK) {
                if (filePathCallback != null) {
                    filePathCallback!!.onReceiveValue(null)
                }
            } else {
                val result = this.getSelectedFiles(data, resultCode)
                if (result != null) {
                    filePathCallback!!.onReceiveValue(result)
                } else {
                    filePathCallback!!.onReceiveValue(outputFileUri?.let { arrayOf(it) })
                }
            }
            PICKER_LEGACY -> {
                val result = if (resultCode != Activity.RESULT_OK) null else if (data == null) outputFileUri else data.data
                filePathCallbackLegacy!!.onReceiveValue(result)
            }
        }
        filePathCallback = null
        filePathCallbackLegacy = null
        outputFileUri = null
    }

    override fun onNewIntent(intent: Intent) {}

    private fun getSelectedFiles(data: Intent?, resultCode: Int): Array<Uri>? {
        if (data == null) {
            return null
        }

        // we have one file selected
        if (data.data != null) {
            return if (resultCode == RESULT_OK && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            } else {
                null
            }
        }

        // we have multiple files selected
        if (data.clipData != null) {
            val numSelectedFiles = data.clipData!!.itemCount
            val result = arrayOfNulls<Uri>(numSelectedFiles)
            for (i in 0 until numSelectedFiles) {
                result[i] = data.clipData!!.getItemAt(i).uri
            }
            return result.requireNoNulls()
        }
        return null
    }

    fun startPhotoPickerIntent(filePathCallback: ValueCallback<Uri>, acceptType: String) {
        filePathCallbackLegacy = filePathCallback

        val fileChooserIntent = getFileChooserIntent(acceptType)
        val chooserIntent = Intent.createChooser(fileChooserIntent, "")

        val extraIntents = ArrayList<Parcelable>()
        if (acceptsImages(acceptType)) {
            extraIntents.add(photoIntent)
        }
        if (acceptsVideo(acceptType)) {
            extraIntents.add(videoIntent)
        }
        chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, extraIntents.toTypedArray())

        if (chooserIntent.resolveActivity(currentActivity!!.packageManager) != null) {
            currentActivity!!.startActivityForResult(chooserIntent, PICKER_LEGACY)
        } else {
            Log.w("RNCWebViewModule", "there is no Activity to handle this Intent")
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    fun startPhotoPickerIntent(callback: ValueCallback<Array<Uri>>, acceptTypes: Array<String>, allowMultiple: Boolean): Boolean {
        filePathCallback = callback

        val extraIntents = ArrayList<Parcelable>()
        if (acceptsImages(acceptTypes)) {
            extraIntents.add(photoIntent)
        }
        if (acceptsVideo(acceptTypes)) {
            extraIntents.add(videoIntent)
        }

        val fileSelectionIntent = getFileChooserIntent(acceptTypes, allowMultiple)

        val chooserIntent = Intent(Intent.ACTION_CHOOSER)
        chooserIntent.putExtra(Intent.EXTRA_INTENT, fileSelectionIntent)
        chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, extraIntents.toTypedArray())

        if (chooserIntent.resolveActivity(currentActivity!!.packageManager) != null) {
            currentActivity!!.startActivityForResult(chooserIntent, PICKER)
        } else {
            Log.w("RNCWebViewModule", "there is no Activity to handle this Intent")
        }

        return true
    }

    fun setDownloadRequest(request: DownloadManager.Request) {
        this.downloadRequest = request
    }

    fun downloadFile() {
        val dm = currentActivity!!.baseContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadMessage = "Downloading"

        dm.enqueue(this.downloadRequest)

        Toast.makeText(currentActivity!!.applicationContext, downloadMessage, Toast.LENGTH_LONG).show()
    }

    fun grantFileDownloaderPermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true
        }

        var result = true
        if (ContextCompat.checkSelfPermission(currentActivity!!, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            result = false
        }

        if (!result) {
            val activity = permissionAwareActivity
            activity.requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), FILE_DOWNLOAD_PERMISSION_REQUEST, webviewFileDownloaderPermissionListener)
        }

        return result
    }

    private fun getFileChooserIntent(acceptTypes: String): Intent {
        var _acceptTypes: String? = acceptTypes
        if (acceptTypes.isEmpty()) {
            _acceptTypes = Companion.DEFAULT_MIME_TYPES
        }
        if (acceptTypes.matches("\\.\\w+".toRegex())) {
            _acceptTypes = getMimeTypeFromExtension(acceptTypes.replace(".", ""))
        }
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = _acceptTypes
        return intent
    }

    private fun getFileChooserIntent(acceptTypes: Array<String>, allowMultiple: Boolean): Intent {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"
        intent.putExtra(Intent.EXTRA_MIME_TYPES, getAcceptedMimeType(acceptTypes))
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
        return intent
    }

    private fun acceptsImages(types: String): Boolean {
        var mimeType: String? = types
        if (types.matches("\\.\\w+".toRegex())) {
            mimeType = getMimeTypeFromExtension(types.replace(".", ""))
        }
        return mimeType!!.isEmpty() || mimeType.toLowerCase().contains("image")
    }

    private fun acceptsImages(types: Array<String>): Boolean {
        val mimeTypes = getAcceptedMimeType(types)
        return isArrayEmpty(mimeTypes) || arrayContainsString(mimeTypes, "image")
    }

    private fun acceptsVideo(types: String): Boolean {
        var mimeType: String? = types
        if (types.matches("\\.\\w+".toRegex())) {
            mimeType = getMimeTypeFromExtension(types.replace(".", ""))
        }
        return mimeType!!.isEmpty() || mimeType.toLowerCase().contains("video")
    }

    private fun acceptsVideo(types: Array<String>): Boolean {
        val mimeTypes = getAcceptedMimeType(types)
        return isArrayEmpty(mimeTypes) || arrayContainsString(mimeTypes, "video")
    }

    private fun arrayContainsString(array: Array<String>, pattern: String): Boolean {
        for (content in array) {
            if (content.contains(pattern)) {
                return true
            }
        }
        return false
    }

    private fun getAcceptedMimeType(types: Array<String>): Array<String> {
        if (isArrayEmpty(types)) {
            return arrayOf(Companion.DEFAULT_MIME_TYPES)
        }
        val mimeTypes = arrayOfNulls<String>(types.size)
        for (i in types.indices) {
            val t = types[i]
            // convert file extensions to mime types
            if (t.matches("\\.\\w+".toRegex())) {
                val mimeType = getMimeTypeFromExtension(t.replace(".", ""))
                mimeTypes[i] = mimeType
            } else {
                mimeTypes[i] = t
            }
        }
        return mimeTypes.requireNoNulls()
    }

    private fun getMimeTypeFromExtension(extension: String?): String? {
        var type: String? = null
        if (extension != null) {
            type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        }
        return type
    }

    private fun getOutputUri(intentType: String): Uri {
        var capturedFile: File? = null
        try {
            capturedFile = getCapturedFile(intentType)
        } catch (e: IOException) {
            Log.e("CREATE FILE", "Error occurred while creating the File", e)
            e.printStackTrace()
        }

        // for versions below 6.0 (23) we use the old File creation & permissions model
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return Uri.fromFile(capturedFile)
        }

        // for versions 6.0+ (23) we use the FileProvider to avoid runtime permissions
        val packageName = reactApplicationContext.packageName
        return FileProvider.getUriForFile(reactApplicationContext, "$packageName.fileprovider", capturedFile!!)
    }

    @Throws(IOException::class)
    private fun getCapturedFile(intentType: String): File {
        var prefix = ""
        var suffix = ""
        var dir = ""
        val filename: String

        if (intentType == MediaStore.ACTION_IMAGE_CAPTURE) {
            prefix = "image-"
            suffix = ".jpg"
            dir = Environment.DIRECTORY_PICTURES
        } else if (intentType == MediaStore.ACTION_VIDEO_CAPTURE) {
            prefix = "video-"
            suffix = ".mp4"
            dir = Environment.DIRECTORY_MOVIES
        }

        filename = prefix + System.currentTimeMillis().toString() + suffix

        // for versions below 6.0 (23) we use the old File creation & permissions model
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // only this Directory works on all tested Android versions
            // ctx.getExternalFilesDir(dir) was failing on Android 5.0 (sdk 21)
            val storageDir = Environment.getExternalStoragePublicDirectory(dir)
            return File(storageDir, filename)
        }

        val storageDir = reactApplicationContext.getExternalFilesDir(null)
        return File.createTempFile(filename, suffix, storageDir)
    }

    private fun isArrayEmpty(arr: Array<String>): Boolean {
        // when our array returned from getAcceptTypes() has no values set from the webview
        // i.e. <input type="file" />, without any "accept" attr
        // will be an array with one empty string element, afaik
        return arr.size == 0 || arr.size == 1 && arr[0].length == 0
    }

    companion object {
        const val MODULE_NAME = "RNCWebView"
        private const val PICKER = 1
        private const val PICKER_LEGACY = 3
        private const val FILE_DOWNLOAD_PERMISSION_REQUEST = 1
        private const val DEFAULT_MIME_TYPES = "*/*"
    }
}
