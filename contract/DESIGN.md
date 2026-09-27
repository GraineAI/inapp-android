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

## Element by element

**Launcher** (the floating button). 56 × 56 (a `pill` is 56 high and as wide as
its label). Fill `accent`; glyph or logo in `onAccent` at 46% of the height.
Corners: `circle` = fully round; `squircle` = continuous corners at 30% of the
size (iOS `.continuous`, elsewhere a 30% radius); `rounded-square` = `radius.lg`;
`square` = `radius.sm`. Shadow `elevation.floating`; with `hardOffset`, a solid
`colors.text` copy offset by `{x, y}` and a `borderWidth` outline in
`colors.text`.

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
     `onAccent` at 18; when that is null, the brand's logo filling the circle;
     else the heading's first letter, 15, bold, `onAccent`.
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
  "Couldn’t load · tap to retry" (a curly apostrophe); not ready or placing → "Connecting…"; muted →
  "Muted"; the agent working out its answer (the call's phase is `thinking`) →
  "Thinking…"; the agent's line still arriving → "Speaking"; poor network →
  "Weak connection"; on a call → "Listening"; otherwise → "Tap to talk". While
  connecting, reconnecting or thinking, draw the loader (Appearance → Loading)
  with that moment's words instead of the words above.
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
