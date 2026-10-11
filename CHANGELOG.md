## 0.6.2

- **A call goes on hold when the phone takes its audio** — a phone call ringing, another app's call or player: nothing the customer says is sent and nothing the agent says is played until the audio comes back, and then the call carries on. A notification sound changes nothing. **If the customer answers a phone call during the hold, the agent's call ends** (`ended`, `local`), as iOS's End & Accept ends it.

## 0.6.1

- **Calls follow the headset**, as a phone call does: a Bluetooth headset first (LE audio, hands-free, hearing aids), then a wired or USB headset, then the earpiece. Turning the loudspeaker off brings the call back to the headset, and the choice is made again whenever a headset is switched on, off, plugged in or pulled out. No new permission.
- **A repeated push for the call in progress is ignored**, as on iOS, instead of being reported `missed` while that call goes on.
- **A microphone that stops mid-call is rebuilt once**; if it cannot be, the call ends — `ended` with `failed` and "The microphone stopped working." — instead of staying up with the customer silent.
- `GraineCallsHost` takes a `debug` option, so React Native and Flutter apps can turn on the SDK's debug log for calls.

## 0.6.0

- **Calls like WhatsApp**: `GraineInApp.get().calls`. Your agent rings the customer — hand your FCM data message (`type: "agent_call"`, `call_id`, `reason`, `caller_name`, `variables`) to `calls.handlePush(data)`, and the phone rings with a call notification (the ringtone, Answer and Decline, CallStyle on Android 12+) and a call screen over the lock screen. Or the customer calls the agent with `calls.startCall(activity, reason, variables)`.
- **The call keeps going with the screen off**, with its audio in your app — the microphone with the platform's echo canceller, the earpiece or speaker — inside a microphone foreground service, with an ongoing-call notification and Hang up.
- **Outcomes for your server**: `GraineCallListener` reports the call's state, captions and speech, and its outcome with the call's id — answered, declined, missed (30 s by default, or busy), ended (local, remote, failed) or failed.
- `GraineCallsConfig` sets the name, the status-bar icon, the ringtone, your own call screen and the ring timeout. The library's manifest declares the call screen, the service, the receiver and their permissions.
- Starting a call ends a conversation in the assistant bar, and the bar cannot start one while a call is live.
- `SessionConfig.wsHost`, filled in by the server.
- `GraineCallsHost`, for the React Native and Flutter call packages: the calls driven in plain values (maps, lists, strings), the options kept for a cold start, the call as one snapshot, and the agent's actions answered from JavaScript or Dart. A native app keeps using `GraineInApp.get().calls`.
- `GraineInApp.registerDeferredAction(name) { args, answer -> }`: an action answered later — after a network call, a confirmation, another screen — held to the same six seconds and the first answer.
- Still no runtime dependencies.
- The library's manifest adds what a call needs. On Android 14+, Google Play asks apps that declare `USE_FULL_SCREEN_INTENT` or `FOREGROUND_SERVICE_MICROPHONE` to explain them; an app that does not use calls removes those two permissions and `ai.graine.inapp.GraineCallService` with `tools:node="remove"` (snippet in the Android guide).

Guide: [In-app calling](https://www.graine.ai/docs/in-app/calling).

## 0.5.0

- A connect says where it is. The engine now reports each stage of placing a call (`graine:progress`): setting up the line, dialling, a fresh try after the first attempt went unanswered (on mobile data, usually a connection the phone dropped while it sat idle), a move to the other transport, or that nothing could carry the call. The voice bar draws a retry or a switch as the loader's "reconnecting" moment, in the brand's own words. A connect the customer has waited on for 4 seconds — a call they placed, or their bar open on a page still loading — says "Still connecting…", but only while the brand's connecting words are the default "Connecting…": a wording the brand chose, often not English, is never replaced. A call that could not be connected on either transport stops saying "connecting" and says "Couldn’t connect · tap to retry"; the microphone (or the words) places it again. An app drawing its own UI reads the same from `GraineVoice.connectStage`, `slowConnect` and `callFailed`, and hears every change in `GraineListener.onConnectProgress`; `GraineConfig.Timing.slowConnectMs` moves the 4-second mark. A stage this build does not know is ignored.
- The brand's logo is drawn whole. Wherever it stands in for the launcher glyph — `GraineLauncherView`, the voice bar's avatar, the chat window's header mark — it is fitted, never cropped, into the largest square its shape holds: 70% of a circle, a squircle or a pill's height, 80% of a rounded or plain square. The avatar used to crop it to fill its circle, which cut a wordmark down to its middle and a square logo's corners off. It sits on its plate, `appearance.logoPlate`, which the dashboard picks from the logo's pixels when it is uploaded and the brand can change: the accent, as logos were drawn before, or white with a hairline in the design's border (a pill's label is then dark). A launcher with a hard offset keeps its own outline. `NativeAppearance.logoPlate` carries the plate; `Logo.box` and `Logo.plate` are the rules. A picture the app sets on `GraineAgentBarView.avatarUrl` still fills the circle, as it always said it would.
- The dashboard's six new designs (Swiss Grid, Precision, Japandi Calm, Fintech Clear, Bauhaus, Gamified) are in the contract file the tests read; they draw from the session's tokens like every other design.
- Nothing to change in your code.

## 0.4.0

- Window chrome: the chat, caption and components open as desktop windows when the design says so — Desktop Buddy does. The tokens carry `chrome`, `none` or `window` (`ThemeTokens.chrome`, and `DesignConfig.chrome` when the brand turned the knob); from an older server that sends none, it is `none`. With `window`, the caption card, every component card and the chat window get a title bar across their top (`contract/DESIGN.md` → Window chrome): solid `surfaceRaised` (even under glass) with a bottom outline and the surface's own top corners, 32 dp high (24 on the caption card); three traffic lights 12 dp in from the left, 10 dp across (8 on the caption card) and 6 apart, always #FF5F57, #FEBC2E and #28C840; and the title centred on the whole bar on one line, in the platform's monospace at its regular weight and the design's caption size, in `textMuted`, lower case — "captions" on the caption card, the heading on the chat window (after the `success` dot, 6 dp before it, while online; the heading alone while connecting). A component card's own title moves into its bar and is not drawn again below, and its body is `surface` rather than `surfaceRaised`, so the bar reads as a title bar — a white window under a grey bar (a surface the app set on `GraineWidgetView.theme` still wins); a component with no title shows the lights alone. The chat window's title bar replaces its header, with the collapse chevron 12 dp in from the bar's right end. The loader card of a component on its way and the answered row stay as they were; so does everything else, and every other design stays a plain surface.

## 0.3.0

- The voice bar is the Garage's design, element by element (`contract/DESIGN.md` → Voice bar). A 60 dp bar in the design's `surface` (translucent under glass), outlined, at `radius.xl`, with the floating shadow or the hard offset. The avatar is the accent with the design's launcher glyph drawn from its path (else the logo filling the circle, else the first letter). The heading sits in the body face at the design's strong weight over a status line at caption size, with a four-bar meter that moves at the design's pace while the agent listens or speaks, and holds still when the design or the phone asks for no motion. The microphone is the accent (danger, struck through, when muted; at 40% while it cannot act); it starts a call, mutes one, or retries a failed load. The end button is danger on a call and a quiet close otherwise, which the new `onClose` hears.
- The caption above the bar is a card in the design, with "YOU" over the customer's own lines, and it rises in over the design's duration. The chat window is drawn to the contract too, for when a chat is attached; this SDK carries calls, so for now the bar offers the call alone.
- The status words follow the contract's order and case: Unavailable, No connection, Reconnecting…, Needs microphone, Couldn’t load · tap to retry, Connecting…, Muted, Thinking…, Speaking, Weak connection, Listening, Tap to talk. While connecting, reconnecting, or while the agent works out its answer, the line is the dashboard's loader, in its words.
- The SDK's own icons (microphone, muted, end, send, chat, call, chevron, close) are the dashboard's drawings, drawn from their path data in the design's icon style (outline, sharp, filled or duotone) at its stroke, in place of the emoji.
- The design's typeface is loaded by the SDK. The faces the design draws with (the body family at its body and strong weights, the heading family at its title and strong weights, in light and dark) are fetched from Graine once, kept on disk, registered, and every SDK view redraws in them: the bar, the caption, components, the launcher. Until a face arrives, and if it never does, the system font. `GraineConfig.fonts` is now optional; a family mapped there is drawn from the app's file and never fetched.
- `GraineVoice.phase` (idle, connecting, reconnecting, thinking, speaking, listening) and `GraineVoice.error` (a misconfiguration the engine reports, now also sent to `onError`), as the React Native launcher has them.

## 0.2.0

- The design the brand chooses in the dashboard is drawn. The session carries it resolved into light and dark tokens (`contract/DESIGN.md`); the bar, the components and the loader take their colours, corners, outlines, shadows or hard offsets, type, glass and motion from it, and pick light or dark again when the phone switches. A component's own style still wins over the design. From a server that sends no design, everything looks exactly as before.
- `GraineLauncherView`: the floating button, in the design's shape (circle, squircle, rounded or plain square, pill), with its glyph, the brand's logo, or the first letter of the name. A tap starts or ends the call unless the app sets its own click listener.
- `GraineConfig.fonts` maps the dashboard's font families to typefaces the app bundles; an unmapped family draws in the system font.
- `GraineInApp.design`, `themeTokens(context)` and `previewDesign(design)`, for an app drawing its own UI in the brand's design or previewing one.

## 0.1.1

- Back in the foreground, an idle engine is told to get ready again (`graine:warm`). The engine keeps its line ready so a tap costs only the call itself, but a phone drops that idle connection while the app is in the background. The SDK now tells the engine when the app returns — only when the page is ready, never during a call.
- Connecting is faster in the hosted engine itself — a warm tap connects in about 0.2 s — with no change to this package.

## 0.1.0

First release.

- `GraineInApp` and `GraineVoice` host Graine's voice engine; a call follows the customer between activities.
- Screen context, prompt variables and in-app actions, masked on the device before anything leaves it.
- Components the agent draws, the loader, the draggable keyboard-aware bar, friction detection and connection quality.
- Tested against the same contract as the React Native, iOS and Flutter SDKs.
