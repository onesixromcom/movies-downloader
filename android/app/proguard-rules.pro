# Methods called from the WebView JavaScript (BrowserFetcher bridge).
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface

# Keep class names readable in crash logs and the app log.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
