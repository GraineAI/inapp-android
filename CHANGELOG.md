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
