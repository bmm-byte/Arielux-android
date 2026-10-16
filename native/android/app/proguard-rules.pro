# Keep the @JavascriptInterface bridge and its methods — JS calls them by name.
-keep class com.arielux.billing.PlayBillingInterface { *; }
-keepclassmembers class com.arielux.billing.PlayBillingInterface {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep the Billing Library API surface used via reflection / JSON marshalling.
-keep class com.android.billingclient.api.** { *; }
-dontwarn com.android.billingclient.api.**