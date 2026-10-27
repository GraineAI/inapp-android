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

# Play Integrity is optional the same way ("Require a verified app"): the one
# file that asks it for a token (PlayIntegrity.kt) is compiled against it, and
# an app without it never loads that file. Its classes are missing from such an
# app on purpose. The SDK looks for the library's factory by its name, so the
# factory keeps its name in an app that has it.
-dontwarn com.google.android.play.core.integrity.**
-dontwarn com.google.android.play.core.common.**
-keepnames class com.google.android.play.core.integrity.IntegrityManagerFactory

# Coroutines are optional too: only GraineInApp.verificationFlow names them
# (coroutines/VerificationFlow.kt), and an app that never reads it never loads it.
-dontwarn kotlinx.coroutines.flow.**
