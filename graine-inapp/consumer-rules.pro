# The page calls these through addJavascriptInterface. R8 renaming or removing
# them would leave a bridge that compiles, loads, and never delivers a message.
-keepclassmembers class ai.graine.inapp.** {
    @android.webkit.JavascriptInterface <methods>;
}
