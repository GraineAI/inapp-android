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
    implementation("ai.graine:inapp-android:0.14.7")
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
`voice.phase` (listening, thinking, speaking…), `voice.captions` and the CC switch
`voice.captionsOn` (`setCaptionsOn(on)`, `clearCaptions()`) — from `GraineInApp.get().voice`.
While a call is being placed, `voice.connectStage` says where it is
(registering, dialling, retrying, switching), `voice.slowConnect` turns true
once the customer has waited 4 s (`GraineConfig.Timing.slowConnectMs`), and
`voice.callFailed` means nothing could carry the call and `start()` tries
again; `GraineListener.onConnectProgress` hears each change. A voice UI of your own
calls `voice.viewOpened()` / `viewClosed()` as it comes and goes (a slow page load is then
reported while it is open), and `voice.attached` says whether the engine is mounted. The logo's plate
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
rang at a booked time).

**Add Firebase to your app; the SDK registers for pushes itself.** Add
Firebase Cloud Messaging as Firebase's own setup describes it:
`google-services.json` in the app module, the Google services Gradle plugin,
and the messaging library:

```kotlin
// app/build.gradle.kts, with the Google services plugin applied
dependencies {
    implementation(platform("com.google.firebase:firebase-bom:<latest>"))
    implementation("com.google.firebase:firebase-messaging")
}
```

That is all. The SDK's manifest brings its own messaging service
(`ai.graine.inapp.GraineMessagingService`), which receives every new token and
rings each call push, and the SDK reads the current token when it starts. The
SDK itself does not depend on Firebase: an app without it builds and runs as
before, without calls that ring.

**Already have your own `FirebaseMessagingService`?** Android delivers each
message to one service, and yours outranks the SDK's (declared at priority
-1). Keep yours and hand Graine's over, two lines:

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
In-app calling. With Firebase in the app the SDK registers the device (and who
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

### Permissions and setup: what the SDK does, what your app adds

**The SDK does by itself:** merges every manifest entry a call needs (the
permissions, the call screen, the `microphone` foreground service, Answer and
Decline, and a Firebase messaging service that receives the push token and
Graine's calls), creates the notification channels, rings over the lock screen, keeps
the call going with the screen off, asks for the microphone on the call screen
when a call starts without it, registers the device with Graine and reports
every outcome — and checks your setup at `configure`, logging each problem once
in logcat (`Calls setup (code): …`) with the exact fix.

**Your app adds:** Firebase Cloud Messaging (`google-services.json`, the
Google services plugin and the messaging dependency) — no messaging service of
your own — and, at a moment you choose, never needed at launch, one call that
asks for the permissions:

```kotlin
// Onboarding, or a "Call me" screen.
GraineInApp.get().calls.requestCallPermissions(activity) { p ->
    // p.microphone, p.notifications (Android 13+), p.fullScreen (Android 14+), each
    // GRANTED · DENIED (not yet) · BLOCKED (only Settings can change it) · NOT_NEEDED
    if (p.fullScreen == GraineCallPermissionStatus.BLOCKED) {
        // After a sentence of your own: "Allow full-screen calls so Acme can ring your locked phone."
        GraineInApp.get().calls.openCallSettings(GraineCallPermission.FULL_SCREEN)
    }
}
```

It asks from a transparent screen of the SDK's own, so no activity forwards a
permission result. The full-screen ring has no dialog on Android 14+:
`openCallSettings(FULL_SCREEN)` opens its page (`NOTIFICATIONS` the app's
notification settings, `MICROPHONE` the app's page). `calls.callPermissions`
reads all three at any time — read it again in `onResume`, as the customer may
have changed Settings. `allGranted` is true when each is granted or not needed.

**What is still missing**, in plain words — the same list the log shows:

```kotlin
GraineInApp.get().calls.callSetupProblems.forEach { Log.w("Calls", "${it.code}: ${it.message}") }
```

| Code | What it means |
| --- | --- |
| `manifest_entry_missing` | The merged manifest lost a call entry (look for `tools:node="remove"`) |
| `no_push_token` | No FCM token reached the SDK 15 seconds after `configure`: Firebase is missing or not set up in the app (the message says which), or an app with its own messaging service never handed one to `GraineCalls.onNewToken` |
| `notifications_not_allowed` | A call can't ring while the app is closed or in the background |
| `full_screen_not_allowed` | A locked phone rings with a heads-up notification instead of the call screen |
| `microphone_blocked` | Only Settings can let calls hear the customer |
| `hosted_ringing_off` | Graine can't ring this phone: no push credentials uploaded for Android |

A problem is never an exception: calls still work where they can. Play asks
for a declaration of the `microphone` foreground service; an app that never
uses calls can drop the call entries with `tools:node="remove"` (the messaging
service, `ai.graine.inapp.GraineMessagingService`, among them). Your own call
screen (`callActivity`) draws `calls.state` and calls the same methods; forward
its `onRequestPermissionsResult` to `calls.onRequestPermissionsResult`.

**The call screen is your brand's**: the design's call tokens (Garage → Call
screen) draw its background, avatar, colours, words, controls and ring (the
brand's `graine` or `soft` tone, or the phone's ringtone), and the last design is
kept on the device so a push that wakes a closed app draws it too. A
ring nobody answers stays on "Missed call" with **Call back**, and leaves a
missed-call notification with the same action.

### Calls over WebRTC

Add the WebRTC module beside the SDK, and calls carry their audio over WebRTC:
a jitter buffer and loss concealment, a path that survives a moment's dropout,
and the phone's own echo canceller. Nothing else changes — not your code, the
ring, the call screen or the outcomes.

```kotlin
dependencies {
    implementation("ai.graine:inapp-android:0.14.7")
    implementation("ai.graine:inapp-android-rtc:0.14.7")   // same version as the SDK
}
```

- **What the person notices:** a call that holds up better on mobile data and
  connects as fast as before — it is prepared the moment they tap, or while the
  phone rings. If WebRTC cannot be set up for a call, the call simply goes on
  over the WebSocket inside the same ring and screen: no second ring, no error.
  The agent's voice arrives at telephone bandwidth on WebRTC today: steadier
  through a weak network, narrower than the WebSocket's on a perfect one.
- **Choose** with `GraineCallsConfig(callTransport = …)`: `GraineCallTransport.AUTO`
  (the default — WebRTC when the module is in the app), `WEBSOCKET` or `WEBRTC`.
  The agent's **In-app call audio** setting in the dashboard can switch WebRTC
  off for every install at once, without a release.
- **Which one carried a call:** `calls.transport` (`"webrtc"` or `"websocket"`)
  and, when a call that would have used WebRTC is on the WebSocket,
  `calls.transportFallbackReason` (`mint_failed`, `register_failed`,
  `no_response`, `no_answer`, `ice_timeout`, `error`, `dashboard`,
  `unavailable`). Read them once the call is `ACTIVE`.
- **Size:** WebRTC's native library, per phone (an app bundle delivers one
  ABI): about 7.4 MB installed on 64-bit ARM (about 2.5–3.3 MB to download),
  4.3 MB on 32-bit ARM, 8.2–8.4 MB on x86; and about 0.5 MB of classes.
- The module's manifest adds `ACCESS_NETWORK_STATE` (a normal permission,
  granted at install, nothing asked); its R8 rules ship inside it. Licences:
  `graine-inapp-rtc/THIRD-PARTY-NOTICES.md` (WebRTC, BSD 3-Clause) — include it
  in your app's open-source licences screen.

## Who the user is, and memory

```kotlin
graine.identify(mapOf("user_id" to user.id, "name" to user.firstName))   // after sign-in and at every launch
graine.identify(mapOf("user_id" to user.id), memory = false)             // this user: remember nothing
graine.identify(null)                                                    // sign-out
```

Traits become prompt variables, masked, as they always did. The one the agent's
**Who is the user?** setting names (Agent → Memory; `user_id` unless the
dashboard chose `email`, `phone` or a field of yours) is how Graine tells your
users apart: the agent remembers a user across conversations and devices, and
can ring them back. Send exactly that key, before the chat or a call opens. It
is sent only with the SDK's own requests to Graine, and kept out of the
prompt's variables; a placeholder (`"guest"`, a masked value) is nobody, and
that conversation has no memory.

**How memory reaches the agent.** At the start of each chat, Talk session or
call — in-app calls in both directions too — the agent gets what it remembers
about the user: after its prompt for a prompt agent, in the flow's Briefing
(which every step reads) for a flow agent. During the conversation it looks
again only when the user refers to an earlier one ("last time", "I told you").
New facts are ready about a minute after a conversation ends. `memory = false`
keeps a user out of memory entirely, from their next conversation.

`setMemory(enabled)`, `memories { list, error -> }` (what the
agent remembers about the signed-in user, newest first) and
`forgetMemory { deleted, error -> }` (how many were deleted) are there for your
privacy screen; the memory choice is kept on this install. Both wait for the
agent's settings — they name the trait that identifies the user — so a screen
opened right after launch gets the answer, not "nobody is identified". When
they refuse, `error.code` says why:

| Code | What it means |
| --- | --- |
| `IDENTITY_REQUIRED` | Nobody is identified by the trait this agent identifies users by; the message names it. `identify() was given no usable "patient_id"…` is the usual mistake: `user_id` sent to an agent that identifies users by `patient_id` (logcat says so too, when `identify()` or the session lands) |
| `DEVICE_LIMIT` | This phone is not one of the user's devices for this agent |
| `UNAVAILABLE`, `NETWORK` | Graine, or the agent's settings, could not answer: try again — the next call loads the settings again |
| `INVALID_KEY`, `ORIGIN_REFUSED` | The agent's settings were refused: check the key, and `app://` in its Allowed domains |

Whether the agent remembers anyone at all is `session?.memoryEnabled` (false:
memory is off for this agent).
`installId` is a random id made on first launch. What is kept, the settings and
the prompt lines that make the agent use it: https://www.graine.ai/docs/in-app/memory

## Secure your agent: your app and your users

New in 0.14.4. Two protections, each switched on per agent in the dashboard
(Agent → Embed & Widgets → Install), each with a report-only stage before it
refuses anything, and each off until you turn it on. With neither on, nothing
changes.

### Verified users: identify with a token

Your server proves who the user is with an **identity token**: an HMAC-SHA256
of the identity value (the trait the agent's *Who is the user?* setting names)
keyed with the agent's user secret — computed on your server, never in the app,
so the secret never ships — or the ID token (JWT) of your own sign-in. Hand it
to `identify`; the SDK sends it only inside the identity, beside the value it
proves, and never in variables, metadata, logs or a URL.

```kotlin
// After sign-in (and at every launch): the token your server minted for this user.
graine.identify(mapOf("user_id" to user.id, "name" to user.firstName), identityToken = session.graineToken)

// Your sign-in renewed it: replace it without identifying anyone else (null clears it).
graine.setIdentityToken(renewedToken)

// Let the SDK ask for a fresh one: when Graine says it expired, when a request is refused,
// before a conversation whose JWT has less than a minute left, when a call rings — and, for a
// user identified without a token, before their first conversation (waiting up to 10 seconds).
graine.setIdentityTokenRefresher { api.graineToken() }        // a suspend function: the fresh token, or null

// The same with a callback (and from Java):
graine.identityTokenRefresher = GraineIdentityTokenRefresher { done ->
    api.graineToken { token -> done.onToken(token) }          // once, from any thread; null keeps the current one
}

// Sign-out takes the token with it; another user never gets the last one's.
graine.identify(null)
```

The refresher is called on the main thread, at most once every 30 seconds and
never twice at once. When the user was identified without a token and the agent
checks its users, a conversation — the chat, the voice bar, a call your app
places — the user's memories, past sessions, booked calls, workflows and a new
conversation, and this device's registration for the calls Graine rings wait up
to 10 seconds for its answer and go out with the token it gives; a registration
that went without one is made again when the token arrives, so the user is rung
on this device. Answering a call that rings never waits. A conversation already
open is never cut when a token lapses. Graine answers with a user pass that the SDK keeps for an hour and
sends with the same identity; another user, or sign-out, drops it at once.

### Your apps: prove the app with Play Integrity

Every request already says which app it is — the package name and the SHA-256
of each certificate it is signed with — so an agent with **Only my apps** on
turns away any other app. To **require a verified app**, add Play Integrity
(the SDK only compiles against it, so apps that do not use it never carry it):

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("ai.graine:inapp-android:0.14.7")
    implementation("com.google.android.play:integrity:1.4.0")
}
```

The SDK then proves the app in the background once the agent's settings arrive,
keeps the app pass Graine gives for 24 hours, and renews it when less than six
hours remain. Prove it on first launch, so the first conversation never waits:

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val graine = GraineInApp.init(this, GraineConfig("https://www.graine.ai", "pk_live_…"))
        graine.prepareAppVerification { verdict -> Log.i("App", "app verification: ${verdict.word}") }
    }
}
```

Set it up once:

1. **Play Console** → your app → **Test and release** → **App integrity** → link
   a Google Cloud project to the Play Integrity API.
2. In that project in the Google Cloud console, **enable the Play Integrity
   API**, and give Graine the package's Play Integrity credentials in the
   dashboard when it asks for them. If you linked a Cloud project of your own,
   enter its project number on the agent too.
3. Add your app under the agent's **Your apps**: the package name and the
   SHA-256 fingerprints of its certificates (with or without colons). Read
   them in **Play Console** → **Test and release** → **App integrity** → **App
   signing key certificate** (and the **Upload key certificate**, for builds you
   sign yourself); for a debug build, `./gradlew signingReport` prints the
   SHA-256 of the debug key.

**Test devices and debug builds.** Emulators, sideloaded debug builds and CI
cannot pass Play Integrity, so a debuggable build — every build Android Studio
runs — sends a **debug token** instead and never asks Play Integrity. The SDK
logs it once per install: *Graine debug token for this install: gdt_… —
register it under Agent → Embed & Widgets → Install → Development → Debug
tokens…* Register it there (it expires after 30 days by default). Graine also
lists **test users** there, accepted without an identity token only from an
install whose debug token is registered. A phone running a build installed from
Play (an internal testing track) proves itself like any user's.

```kotlin
graine.debugToken                                         // this install's token, or null in a release build
graine.debugTokenMode = GraineDebugTokenMode.ALWAYS       // an internal QA build signed for release
graine.debugTokenMode = GraineDebugTokenMode.NEVER        // a debug build that proves itself (with "Allow development builds")
```

### What Graine has proven

```kotlin
val v = graine.verification                       // v.app: OFF, CHECKING, VERIFIED, TEST_BUILD, DEBUG_TOKEN, UNVERIFIED
graine.addVerificationListener { v ->             // v.user: OFF, VERIFIED, TEST_USER, UNVERIFIED, INVALID
    Log.i("App", "app ${v.app.word} ${v.appReason ?: ""}, user ${v.user.word} ${v.userReason ?: ""}")
}
lifecycleScope.launch { graine.verificationFlow.collect { render(it) } }   // the same, as a StateFlow
```

When a protection refuses, the SDK tries what it can first — it proves the app,
or asks your refresher, and sends the request once more — and then `error.code`
says why:

| Code | What it means |
| --- | --- |
| `APP_NOT_ALLOWED` | This app is not one of the agent's apps (*Only my apps*): add its package and fingerprints under **Your apps**. Final — the chat or call ends with it, and nothing tries again by itself |
| `APP_NOT_VERIFIED` | The agent requires a verified app and this install has no valid app pass: add Play Integrity, check its setup, or register this build's debug token |
| `IDENTITY_UNVERIFIED` | The agent requires verified users and this identity is not verified: pass an identity token to `identify`, or set `identityTokenRefresher`. Memories, past sessions and booking are refused; a conversation goes ahead without memory |

## Server tokens

The strongest mode: your server vouches for the signed-in user. It mints a
short-lived token for one user of one agent — `POST /v2/client-tokens` with
your secret API key: the `agent_id`, the `user_id` your app passes to
`identify()`, the `scopes` the app needs (`chat`, `call`, `memory`, `booking`,
`workflows`) and a `ttl_seconds` of 60–3600 — and the SDK sends it with every
request to Graine. Once the agent's **Require server tokens** is enforced
(Embed & Widgets), the publishable key alone opens nothing.

```kotlin
GraineInApp.init(this, GraineConfig(
    baseUrl = "https://www.graine.ai",
    publishableKey = "pk_live_…",
    tokenProvider = GraineTokenProvider { done ->
        // Your server mints it for the signed-in user; null when there is none right now.
        backend.graineToken(onResult = { token -> done(token) }, onError = { done(null) })
    },
))
// Or from a suspending function: tokenProvider = GraineTokenProvider.suspending { backend.graineToken() }
```

- The SDK asks at start, again shortly before the token expires (in the
  background, with a little jitter, and after a failure again in 2, 4, 8 …
  60 seconds), when a call rings, when `identify()` names another user or
  nobody (the token held is let go of: it spoke for the user before), and
  when Graine refuses a token — one
  question at a time, however many requests wait for it. Nothing is asked
  while the app is in the background; back in front, a token close to its
  expiry is refreshed. The SDK reads only the token's `iat` and `exp`, and
  never keeps it on the device.
- `fetch` is called on a background thread; call `done` once, from any thread.
  Throwing, or no answer within 15 seconds, counts as null.
- A token that expires never ends, pauses or reconnects a live chat or call:
  tokens are checked when a conversation starts. A new conversation waits at
  most 10 seconds for a fresh one, then goes with the newest it has.
- Refused for its token (`TOKEN_REQUIRED`, `TOKEN_INVALID`, `TOKEN_EXPIRED`), a
  request asks for a fresh token and goes once more. `TOKEN_SCOPE` and
  `KEY_REVOKED` are final. A request's callback hears the code, with
  `error.reason` saying which part failed; a chat that cannot connect sets
  `chat.error` (and `chat.failed`) and does not retry by itself; a call that
  cannot start fails with the code at the start of its error.
- Without a `tokenProvider` nothing is sent and nothing changes.

| Code | What it means |
| --- | --- |
| `TOKEN_REQUIRED` | The agent requires a server token and none came with the request: set `tokenProvider` |
| `TOKEN_INVALID` | The token could not be checked — `reason`: `malformed`, `signature`, `unknown_key`, `issuer` or `not_yet_valid` |
| `TOKEN_EXPIRED` | The token expired, and a fresh one did not come in time |
| `TOKEN_SCOPE` | The token is another agent's (`agent`) or another user's (`user`), or lacks the scope this needs (`scope`) |
| `KEY_REVOKED` | This publishable key was revoked: update the app with the agent's new key |

React Native and Flutter apps whose own code fetches the tokens push each one to
the native call engine with `GraineCallsHost.setClientToken(token)` (their
packages do it) and hear `needClientToken` when one is refused; an agent
configured with its own `tokenProvider` keeps its own.

## Chat

```kotlin
GraineChatActivity.open(context)                         // a chat screen in your brand's design
// or, in your own layout:
root.addView(GraineChatView(context).apply { onClose = { finish() } })
```

The same agent, by text: it sees the screen, runs your actions and draws its
components in the chat (drawing one yourself, from `onWidget`? answer it with
`graine.answerWidget(widgetId, data, summary)`, on whichever conversation is live). The voice bar switches between the call and the chat as
the dashboard's mode says (voice, chat or both); `bar.chat = null` keeps it to
the call. The chat is drawn in the brand's chat design (Garage → Chat window),
and shows the user's booked calls on an Upcoming calls card they can cancel from. `GraineInApp.get().chat` is the conversation itself (`messages`,
`send`, `connect`, `close`, and `connected`, `connecting`, `reconnecting` while a dropped chat is made
again, `failed` once it gave up), and a `ChatSource` of your own can stand in its place.

Closing the chat screen ends the conversation shortly after: with no chat
window showing it, it closes 15 seconds after the last window left (or after
the last message, whichever is later) — `GraineConfig.chatCloseDelaySeconds`,
0–300, sets another delay, and `chat.closeDelaySeconds` changes it while the
app runs. A window showing it again in that time goes on where it was, so a
quick hop to the booking sheet keeps it; opened later, a signed-in user's
conversation continues from Graine's history, with no new welcome. When Graine
ends a conversation itself — idle, or at its time limit — the chat does not
reconnect by itself: the lines stay, the header says "Disconnected", and
opening the chat or sending a message connects again. `close()` and the app
going to the background end it at once.

The user's earlier sessions with the agent — like the dashboard's Execution
History — are `chat.pastSessions` (`refreshPastSessions()`,
`loadMorePastSessions()`), and `chat.sessionTranscript(id)` answers one
session's messages, for a history screen of your own.

### Conversations continue

A user you `identify()` who comes back — later today, tomorrow, on their other
phone — finds the chat where they left it, and the agent picks up from there
instead of greeting them again. The chat opens with the recent messages (the
last 50, kept for a while after the last one), earlier calls show as one
"Voice call · 6:05 PM" row, and day lines say Today, Yesterday or the date.
Drawing your own chat? `turn.restored` marks a message from an earlier
conversation and `turn.channel` (`CHAT`, `VOICE`) where it was said;
`ChatRows.build(chat.messages)` gives the rows to draw (day lines, one row per earlier call).
`chat.newConversation { _, error -> }` starts a fresh thread (the chat window's
header offers it while an earlier conversation is on screen); what the agent
remembers about the user stays. `forgetMemory` clears the chat on screen too.
When the chat connects again and the conversation cannot be continued, the new
one starts under the earlier lines after a "Disconnected · new conversation"
line — never a second welcome stacked under the first.
Anonymous users, and those with memory off, start fresh every time; each agent
in your app keeps its own conversation.

**A chat belongs to whoever was identified when it connected.** Identify before
showing the chat where you can: if the identity changes while the chat is
connected — signing in after it opened, another user, signing out — the
conversation closes quietly, its messages clear (a message typed and not yet
sent stays, and goes next), and the chat connects again as the new identity.

### Sessions

Each chat connection is a session, and so is each call: `turn.session` is the
id of the one a message was said in (the id Execution Logs shows), and
`chat.sessionId` the current one. `chat.sessions` lists them, oldest first —
for a "Past conversations" list of your own:

```kotlin
val chat = GraineInApp.get().chat
for (s in chat.sessions.reversed()) {                  // newest first
    val kind = if (s.channel == GraineChatTurn.Channel.VOICE) "Call" else "Chat"
    addRow("$kind · ${s.turns} messages", dateFormat.format(s.startedAt), current = s.current)
}
```

Messages kept before sessions were recorded make one session with no id; with
a server that does not name sessions yet, the whole conversation is one session
with no id and `sessionId` is null.

### Chat window v2: styles, quick replies, ratings

The chat window is drawn piece by piece from the dashboard (**Chat window →
Style**): a style — Classic, Soft, Bold, Glass, Minimal, Crisp, Cozy, Calm,
Compact or Lively — sets the header, bubbles, times, wallpaper, chips, cards,
composer, typing indicator, welcome and rating at once, and each piece can then
be tuned on its own, with no code. Classic is the chat as it has always looked.
Replies arrive smoothly and formatted (bold, italic, lists, links, code); a
long press copies a message; a message that could not be sent says so, and a
tap sends it again. The agent can suggest replies (chips the user taps) and end
the conversation, after which the window asks how it went where the design does.

```kotlin
val chat = GraineInApp.get().chat
// What the conversation is about, pinned under the header or atop the thread (never sent to the agent).
chat.pin(GraineChatPin("Account settings", subtitle = "Profile, email and phone", badge = "Active"))
chat.pin(null)
chat.onAttach = { pickAFile() }          // the paperclip shows where the design has one, and only with this set
chat.onRated = { rating -> log(rating.style, rating.value, rating.scale) }
chat.rating                              // the user's answer, once given
// Drawing your own chat:
chat.quickReplies                        // the agent's suggested replies for its latest turn
chat.sendQuickReply(chat.quickReplies.first())
chat.ended                               // the agent ended the conversation
chat.rate("stars", 4)                    // thumbs 1 (up) or 0, stars and emoji 1–5 — once, after the end
```

The microphone and call buttons a style may draw call the agent the way the
voice bar does, and are drawn only where the agent takes calls.

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
graine.scheduleCall(at, reason = "Follow-up") { call, error -> }                  // GraineScheduledCall
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

## Booking a call from your app

When your workspace has Graine ring its users and the agent lets them book
(Calls & scheduling, in the dashboard), the SDK draws the booking screen itself
— "Call me at…" with no screen of your own:

```kotlin
if (graine.bookingAvailable) {                      // rung here, booking on, the user identified
    graine.presentBooking(activity, reason = "Card replacement") { call, _ ->
        if (call != null) showBooked(call)          // null: closed without booking
    }
}
```

The sheet is drawn in the agent's design, light or dark: the day (chips from
today to the furthest day the agent allows), the time on the platform's time
picker (spinner mode; only times the agent's rules allow), the phone's time
zone, "What's it about? (optional)" — 120 characters, kept with the call for
the agent, never in the push; `reason` fills it — and **Book call**. A refusal
stays in the sheet as one line: "Pick a time at least 5 minutes from now",
"Pick a time within 14 days", "That time is outside the hours we can call —
pick another", "You already have 3 calls booked — cancel one first", "Sign in
to book a call", "Couldn't book just now — try again". Once it is booked, the
sheet says when the agent will call, and Done closes it. A sheet up from the
bottom on a phone, a centred panel on a tablet; Back or a tap outside closes it.

`GraineBookingDialog(activity, graine, reason) { call, _ -> }.show()` is the
same sheet, for an app that shows it its own way. From Java:
`graine.presentBooking(activity, (call, error) -> { … });`.

`bookingAvailable` is each agent's own: true only when Graine rings this phone
(the workspace's In-app calling, for Android), the agent lets users book, and
the user is identified with the trait the agent's Identity setting names
(otherwise a booking is refused with `IDENTITY_REQUIRED`). The SDK's chat
window follows the same rule: **Book a call** under the booked calls in its
Upcoming calls card, or on its own in the card's place when none are booked —
and none when the design hides the booked-calls card.

**Read it again when the session arrives.** The agent's settings — which trait
identifies the user, whether Graine rings this phone — load after launch, so
`bookingAvailable` is false until they have arrived: don't read it once at
launch. Read it in `onSessionReady` and after `identify()`.
`bookingUnavailableReason` says why in a word: `not_loaded` (not yet — never a
final answer), `rings_not_set_up`, `scheduling_off` or `not_identified`.

```kotlin
graine.addListener(object : GraineListener {
    override fun onSessionReady(session: SessionConfig) = updateBooking()   // the settings arrived: read again
})

fun updateBooking() {                                                       // and after identify()
    bookButton.visibility = if (graine.bookingAvailable) View.VISIBLE else View.GONE
    bookHint.text = if (graine.bookingUnavailableReason == "not_identified") "Sign in to book a call" else ""
}
```

The SDK's sheet and chat window do this themselves: a sheet opened before the
settings arrive, or after they failed to load, asks for them again and redraws
when they do. `scheduleCall`, `scheduledCalls` and `cancelScheduledCall` wait
for the settings too — a call booked or listed right after launch is the
user's, not only this install's — and when the settings could not be loaded
they answer with that reason (`UNAVAILABLE`, `NETWORK`…), never a booking rule
that was never read.

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
`contract/client-tokens.json` holds the server tokens' codes, what a token says
about its own life and when each is refreshed; the token tests run every case.

A real call against your agent, on the JVM, with a WAV file as the customer:

```bash
GRAINE_LIVE_E2E=1 GRAINE_E2E_KEY=pk_live_… GRAINE_E2E_WAV=question-16k.wav \
  ./gradlew :graine-inapp:testDebugUnitTest --tests 'ai.graine.inapp.LiveCallE2ETest' --rerun
```

## License

MIT — see `LICENSE`.
