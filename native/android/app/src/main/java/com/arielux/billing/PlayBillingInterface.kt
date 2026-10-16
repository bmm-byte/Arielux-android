package com.arielux.billing

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import com.android.billingclient.api.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Native Google Play Billing bridge exposed to the WebView as `window.PlayBilling`.
 *
 * Google Play Billing Library 8.x / 9.x API. Billing Library 7 (SkuDetails /
 * querySkuDetailsAsync) is DEPRECATED and REMOVED: by Aug 31 2026 every new app
 * or update must use Billing Library 8+. This file uses the v8+ API:
 *   - ProductDetails (replaces SkuDetails)
 *   - queryProductDetailsAsync(QueryProductDetailsParams) (replaces querySkuDetailsAsync)
 *   - BillingClient.ProductType (replaces BillingClient.SkuType)
 *   - BillingFlowParams.setProductDetailsParamsList (replaces setSkuDetails)
 *   - Purchase.products (replaces Purchase.skus, removed in v8)
 *
 * @JavascriptInterface methods run on a WebView binder thread and block (via
 * runBlocking with a timeout) until the async BillingClient result arrives.
 * Billing operations are rare and user-initiated, so a short block is
 * acceptable. Methods return JSON strings because @JavascriptInterface cannot
 * reliably hand complex objects back to JavaScript — the JS bridge
 * (src/lib/playBillingBridge.js) parses them.
 *
 * SECURITY — ORIGIN RESTRICTION (HOST RESPONSIBILITY): a @JavascriptInterface is
 * reachable from ANY page loaded in the WebView, including a malicious page if
 * the WebView ever navigates away from the trusted app origin. The host Activity
 * (com.arielux.wrapper.MainActivity) only injects this interface when the
 * WebView's loaded URL origin is the trusted app
 * (https://aura-your-muse.base44.app or your connected custom domain), and
 * re-checks the URL in a WebViewClient.shouldOverrideUrlLoading /
 * onPageStarted hook, stripping the interface for any off-origin URL before it
 * executes. See native/android/OriginSecurity.md.
 *
 * Wire from the host Activity:
 *   val playBilling = PlayBillingInterface(applicationContext) { currentActivity }
 *   webView.addJavascriptInterface(playBilling, "PlayBilling")
 *   playBilling.start()   // onResume; warm the connection
 * and playBilling.end() in onDestroy.
 */
class PlayBillingInterface(
    private val context: Context,
    private val activityProvider: () -> Activity?
) {
    private val tag = "PlayBilling"
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var client: BillingClient? = null
    @Volatile private var ready = false
    // v8: cache ProductDetails (replaces the SkuDetails cache).
    private val detailsCache = ConcurrentHashMap<String, ProductDetails>()
    // Resolves the launchBillingFlow call from the purchases-updated listener.
    private var purchaseResolver: CompletableDeferred<Result<Purchase>>? = null

    private val pendingListener = PurchasesUpdatedListener { result, purchases ->
        val r = purchaseResolver ?: return@PurchasesUpdatedListener
        purchaseResolver = null
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            r.complete(Result.failure(BillingErr(result.responseCode, result.debugMessage)))
            return@PurchasesUpdatedListener
        }
        val p = purchases?.firstOrNull()
        if (p == null) r.complete(Result.failure(BillingErr(BillingClient.BillingResponseCode.ERROR, "No purchase returned.")))
        else r.complete(Result.success(p))
    }

    /** Warm the BillingClient connection. Call from the host Activity early (onCreate/onResume). */
    fun start() { ensureConnectedAsync() }

    private fun ensureConnectedAsync() {
        if (client?.isReady == true) { ready = true; return }
        if (client == null) {
            client = BillingClient.newBuilder(context)
                .setListener(pendingListener)
                .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                .build()
        }
        client?.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                ready = result.responseCode == BillingClient.BillingResponseCode.OK
                Log.i(tag, "Billing setup finished: ${result.responseCode}")
            }
            override fun onBillingServiceDisconnected() {
                ready = false
                Log.w(tag, "Billing service disconnected")
            }
        })
    }

    /** Suspend until the BillingClient is connected (best-effort, with a timeout). */
    private suspend fun ensureReady(timeoutMs: Long = 8_000) {
        if (ready && client?.isReady == true) return
        ensureConnectedAsync()
        var waited = 0L
        while (!ready && waited < timeoutMs) { delay(100); waited += 100 }
    }

    @JavascriptInterface
    fun isAvailable(): Boolean {
        ensureConnectedAsync()
        return client != null && client?.isReady == true
    }

    // v8+: queryProductDetailsAsync. The callback receives a QueryProductDetailsResult
    // (not a flat list): productDetailsList holds the fetched products and
    // unfetchedProductList holds any that could not be fetched (with a per-product
    // status code). Read fetched products from productDetailsList; log unfetched
    // products rather than failing the whole call.
    //
    // NOTE: compilation was NOT performed in this project (no Kotlin compiler is
    // available here). Verify the exact QueryProductDetailsResult accessors
    // (productDetailsList / unfetchedProductList) against the pinned Billing
    // Library version (8.1.0+ / 9.1.0) at build time.
    private suspend fun queryProducts(productIds: List<String>, type: String): List<ProductDetails> =
        suspendCancellableCoroutine { cont ->
            val productList = ArrayList<QueryProductDetailsParams.Product>()
            for (id in productIds) {
                productList.add(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(id)
                        .setProductType(type)
                        .build()
                )
            }
            val params = QueryProductDetailsParams.newBuilder().setProductList(productList).build()
            val c = client
            if (c == null) {
                cont.resumeWithException(BillingErr(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE, "No billing client."))
                return@suspendCancellableCoroutine
            }
            c.queryProductDetailsAsync(params) { result, productDetailsResult ->
                if (!cont.isActive) return@queryProductDetailsAsync
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    cont.resumeWithException(BillingErr(result.responseCode, result.debugMessage))
                    return@queryProductDetailsAsync
                }
                val fetched = productDetailsResult?.productDetailsList ?: emptyList()
                val unfetched = productDetailsResult?.unfetchedProductList ?: emptyList()
                if (unfetched.isNotEmpty()) {
                    Log.w(tag, "queryProductDetails: ${unfetched.size} product(s) unfetched")
                }
                cont.resume(fetched)
            }
        }

    private suspend fun acknowledge(token: String): BillingResult =
        suspendCancellableCoroutine { cont ->
            val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build()
            val c = client
            if (c == null) {
                cont.resumeWithException(BillingErr(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE, "No billing client."))
                return@suspendCancellableCoroutine
            }
            c.acknowledgePurchase(params) { result -> if (cont.isActive) cont.resume(result) }
        }

    private suspend fun consume(token: String): BillingResult =
        suspendCancellableCoroutine { cont ->
            val params = ConsumeParams.newBuilder().setPurchaseToken(token).build()
            val c = client
            if (c == null) {
                cont.resumeWithException(BillingErr(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE, "No billing client."))
                return@suspendCancellableCoroutine
            }
            c.consumeAsync(params) { result, _ -> if (cont.isActive) cont.resume(result) }
        }

    private suspend fun queryOwned(type: String): List<Purchase> =
        suspendCancellableCoroutine { cont ->
            val c = client
            if (c == null) {
                cont.resumeWithException(BillingErr(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE, "No billing client."))
                return@suspendCancellableCoroutine
            }
            c.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(type).build()) { result, purchases ->
                if (!cont.isActive) return@queryPurchasesAsync
                if (result.responseCode == BillingClient.BillingResponseCode.OK) cont.resume(purchases ?: emptyList())
                else cont.resumeWithException(BillingErr(result.responseCode, result.debugMessage))
            }
        }

    // --- @JavascriptInterface methods (return JSON strings) ---

    @JavascriptInterface
    fun getSkuDetails(skuIdsJson: String): String {
        val ids = ArrayList<String>()
        try {
            val a = JSONArray(skuIdsJson)
            for (i in 0 until a.length()) ids.add(a.getString(i))
        } catch (e: Exception) {
            return err("Invalid product ids.")
        }
        if (ids.isEmpty()) return "[]"
        val out = JSONArray()
        runBlocking {
            try {
                ensureReady()
                val subSkus = ids.filter { it.endsWith("_monthly") }
                val inappSkus = ids.filterNot { it.endsWith("_monthly") }
                if (subSkus.isNotEmpty()) {
                    queryProducts(subSkus, BillingClient.ProductType.SUBS).forEach {
                        out.put(productJson(it)); detailsCache[it.productId] = it
                    }
                }
                if (inappSkus.isNotEmpty()) {
                    queryProducts(inappSkus, BillingClient.ProductType.INAPP).forEach {
                        out.put(productJson(it)); detailsCache[it.productId] = it
                    }
                }
                Unit
            } catch (e: Exception) {
                Log.w(tag, "getSkuDetails error: ${e.message}")
            }
        }
        return out.toString()
    }

    @JavascriptInterface
    fun launchBillingFlow(skuId: String, accountId: String?): String {
        val details = detailsCache[skuId] ?: return err("Unknown product. Call getSkuDetails first.")
        val subscriptionOffer = details.subscriptionOfferDetails?.firstOrNull { it.offerId == null }
            ?: details.subscriptionOfferDetails?.firstOrNull()
        if (details.productType == BillingClient.ProductType.SUBS && subscriptionOffer == null) {
            return err("No eligible subscription offer is available.")
        }
        val activity = activityProvider() ?: return err("No activity available.")
        val resolver = CompletableDeferred<Result<Purchase>>()
        purchaseResolver = resolver
        val launchCode = CompletableDeferred<Int>()
        mainHandler.post {
            // v8: setProductDetailsParamsList replaces setSkuDetails.
            val productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details)
                .apply { subscriptionOffer?.let { setOfferToken(it.offerToken) } }
                .build()
            val params = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(productDetailsParams))
                .apply { if (!accountId.isNullOrBlank()) setObfuscatedAccountId(accountId) }
                .build()
            val r = client?.launchBillingFlow(activity, params)
            launchCode.complete(r?.responseCode ?: BillingClient.BillingResponseCode.ERROR)
        }
        return runBlocking {
            try {
                ensureReady()
                val code = withTimeout(5_000) { launchCode.await() }
                if (code != BillingClient.BillingResponseCode.OK) return@runBlocking err("Launch failed: $code")
                val result = withTimeout(60_000) { resolver.await() }
                result.fold({ purchaseJson(it).toString() }, { err(it.message ?: "Purchase failed.") })
            } catch (e: Exception) {
                purchaseResolver = null
                err(e.message ?: "Purchase timed out.")
            }
        }
    }

    @JavascriptInterface
    fun acknowledgePurchase(purchaseToken: String): String = runBlocking {
        try {
            ensureReady()
            val r = withTimeout(10_000) { acknowledge(purchaseToken) }
            if (r.responseCode == BillingClient.BillingResponseCode.OK) ok() else err("Acknowledge failed: ${r.responseCode}")
        } catch (e: Exception) {
            err(e.message ?: "Acknowledge failed.")
        }
    }

    @JavascriptInterface
    fun consumePurchase(purchaseToken: String): String = runBlocking {
        try {
            ensureReady()
            val r = withTimeout(10_000) { consume(purchaseToken) }
            if (r.responseCode == BillingClient.BillingResponseCode.OK) ok() else err("Consume failed: ${r.responseCode}")
        } catch (e: Exception) {
            err(e.message ?: "Consume failed.")
        }
    }

    @JavascriptInterface
    fun getPurchases(): String {
        val out = JSONArray()
        runBlocking {
            try {
                ensureReady()
                val subs = withTimeout(10_000) { queryOwned(BillingClient.ProductType.SUBS) }
                val inapps = withTimeout(10_000) { queryOwned(BillingClient.ProductType.INAPP) }
                (subs + inapps).forEach { out.put(purchaseJson(it)) }
            } catch (e: Exception) {
                Log.w(tag, "getPurchases error: ${e.message}")
            }
        }
        return out.toString()
    }

    /** Release the BillingClient. Call from the host Activity onDestroy. */
    fun end() {
        try { client?.endConnection() } catch (e: Exception) { Log.w(tag, "endConnection error: ${e.message}") }
    }

    // v8: ProductDetails JSON. The JS contract (playBillingBridge.js) keeps the
    // same field names as the prior SkuDetails shape for compatibility. Verify
    // the exact accessor names (oneTimePurchaseOfferDetails / subscriptionOfferDetails)
    // against the pinned Billing Library version when building.
    private fun productJson(d: ProductDetails): JSONObject = JSONObject().apply {
        put("sku", d.productId)
        put("type", d.productType) // "subs" or "inapp"
        put("title", d.title)
        put("description", d.description)
        val oneTimeOffer = d.oneTimePurchaseOfferDetails
        if (oneTimeOffer != null) {
            put("price", oneTimeOffer.formattedPrice)
            put("price_amount_micros", oneTimeOffer.priceAmountMicros)
            put("price_currency_code", oneTimeOffer.priceCurrencyCode)
        } else {
            val offer = d.subscriptionOfferDetails?.firstOrNull { it.offerId == null }
                ?: d.subscriptionOfferDetails?.firstOrNull()
            val phase = offer?.pricingPhases?.pricingPhaseList?.firstOrNull()
            put("price", phase?.formattedPrice ?: "")
            put("price_amount_micros", phase?.priceAmountMicros ?: 0L)
            put("price_currency_code", phase?.priceCurrencyCode ?: "")
        }
    }

    private fun purchaseJson(p: Purchase): JSONObject = JSONObject().apply {
        put("purchaseToken", p.purchaseToken)
        // v8: Purchase.products replaces the removed Purchase.skus.
        put("productId", p.products.firstOrNull() ?: "")
        put("orderId", p.orderId ?: JSONObject.NULL)
        put("purchaseTime", p.purchaseTime)
        put("purchaseState", p.purchaseState)
        put("acknowledged", p.isAcknowledged)
    }

    private fun ok(): String = JSONObject().put("ok", true).toString()
    private fun err(msg: String): String = JSONObject().put("ok", false).put("error", msg).toString()

    private class BillingErr(val code: Int, msg: String) : Exception(msg)
}