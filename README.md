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
    implementation("ai.graine:inapp-android:0.7.0")
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
see the guide for components, friction, Jetpack Compose and Java. A screen
described once can be kept current field by field with `updateField` and
`removeField`; taps on labelled controls (their content description, view id
or button text) are noticed by themselves (`GraineConfig.captureTaps`).

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
variables; answering sets `who_called = "agent"` (`"scheduled"` for a call Graine
rang at a booked time). Hand every message to the SDK
first — and its token, so Graine itself can ring your users (below). The SDK
does not depend on Firebase; two lines in your messaging service:

```kotlin
class Messaging : FirebaseMessagingService() {
    override fun onNewToken(token: String) = GraineCalls.onNewToken(this, token)
    override fun onMessageReceived(message: RemoteMessage) {
        if (GraineCalls.handlePush(this, message.data)) return
        // …your own messages
    }
}
```

**Graine rings your users itself** — a call the agent booked in a
conversation, one the user booked in your app, or one booked from the
dashboard — once your push credentials are uploaded under Workspace settings →
In-app calling. With the two lines above the SDK registers the device (and who
uses it, from `identify`), rings those calls exactly as your own ("Scheduled
call" on the ring), and tells Graine what became of each. Nothing else to send
from your server — you need no server at all.

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

**The call screen is your brand's**: the design's call tokens (Garage → Call
screen) draw its background, avatar, colours, words, controls and ring (the
brand's `graine` or `soft` tone, or the phone's ringtone), and the last design is
kept on the device so a push that wakes a closed app draws it too. A
ring nobody answers stays on "Missed call" with **Call back**, and leaves a
missed-call notification with the same action.

## Who the user is, and memory

```kotlin
graine.identify(mapOf("user_id" to user.id, "name" to user.firstName))   // after sign-in
graine.identify(mapOf("user_id" to user.id), memory = false)             // this user: remember nothing
graine.identify(null)                                                    // sign-out
```

Traits become prompt variables, masked, as they always did. The one the agent's
**Identity** setting names (`user_id` unless the dashboard chose `email`,
`phone` or a field of yours) is how Graine tells your users apart: the agent
remembers a user across conversations and devices, and can ring them back. It
is sent only with the SDK's own requests to Graine, and kept out of the
prompt's variables. `setMemory(enabled)`, `memories { list, error -> }` (what the
agent remembers about the signed-in user, newest first) and
`forgetMemory { deleted, error -> }` (how many were deleted) are there for your
privacy screen; the memory choice is kept on this install.
`installId` is a random id made on first launch.

## Chat

```kotlin
GraineChatActivity.open(context)                         // a chat screen in your brand's design
// or, in your own layout:
root.addView(GraineChatView(context).apply { onClose = { finish() } })
```

The same agent, by text: it sees the screen, runs your actions and draws its
components in the chat. The voice bar switches between the call and the chat as
the dashboard's mode says (voice, chat or both); `bar.chat = null` keeps it to
the call. The chat is drawn in the brand's chat design (Garage → Chat window),
and shows the user's booked calls on an Upcoming calls card they can cancel from. `GraineInApp.get().chat` is the conversation itself (`messages`,
`send`, `connect`, `close`), and a `ChatSource` of your own can stand in its place.

### Conversations continue

A user you `identify()` who comes back — later today, tomorrow, on their other
phone — finds the chat where they left it, and the agent picks up from there
instead of greeting them again. The chat opens with the recent messages (the
last 50, kept for a while after the last one), earlier calls show as one
"Voice call · 6:05 PM" row, and day lines say Today, Yesterday or the date.
Drawing your own chat? `turn.restored` marks a message from an earlier
conversation and `turn.channel` (`CHAT`, `VOICE`) where it was said.
`chat.newConversation { _, error -> }` starts a fresh thread (the chat window's
header offers it while an earlier conversation is on screen); what the agent
remembers about the user stays. `forgetMemory` clears the chat on screen too.
Anonymous users, and those with memory off, start fresh every time; each agent
in your app keeps its own conversation.

## A bigger chat window

How big the bar's chat opens is set per agent in the dashboard — **Embed &
Widgets → Appearance → Chat window → Window size** — and the voice bar draws it
with no code:

| Size | On a phone | On a tablet |
|---|---|---|
| Compact | The window above the bar, as always | The same |
| Large | A sheet over most of the screen, the app dimmed behind it; drag it down, tap outside or press Back to close | A panel 420 dp wide in the bottom corner |
| Full screen | The whole screen, closed with the chevron at the top left or Back | The whole screen |

Your app can choose otherwise, for every chat or for one:

```kotlin
GraineInApp.init(application, GraineConfig(baseUrl = "https://www.graine.ai", publishableKey = "pk_live_…",
    chatSize = GraineChatSize.LARGE))

GraineInApp.get().chat.open(GraineChatSize.FULL)   // opens the chat now, at this size, this once
```

The size passed to `open` wins until the chat closes, then `GraineConfig.chatSize`,
then the dashboard's. A voice bar on screen moves to the chat when you open it
this way, and switching between the call and the chat keeps the size.
`GraineChatActivity.open(context)` and `GraineChatView` stay as they are, for a
chat you place yourself.

## Scheduled calls

```kotlin
val rules = graine.schedulingRules                       // usersCanBook, earliest(now), latest(now), …
graine.scheduleCall(at, reason = "Review the KYC documents") { call, error -> }   // GraineScheduledCall
graine.scheduledCalls { calls, error -> }                                         // upcoming first
graine.cancelScheduledCall(call.id) { status, error -> }                          // GraineCallStatus.CANCELLED
```

When the agent lets users book (Calls & scheduling), the call rings this user
at that time, within the agent's calling hours. A time outside
`schedulingRules.earliest()`…`latest()`, or booking switched off, is refused on
the device before anything is sent; the rest comes back as a `GraineError`
whose `code` is a `GraineErrorCode`: `SCHEDULING_OFF`, `TOO_SOON`, `TOO_FAR`,
`OUTSIDE_WINDOW`, `TOO_MANY_PENDING`, `NETWORK`… A `GraineScheduledCall` has
`id`, `at`, `status` (`SCHEDULED`, `RINGING`, …, `NO_DEVICE`, or `Unknown`),
`source` (`AGENT`, `APP`, `DASHBOARD`, `API`), `reason`, `isUpcoming` and `canCancel`.

## Several agents in one app

An app can run several agents, each with its own publishable key, and pick one
per screen — a support agent in Help, a sales agent on Plans:

```kotlin
GraineInApp.init(this, GraineConfig(baseUrl, "pk_live_support…"))                 // the default agent
GraineInApp.init(this, GraineConfig(baseUrl, "pk_live_sales…"), name = "sales")    // another one

GraineInApp.get("sales").voice.attach(activity)                        // its engine, as for the default agent
bar.graine = GraineInApp.get("sales")                                  // a voice bar for the sales agent
GraineChatView(context).apply { source = GraineInApp.get("sales").chat }
GraineChatActivity.open(context, agent = "sales")
GraineInApp.get().calls.startCall(activity, reason = "upgrade", agent = "sales")
```

Each agent has its own session (design, actions, scheduling rules, memory
setting), chat, voice bar, registered actions, and scheduling and memory calls,
all sent with its own key. What belongs to the app is shared: the install id,
who the user is (`identify` on any agent identifies them for every agent, and
`identify(null)` signs out of every one), their memory choice, the screen
(`setScreen` on any agent; each sends it on while it has a live conversation)
and the one call engine. One conversation at a time across all of them. A ring
Graine sends names its agent, and rings with that agent's design (kept on the
device for a cold start); the device is registered with every agent's key. An
app with one agent names none: `init` makes the default, `get()` returns it.
`GraineInApp.find(name)` and `GraineInApp.all` list what was made.

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
