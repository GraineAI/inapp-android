# Graine in-app agent for Android

An AI agent inside your Android app: it sees the screen you describe, acts
through the actions you register, draws components (product lists, choices,
time slots), and talks by voice. Kotlin, Android 7.0 (API 24) and up, and **no
runtime dependencies** — no AndroidX, no OkHttp, no Compose — so it drops into
any app without a version conflict.

The call runs in a page Graine hosts, inside a WebView this SDK owns, one
invisible pixel big. Every audio fix (TURN relay, dead-media watchdog,
WebSocket fallback, connection quality) reaches your app without a release.

Integration guide: https://www.graine.ai/docs/in-app/android

## Install

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("ai.graine:inapp-android:0.6.1")
}
```

Or build it from this repository: `./gradlew :graine-inapp:publishToMavenLocal`,
then add `mavenLocal()` to your repositories.

The library's manifest adds `INTERNET`, `RECORD_AUDIO` and
`MODIFY_AUDIO_SETTINGS` — and, for [calls](#calls), `POST_NOTIFICATIONS`,
`USE_FULL_SCREEN_INTENT`, `FOREGROUND_SERVICE` and
`FOREGROUND_SERVICE_MICROPHONE` with the call screen, service and receiver —
and its ProGuard rule ships inside it. In the
dashboard, add `app://` to the agent's **Allowed domains** (Embed & Widgets).

## Set up

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val graine = GraineInApp.init(this, GraineConfig(
            baseUrl = "https://www.graine.ai",
            publishableKey = "pk_live_…",          // Agent → Embed & Widgets
        ))
        graine.registerAction("open_screen") { args ->
            ActionResult.ok("Opened ${args.optString("path")}.")
        }
    }
}
```

In your first activity, mount the engine once and add the bar:

```kotlin
GraineInApp.get().voice.attach(this)
root.addView(GraineAgentBarView(this),
    FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
```

The engine then follows the customer from activity to activity; a call in
progress carries on. Tell the agent what is on screen with `setScreen`, and
see the guide for components, friction, Jetpack Compose and Java.

With no call to end, the bar's end button is a close. Say what closing means
in your app — hide the bar, show your launcher again:

```kotlin
bar.onClose = { bar.visibility = View.GONE }
```

## How it looks

The dashboard decides: the design the brand chose in the Garage (a philosophy
and a few knobs) reaches the SDK as light and dark tokens, and the bar, its
caption, the components, the loader and `GraineLauncherView` draw them —
colours, corners, outlines, shadows or hard offsets, glass, motion, the icon
style, the typeface, and window chrome (the caption, the components and the
chat opening as desktop windows). The voice bar is the Garage's phone preview, element
by element. The brand's logo, where it stands in for the launcher glyph, is
drawn whole — fitted, never cropped — on the plate the dashboard chose for it:
the accent, or white with a hairline. Nothing to configure, and nothing in the
app names a font: the SDK fetches the design's faces from Graine itself, keeps
them on disk, and draws in them once they arrive (the system font until then).

A family your app already bundles can be mapped instead, and is then never
downloaded:

```kotlin
GraineConfig(
    baseUrl = "https://www.graine.ai",
    publishableKey = "pk_live_…",
    fonts = mapOf("Inter" to Typeface.createFromAsset(assets, "fonts/Inter.ttf")),
)
```

An app drawing its own UI reads the same tokens with
`GraineInApp.get().themeTokens(context)`, and the call's state — including
`voice.phase` (listening, thinking, speaking…) — from `GraineInApp.get().voice`.
While a call is being placed, `voice.connectStage` says where it is
(registering, dialling, retrying, switching), `voice.slowConnect` turns true
once the customer has waited 4 s (`GraineConfig.Timing.slowConnectMs`), and
`voice.callFailed` means nothing could carry the call and `start()` tries
again; `GraineListener.onConnectProgress` hears each change. The logo's plate
is `Appearance.resolve(…).logoPlate`, and `Logo.box` / `Logo.plate` size and
fill it as the bar does.

## Calls

A WhatsApp-style call with your agent — your agent rings the customer, or the
customer calls it — runs natively: the phone's own microphone, speaker and echo
canceller, in your app rather than in a web view. It rings over the lock
screen, keeps going with the screen off, and reports what became of it. The
library's manifest already declares everything (the permissions, a call screen,
a `microphone` foreground service and a receiver); nothing to add.

```kotlin
// Application.onCreate, after GraineInApp.init:
GraineInApp.get().calls.configure(GraineCallsConfig(
    displayName = "Acme Support",           // the ring and the call screen
    smallIcon = R.drawable.ic_stat_call,    // monochrome status-bar icon
    // ringtone = Uri (default: the phone's ringtone), ringTimeoutMs = 30_000,
    // callActivity = YourCallActivity::class.java (default: the SDK's screen)
))
```

**Your agent rings the customer.** Your server sends an FCM *data* message
(high priority) with string values:

```json
{ "type": "agent_call", "call_id": "call_8412", "reason": "payment_due",
  "caller_name": "Acme Support", "variables": "{\"order_id\":\"A-17\"}" }
```

`call_id` is required and comes back with every outcome; `reason` becomes the
prompt's `call_reason`; `variables` (a JSON object, as a string) become prompt
variables; answering sets `who_called = "agent"`. Hand every message to the SDK
first — the app does not depend on Firebase, the SDK does not either:

```kotlin
class Messaging : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        if (GraineInApp.get().calls.handlePush(message.data)) return
        // …your own messages
    }
}
```

**The customer calls the agent** (`who_called = "customer"`), from a screen in
front:

```kotlin
val callId = GraineInApp.get().calls.startCall(activity, reason = "kyc_help",
    variables = mapOf("order_id" to "A-17"))   // null when a call is already live
```

**What became of it**, for your server — on the main thread:

```kotlin
GraineInApp.get().calls.addListener(object : GraineCallListener {
    override fun onCallOutcome(callId: String, outcome: GraineCallOutcome) {
        // outcome.wire: answered · declined · missed · ended (endReason local|remote|failed) · failed (error)
    }
})
```

`calls.state` (idle, ringing, connecting, active, ended) and `onCallState`,
`onCallCaption`, `onAgentSpeaking` drive a screen of your own; `answer()`,
`decline()`, `end()`, `setMuted()` and `setSpeaker()` control the call. One call
at a time: a push during a call is reported `missed`, and a native call ends any
web view call first.

- Android 13+: ask for `POST_NOTIFICATIONS` (during onboarding) — without it the
  phone cannot ring. The microphone is asked for on the call screen if needed.
- Android 14+: the full-screen call screen needs `USE_FULL_SCREEN_INTENT`, which
  Play grants to calling apps; without it the call rings as a heads-up
  notification. Play also asks for a declaration of the `microphone`
  foreground service. An app that never uses calls can drop these entries with
  `tools:node="remove"`.
- Your own call screen (`callActivity`) draws `calls.state` and calls the same
  methods; forward its `onRequestPermissionsResult` to
  `calls.onRequestPermissionsResult`.

## The sample app

`sample/` is a KYC screen with the bar, a second activity, and demo
components. Put your publishable key in `local.properties` (never committed):

```properties
graine.publishableKey=pk_live_…
```

then `./gradlew :sample:installDebug`.

## Tests

```bash
./gradlew :graine-inapp:testDebugUnitTest
```

`contract/fixtures.json` and `contract/rn-parity.json` hold answers generated by
the React Native SDK itself — masking, connection quality, the engine URL,
component looks, drag placement, friction, the dashboard's appearance and the
logo's plate — and every Graine SDK must reproduce all of them.
`contract/design.json` holds every design the dashboard offers, resolved; the
tests parse each one. `contract/native-calls.json` holds the call engine's test
vectors, generated by the React Native SDK; the call engine passes every one.

A real call against your agent, on the JVM, with a WAV file as the customer:

```bash
GRAINE_LIVE_E2E=1 GRAINE_E2E_KEY=pk_live_… GRAINE_E2E_WAV=question-16k.wav \
  ./gradlew :graine-inapp:testDebugUnitTest --tests 'ai.graine.inapp.LiveCallE2ETest' --rerun
```

## License

MIT — see `LICENSE`.
