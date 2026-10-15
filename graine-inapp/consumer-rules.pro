# The page calls these through addJavascriptInterface. R8 renaming or removing
# them would leave a bridge that compiles, loads, and never delivers a message.
-keepclassmembers class ai.graine.inapp.** {
    @android.webkit.JavascriptInterface <methods>;
}

# Firebase Cloud Messaging is optional: the SDK's messaging service and its
# token source (GraineMessagingService.kt) are compiled against it, and an app
# without Firebase never loads them. Its classes are missing from such an app
# on purpose, which R8 must not stop the build for.
-dontwarn com.google.firebase.messaging.**
-dontwarn com.google.android.gms.tasks.**
