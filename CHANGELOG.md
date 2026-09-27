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

- Back in the foreground, an idle engine is told to warm (`graine:warm`). The engine keeps a SIP registration warm so a tap costs only the call itself, but a phone drops that idle connection while the app is in the background, and the engine page is not reliably told by its own web view. The SDK now says so when the app returns — only when the page is ready, never during a call.
- Connecting is faster in the hosted engine itself — a warm tap connects in about 0.2 s — with no change to this package.

## 0.1.0

First release.

- `GraineInApp` and `GraineVoice` host Graine's voice engine; a call follows the customer between activities.
- Screen context, prompt variables and in-app actions, masked on the device before anything leaves it.
- Components the agent draws, the loader, the draggable keyboard-aware bar, friction detection and connection quality.
- Tested against the same contract as the React Native, iOS and Flutter SDKs.
