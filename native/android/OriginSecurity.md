# Native bridge origin security — LAUNCH BLOCKER

> Status: **Unresolved launch blocker.** The trusted-origin restriction for
> `window.PlayBilling` is the responsibility of the **custom Android host
> Activity** that injects the bridge. There is **no host Activity in this
> Base44 project** — only the Kotlin interface and this documentation. The
> bridge CANNOT be considered secure until the host enforces an HTTPS origin
> allow-list at runtime.

## Why this is a blocker

A `@JavascriptInterface` is reachable from **any** page loaded in the WebView
(`PlayBillingInterface.kt` lines marked `@JavascriptInterface`). If the WebView
ever navigates to a third-party page, a redirect, an injected script, or an
XSS payload, that content can call `getPurchases()` (reading purchase tokens),
`launchBillingFlow`, `acknowledgePurchase`, or `consumePurchase`. Comments and
documentation are not security controls — the enforcement must be runtime code
in the host.

## The host Activity MUST implement

1. **Inject only for the trusted origin.** Call
   `webView.addJavascriptInterface(playBilling, "PlayBilling")` only when the
   WebView's loaded URL origin is the trusted app (`https://aura-your-muse.base44.app`
   or your connected custom domain). For any other origin, do NOT inject the
   interface (or remove it).

2. **Enforce an HTTPS origin allow-list in a `WebViewClient`.** Override
   `shouldOverrideUrlLoading` and `onPageStarted` to check the loaded URL's
   origin against the allow-list. Block navigation to off-origin pages (return
   true / cancel the load) or strip the `PlayBilling` interface before the page
   executes. Example sketch:

   ```kotlin
   private val trustedOrigins = setOf(
       "https://aura-your-muse.base44.app",
       // add your connected custom domain here
   )

   webView.webViewClient = object : WebViewClient() {
       override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
           val origin = request?.url?.let { "${it.scheme}://${it.host}" } ?: return true
           if (origin !in trustedOrigins) {
               // Off-origin navigation: remove the bridge so the page cannot reach it.
               view?.removeJavascriptInterface("PlayBilling")
               // Optionally open the URL in the external browser instead.
               return true
           }
           return false
       }
       override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
           val origin = url?.let { uri ->
               "${uri.substringBefore("://")}://${uri.substringAfter("://").substringBefore("/")}"
           }
           if (origin !in trustedOrigins) {
               view?.removeJavascriptInterface("PlayBilling")
           }
       }
   }
   ```

3. **Re-add the interface** when navigation returns to the trusted origin (in
   `onPageStarted`), so the app keeps working after a legitimate in-app
   navigation.

4. **Never expose the service-account JSON, package name, or other secrets** to
   the WebView. Those live server-side only (`GOOGLE_PLAY_*` secrets).

## Scope

This file is a requirement specification for the **separate native Android
project**. It is not implemented here because no host Activity exists in the
Base44 project, and Base44's managed WebView wrapper does not inject
`window.PlayBilling` at all. Until a custom wrapper implements the runtime
allow-list above, **origin security remains an open launch blocker**.