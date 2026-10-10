# Design tokens — the rendering contract

Every Graine surface — the website widget, and the React Native, Android, iOS
and Flutter SDKs — draws the agent from the same tokens. The brand chooses a
design in the Graine dashboard (a philosophy plus a few knobs); the session
delivers it already resolved. An SDK never resolves a philosophy itself: it
reads the tokens below and draws them onto the elements listed here, exactly.

`design.json` beside this file holds real sessions and the expected answers;
every SDK's tests must parse all of them.

## Where the tokens come from

`GET/POST /api/embed/session` returns, beside `appearance`:

```json
"design": {
  "config": { "preset": "…", "accent": "#RRGGBB", "scheme": "auto", "…": "…" },
  "light":  { ThemeTokens },
  "dark":   { ThemeTokens }
}
```

**Which set to draw:** `config.scheme` is `"light"` → `light`; `"dark"` → `dark`;
absent or `"auto"` → the device's current appearance, and `light` when unknown.
Re-pick when the device switches appearance.

**When `design` is absent** (an older server), keep drawing from `appearance`
as before. Unknown keys inside `design` are ignored, never an error.

## ThemeTokens

All colours are `#RRGGBB`. All lengths are density-independent units (CSS px,
iOS points, Android dp, Flutter logical pixels).

| Token | Meaning |
|---|---|
| `colors.background` | Behind everything in the chat window |
| `colors.surface` | The voice bar, the chat header, sheets |
| `colors.surfaceRaised` | Cards, the composer field |
| `colors.text` / `colors.textMuted` | Primary text / secondary text, captions, status lines |
| `colors.border` | Hairlines and outlines |
| `colors.accent` / `colors.onAccent` | Primary buttons, the launcher, the send button / what sits on them |
| `colors.accentSoft` | Chips, selected rows, focus halo |
| `colors.success` / `warning` / `danger` | States; `danger` is the end-call button |
| `colors.userBubble` / `userBubbleText` | The customer's messages |
| `colors.agentBubble` / `agentBubbleText` | The agent's messages |
| `colors.scrim` | Behind a modal sheet, at 40% opacity |
| `font.body`, `font.heading` | `"system"` = the platform's UI font; otherwise a family name (see Fonts) |
| `font.scale.caption / body / title / display` | Sizes |
| `font.weights.body / strong / title` | 100–900 |
| `font.letterSpacing.body / title` | em (multiply by the size) |
| `font.lineHeight.body / title` | Multipliers |
| `shape.radius.sm / md / lg / xl` | Corner radii |
| `shape.borderWidth` | Width of every outline |
| `shape.launcher` | `circle`, `squircle`, `rounded-square`, `square`, `pill` |
| `spacing.unit / cardPadding / gap` | Base unit, padding inside cards, gap between items |
| `elevation.card / floating` | `{ y, blur, spread, opacity, color }` or `null` (no shadow) |
| `elevation.hardOffset` | `{ x, y }` or `null` — a solid offset shadow drawn in `colors.text` |
| `motion.durationMs / easing / bounce` | Transitions; `durationMs: 0` means none. Always also honour the OS "reduce motion" setting |
| `icons.style / stroke` | Icon treatment for the SDK's own icons |
| `icons.launcher` | `{ id, path, stroke }` — a 24×24 SVG path — or `null`: show the brand's logo |
| `glass` | `{ blur, surfaceOpacity }` or `null`. When set, surfaces are translucent: `surface` at `surfaceOpacity`, background blurred by `blur` where the platform can (iOS has no public blur radius: its thinnest system material) |
| `bubbles` | `filled`, `outline`, `minimal` — see Chat window |
| `chrome` | `none` or `window` — see Window chrome. Absent (an older server): `none` |

## Element by element

**Launcher** (the floating button). 56 × 56 (a `pill` is 56 high and as wide as
its label). Fill `accent`; glyph in `onAccent` at 46% of the height; a logo as
**The brand's logo** below.
Corners: `circle` = fully round; `squircle` = continuous corners at 30% of the
size (iOS `.continuous`, elsewhere a 30% radius); `rounded-square` = `radius.lg`;
`square` = `radius.sm`. Shadow `elevation.floating`; with `hardOffset`, a solid
`colors.text` copy offset by `{x, y}` and a `borderWidth` outline in
`colors.text`.

**The brand's logo** — wherever it stands in for the launcher glyph (the
launcher, the voice bar's avatar, the chat header's mark):

- **Whole.** Scaled to fit, proportions kept, centred — **never cropped**. It
  fills the largest square its shape holds: 70% of the size for `circle`,
  `squircle` and a `pill`'s height, 80% for `rounded-square` and `square`
  (`logoBox()` in theme.ts). Drawing it to fill the circle (`cover`) cut a
  wordmark down to its middle and a square logo's corners off.
- **On its plate**, `appearance.logoPlate`: `accent` — the shape's own fill,
  for a white or light mark made for a coloured background — or `white`:
  `#FFFFFF` with a `max(1, borderWidth)` outline in `border` (a pill's label
  then `#101318`). Absent: `accent`, as logos were drawn before. A logo drawn
  in the brand's own colour on the brand's own fill is no logo at all, which
  is why the plate exists. The dashboard reads it from the pixels on upload
  (85%+ of the visible pixels near white → `accent`, otherwise `white`; an
  opaque image → `white`) and the brand can change it. A hard offset keeps its
  `colors.text` outline instead of the hairline.
- The uploaded file has its empty transparent margins trimmed, so a logo with
  padding is not drawn small.

**Voice bar** — what the customer sees during a call, and what the dashboard's
Garage draws in its phone preview. Every SDK draws it itself from these tokens;
the app passes nothing about how it looks.

- **Frame.** 60 high, 9 padding left and right, items 10 apart, centred
  vertically. Fill `surface` (with `glass`: translucent + blur), outline
  `borderWidth` in `border`, corners `radius.xl`, shadow `elevation.floating` —
  or, with `hardOffset`, a solid `colors.text` copy offset by `{x, y}`, never
  blurred.
- **Left to right:**
  1. **Avatar** 38 × 38, fully round, `accent`. Inside: `icons.launcher` in
     `onAccent` at 18; when that is null, the brand's logo (see **The brand's
     logo**: whole, 70% of the circle, on its plate); else the heading's first
     letter, 15, bold, `onAccent`.
  2. **Name and status**, taking the rest. Name: the appearance heading, 14, the
     body family at `weights.strong`, `text`, one line. Status under it:
     `font.scale.caption`, the body family at `weights.body`, `textMuted`, one
     line — preceded by the **meter** while listening or speaking.
  3. **Switch** — only when the agent offers both call and chat and the SDK
     can carry a chat (the iOS SDK carries calls only, so it draws none): 38
     round, `accentSoft`, icon `chat` (on a call) or `call` (in the chat) in
     `text`.
  4. **Microphone** — calls only: 38 round, `accent`, icon `mic` in
     `onAccent`. Muted: `danger`, icon `micOff` in white. At 40% opacity while it
     cannot act (connecting, not ready, refused, error). Off a call it starts
     one; on a call it mutes; after a failed load it retries.
  5. **End** — 38 round. On a call: `danger`, icon `end` in white. Otherwise:
     `accentSoft`, icon `close` in `textMuted`.
- **Status words**, first match wins: error → "Unavailable"; in the chat →
  "Online" / "Connecting…"; offline → "No connection"; reconnecting →
  "Reconnecting…"; microphone refused → "Needs microphone"; load failed →
  "Couldn’t load · tap to retry" (a curly apostrophe); the call could not be
  connected on either transport (`graine:progress` `failed`) → "Couldn’t
  connect · tap to retry", and the microphone button starts a new call; not
  ready or placing → "Connecting…" (see **Connecting** below); muted →
  "Muted"; the agent working out its answer (the call's phase is `thinking`) →
  "Thinking…"; the agent's line still arriving → "Speaking"; poor network →
  "Weak connection"; on a call → "Listening"; otherwise → "Tap to talk". While
  connecting, reconnecting or thinking, draw the loader (Appearance → Loading)
  with that moment's words instead of the words above.
- **Connecting.** The engine reports where a connect is with `graine:progress`
  `{ stage }`: `registering`, `dialling`, `retrying` (the first attempt went
  unanswered — on mobile data, usually a connection the phone dropped while
  idle — and a fresh one is being made), `switching` (moving to the other
  transport), `failed` (nothing could carry it; stop connecting). While
  `retrying` or `switching`, draw the **reconnecting** moment. A connect the
  customer has waited on for 4 s (their bar open, the page or the call not
  there yet) says "Still connecting…" — but only while the brand's
  connecting words are the default "Connecting…": a wording the brand chose,
  often not English, is never replaced. Ignore stages you do not know.
- **Meter.** Four bars 2.5 wide, 2 apart, bottoms aligned, heights 55%, 100%,
  70%, 40% of 12, fully round, `accent`. Each scales from its bottom between 35%
  and 100% and back, each leg `max(420, motion.durationMs × 3.2)` ms, bar *i*
  starting *i* × 90 ms late, forever. Still at full height when
  `motion.durationMs` is 0 or the OS asks for reduced motion.
- **Caption**, 8 above the bar, on a call with captions on: the latest line.
  Fill `surface` (glass as above), outline, corners `radius.lg`, shadow
  `elevation.card` (or `hardOffset`), padding 12 × 8. Text 13, the body family at
  `weights.strong`, `text`, at most two lines, 75% opacity while still arriving.
  The customer's own lines carry "YOU" above them, 10.5, `accent`, spaced 0.6.
- **Arrivals.** The caption and the chat window rise into place: opacity 0 → 1
  and 8 upward over `motion.durationMs` with the design's easing; no animation
  when that is 0.

**Chat window** — above the bar when the customer is in the chat. Page
`background`, outline, corners `radius.xl`, shadow `elevation.floating` (or
`hardOffset`). Header 50 high on `surface`, bottom outline: a 30 launcher mark
(the launcher shape, a pill drawn as a circle), the heading in the heading family
at `weights.title` and `min(font.scale.title, 16)`, and under it "Online" after a
6-point `success` dot (or "Connecting…") in `textMuted` at caption size; a
chevron pointing down in `textMuted` goes back to the call (or closes a chat-only
agent). Bubbles at most 84% wide, `spacing.gap` apart, corners
`radius.lg` (the corner nearest the speaker `radius.sm`), padding
`spacing.cardPadding × 0.75` vertical, `spacing.cardPadding` horizontal:

- `filled` — customer `userBubble` / `userBubbleText`; agent `agentBubble` /
  `agentBubbleText`.
- `outline` — both transparent with a `borderWidth` outline: customer in
  `accent`, agent in `border`; text in `text`.
- `minimal` — the agent's messages are plain text on the page, no bubble; the
  customer's sit on `accentSoft` in `text`.

Composer, 58 high on `background`: field 40 high, `surfaceRaised`, outline
`border`, corners `radius.xl`, placeholder "Message…" in `textMuted`; send button
40 round, `accent`, icon `send` in `onAccent`.

**Window chrome** — `chrome: "window"`, the playful-desktop look (Desktop
Buddy's, and a knob on any design). The chat window, the caption card and every
component card open as desktop windows:

- **Title bar** across the top of the surface: `surfaceRaised`, a bottom outline
  `borderWidth` in `border`, and the surface's own top corners. 32 high on the
  chat window and components, 24 on the caption card.
- **Traffic lights** at the left, 12 in from the edge: three dots, 10 across (8
  on the caption), 6 apart — `#FF5F57`, `#FEBC2E`, `#28C840`. Always these three:
  they are the window's controls, not the brand's colours.
- **Title**, centred in the bar on one line: the platform's monospace at
  `font.scale.caption`, `textMuted`, lower case. The chat window shows the
  heading (after a 6-point `success` dot when online), the caption card
  "captions", a component card its own title.
- **The chat window's title bar replaces its header**: no launcher mark; the
  collapse chevron sits at the bar's right end, 12 in. Offline, the bar shows
  the heading alone (no "Connecting…").
- **A component card's own title moves into the bar** and is not drawn again
  in the body; rows inside the card keep theirs. Its body is `surface` (not
  `surfaceRaised`), so the `surfaceRaised` bar reads as a title bar — the
  white-window, grey-bar look. A component with no title
  shows the lights alone. The "coming" loader card and the answered row are
  unchanged.
- **Details every SDK draws the same way:** the title is in the regular
  weight, centred on the whole bar and kept clear of the lights; 6 between the
  online dot and the heading; the bar is solid `surfaceRaised` even when the
  design is glass.
- Everything else is unchanged — surfaces, corners, shadows, the voice bar, the
  launcher. Where the platform draws an inner highlight, a primary button may
  carry a glossy top edge (white at 55%, 1 point); optional.

**Signature** — `preset: "signature"` (saved as `"gateway"` for one afternoon;
that id still resolves here), the look Graine itself wears: the dashboard's
design system, which follows Sarvam's visual system (Graine and Sarvam are
partners) with Graine's own motif. Every element above is drawn from its tokens
exactly as for any design: neutral greys, the customer's bubble in slate, the
controls in indigo, 24–32 corners, an ambient shadow, `emphasized` motion, DM
Sans with Newsreader titles, and the `grain` glyph (the G mark's horizontal
bands cut to a circle) on the launcher and the voice bar's avatar. No token is
new, so it reaches every shipped SDK as it is.

- **The web widget's voice panel** (the embed page — not the native voice bar)
  draws the call with the grain, in place of the classic microphone body
  (components/web-widgets/signature-voice-stage.tsx): seven horizontal bands,
  each twice the height of the gap, cut to a circle and filled with one fall
  from a pale top to `accent` — still while idle; a band of light running down
  them while connecting or thinking (the app loader's own sweep); a slow
  breath running down them while listening; each band stretching and settling
  on its own beat while speaking (motion off under reduced motion); one state
  line, lead in `text` and the rest in `textMuted` ("Listening, go ahead",
  "Looking it up", "Speaking now"); the line being said, 19 in the body
  family, the agent's in `accent` and the customer's in `text`, with the line
  before it at 12.5 in `textMuted`; and the call as a pill on `surfaceRaised`
  — elapsed time in monospace, the voice as bars in `text`, and a `danger`
  stop. Off a call, one `accent` pill: "Start a call".
- The phase comes from the transcript, not a new event: an agent line still
  arriving is speaking; the customer talking, or the agent finished, is
  listening; the customer finished with no reply yet is thinking.
- Native SDKs keep the **Voice bar** above for this design. Drawing the grain
  on a phone is a future SDK release, not a token change.

## Icons

The SDK's own icons — `mic`, `micOff`, `end`, `send`, `chat`, `call`, `chevron`,
`close` — are 24 × 24 drawings in `design.json` → `icons`, the same paths the
dashboard draws. Each has a closed `body` (may be filled) and `lines` (only ever
stroked). Draw them in `icons.style` at `icons.stroke`:

- `outline` — stroked, round caps and joins;
- `sharp` — stroked, square caps, mitred joins;
- `filled` — body filled, stroked at 0.75 × the stroke (at least 1);
- `duotone` — body filled at 28% opacity, then stroked.

`chevron` points up as drawn; the chat header turns it 180°.

**Components** (cards, lists, galleries the agent draws). Card `surfaceRaised`,
outline `borderWidth` in `border`, corners `radius.lg`, padding
`spacing.cardPadding`, items `spacing.gap` apart, shadow `elevation.card` (or
`hardOffset`). Title `font.heading` at `font.scale.title`/`font.weights.title`;
body `font.scale.body`; meta `textMuted` at `font.scale.caption`. Primary button
`accent` / `onAccent`, corners `radius.md`; chips `accentSoft`. A component's own
style (`preset`, `radius`, `accent` chosen on the component) still wins over the
design — the design supplies every value the component does not set.

**Loader.** Draw in `accent`.

## Fonts

`"system"` is always safe. Any other value is a family name from the
dashboard's curated list. On the web it is loaded from Google Fonts. **In an app
the SDK loads it itself** — the brand picks a typeface and the phone draws it,
with nothing in the app naming a font:

1. **Which faces.** For each token set the SDK may draw (light and dark): the
   body family at `weights.body` and `weights.strong`, the heading family at
   `weights.title` and `weights.strong`. Skip `"system"`.
2. **Which weight.** The nearest weight the family has (`design.json` →
   `fontWeights`); on a tie, the heavier.
3. **Where.** `<origin>/api/embed/font?f=<family>&w=<weight>&v=<version>` —
   the family URL-encoded, `v` = `fontAssetVersion`. A TTF, cached for a year:
   keep it on disk by URL and never fetch it twice.
4. **Register** it with the platform — Android `Typeface.createFromFile`, iOS
   `CTFontManagerRegisterGraphicsFont`, Flutter `FontLoader` — under
   `Graine-<family with only letters and digits>-<weight>` where the platform
   lets you name it, and redraw. iOS registers a font under the PostScript
   name inside the file: keep the contract name as the face's key and draw
   with the name the file registered.
5. **Draw with the face itself.** A loaded face IS its weight: set the face,
   not a bold flag on top of it (Android would embolden a bold face twice).

Until a face arrives, and if it never does, draw the system font at the weight —
silently: never crash, never show the customer a warning. A family the app
registered itself (React Native `fonts`) wins over the download. React Native
cannot register a font without a native module, so there the app hands the
provider `loadFont` (expo-font's `Font.loadAsync`) once, and the SDK does the rest.

## Launcher glyph images

Apps that cannot draw SVG path data show the glyph as an image:

```
https://www.graine.ai/api/embed/glyph?id=<id>&c=<RRGGBB>&s=<physical px>&w=<stroke>&v=<version>
```

Build it exactly as `glyphUrl()` does (`design.json` → `glyphs` has the expected
URLs): id lower-cased, colour upper-case hex without `#`, size clamped to
16–512 physical pixels (points × screen scale), stroke clamped to 0.5–4 in
quarter steps, `v` = the glyph asset version. The image is cached for a year.
If it fails to load, show the brand's logo, else the first letter of the
agent's name.
