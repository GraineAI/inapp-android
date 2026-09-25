## 0.1.1

- Back in the foreground, an idle engine is told to warm (`graine:warm`). The engine keeps a SIP registration warm so a tap costs only the call itself, but a phone drops that idle connection while the app is in the background, and the engine page is not reliably told by its own web view. The SDK now says so when the app returns — only when the page is ready, never during a call.
- Connecting is faster in the hosted engine itself — a warm tap connects in about 0.2 s — with no change to this package.

## 0.1.0

First release.

- `GraineInApp` and `GraineVoice` host Graine's voice engine; a call follows the customer between activities.
- Screen context, prompt variables and in-app actions, masked on the device before anything leaves it.
- Components the agent draws, the loader, the draggable keyboard-aware bar, friction detection and connection quality.
- Tested against the same contract as the React Native, iOS and Flutter SDKs.
