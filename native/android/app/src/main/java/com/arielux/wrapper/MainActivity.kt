package com.arielux.wrapper

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.arielux.billing.PlayBillingInterface

/**
 * Host Activity for the Arielux web app. Loads the trusted HTTPS origin in a
 * locked-down WebView and injects the Play Billing bridge ONLY for that origin.
 *
 * Security model (see ../OriginSecurity.md): a @JavascriptInterface is reachable
 * from any page in the WebView, so [WebViewClient.shouldOverrideUrlLoading] and
 * [WebViewClient.onPageStarted] enforce an HTTPS origin allow-list: the
 * `PlayBilling` interface is present only while the loaded URL origin is trusted
 * and is stripped the instant any off-origin URL is touched.
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var playBilling: PlayBillingInterface

    // The ONLY origins the WebView may load. Update this single set if a custom
    // domain is connected later. The billing interface is injected only here.
    private val trustedOrigins = setOf(
        "https://aura-your-muse.base44.app"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        configureWebSettings(webView.settings)

        playBilling = PlayBillingInterface(applicationContext) { this }
        // Inject before load so the object exists when the web app boots.
        webView.addJavascriptInterface(playBilling, "PlayBilling")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                if (!isTrusted(request?.url)) {
                    // Off-origin navigation: strip the bridge so the page cannot
                    // reach it, then block the load.
                    view?.removeJavascriptInterface("PlayBilling")
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (isTrusted(url?.let { Uri.parse(it) })) {
                    // Re-add on return to the trusted origin.
                    view?.addJavascriptInterface(playBilling, "PlayBilling")
                } else {
                    view?.removeJavascriptInterface("PlayBilling")
                    view?.stopLoading()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                if (!isTrusted(request.origin)) {
                    request.deny()
                    return
                }
                // Grant a WebView media permission only if the app already holds
                // the corresponding runtime permission.
                val granted = request.resources.filter { res ->
                    (res == PermissionRequest.RESOURCE_AUDIO_CAPTURE && hasPermission(Manifest.permission.RECORD_AUDIO)) ||
                        (res == PermissionRequest.RESOURCE_VIDEO_CAPTURE && hasPermission(Manifest.permission.CAMERA))
                }.toTypedArray()
                if (granted.isNotEmpty()) request.grant(granted) else request.deny()
            }
        }

        setContentView(webView)
        requestRuntimePermissions()
        playBilling.start()
        webView.loadUrl("https://aura-your-muse.base44.app")
    }

    private fun configureWebSettings(s: WebSettings) {
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.setSupportMultipleWindows(false)
        s.javaScriptCanOpenWindowsAutomatically = false
        // Disable all file/content access — the app loads only an HTTPS origin.
        s.allowFileAccess = false
        s.allowContentAccess = false
        @Suppress("DEPRECATION")
        s.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        s.allowUniversalAccessFromFileURLs = false
        s.mediaPlaybackRequiresUserGesture = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
    }

    private fun isTrusted(uri: Uri?): Boolean = uri != null &&
        uri.scheme == "https" && uri.userInfo.isNullOrEmpty() &&
        (uri.port == -1 || uri.port == 443) &&
        "${uri.scheme}://${uri.host}" in trustedOrigins

    private fun hasPermission(perm: String): Boolean =
        checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    private fun requestRuntimePermissions() {
        val needed = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
            .filterNot { hasPermission(it) }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), RC_PERMS)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::playBilling.isInitialized) playBilling.start()
    }

    override fun onDestroy() {
        if (::playBilling.isInitialized) playBilling.end()
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && ::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        private const val RC_PERMS = 1001
    }
}