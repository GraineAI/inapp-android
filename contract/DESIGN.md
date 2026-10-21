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
| `chat` | The chat window's words and colours — see **Chat window** — and, from a v2 server, every piece of it (`preset`, `header`, `bubbles`, `timestamps`, `wallpaper`, `chips`, `cards`, `composer`, `typing`, `density`, `welcome`, `rating`, `escalation`, `glass`) — see **Chat window v2**. Absent (an older server): draw the chat window as before |
| `call` | The call screen's colours, buttons, words and ring, and its look (version 2: backdrop, layout, rings, visualiser, caption style, type, a look per moment) — see **Call screen**. Absent: Graine Classic's (below) |

### `chat` and `call`

| Token | Value | Drawn as |
|---|---|---|
| `chat.title` | string; `""` = the Appearance heading | Header title, the empty chat's title, the window-chrome title |
| `chat.subtitle` | string; `""` = "Online" | Header status while online; the empty chat's line (only when not `""`) |
| `chat.avatar` | `logo` · `glyph` · `none` | Header avatar (30), empty chat (48) |
| `chat.placeholder` | string | Composer placeholder |
| `chat.showTimestamps` | boolean | Times under messages |
| `chat.suggestions` | 0–4 strings | Starters above the composer of a new chat |
| `chat.background` | colour | Frame, thread, composer strip |
| `chat.pattern`, `chat.patternColor` | `none` · `dots`; colour | Dots over the thread |
| `chat.headerBackground`, `headerText`, `headerSubtext` | colours | Header fill; title (and the dot on an accent header); status and chevron |
| `chat.composerBackground`, `composerText`, `composerPlaceholder`, `composerBorder` | colours | Composer field |
| `chat.size` | `compact` · `large` · `full`; the same in light and dark | How big the window opens — see **Chat window** → Size |
| `chat.showUpcomingCalls`, `chat.upcomingTitle` | boolean; string, `""` = "Upcoming call" / "Upcoming calls"; the same in light and dark | The booked-calls card, and its title — see **Chat window** → Upcoming calls |
| `chat.showHistory` | boolean; the same in light and dark | Whether the conversation kept from before is on screen — see **Chat window** → Earlier messages |
| `chat.newConversation` | boolean; the same in light and dark | The header's compose control — see **Chat window** → Header |
| `call.background`, `call.backgroundTo` | colour; colour or `null` | The screen, or its gradient top → bottom |
| `call.text`, `call.textMuted` | colours | Name, the agent's caption; status, labels, the customer's caption, the ringing halo |
| `call.avatar`, `call.avatarShape` | `logo` · `glyph` · `initials`; `circle` · `squircle` · `rounded-square` | Avatar |
| `call.avatarFill`, `call.avatarText` | colours | The disc behind a glyph or letter; the glyph or letter |
| `call.buttonFill`, `buttonIcon` | colours | Mute, speaker, chat — off |
| `call.buttonActiveFill`, `buttonActiveIcon` | colours | Mute while muted, speaker while on |
| `call.endFill`, `endIcon`, `endShape` | colours; `round` · `pill` | End |
| `call.acceptFill`, `acceptIcon` | colours | Answer |
| `call.declineFill`, `declineIcon` | colours | Decline |
| `call.callbackFill`, `callbackIcon` | colours | Call back, on a missed call |
| `call.captions`, `call.timer` | booleans | The caption; the timer in the status |
| `call.controls` | `mute` · `speaker` · `chat` · `end`, in order, always with `end` | The call's buttons |
| `call.ringtone` | `system` · `graine` · `soft` | The ring |
| `call.statusText.ringing`, `.scheduled`, `.connecting`, `.ended`, `.missed`, `.outgoing`, `.reconnecting` | strings | The status line, per moment |
| `call.preset`, `call.layout`, `call.backdrop`, `call.ink`, `call.avatarSize`, `call.rings`, `call.avatarRingColor`, `call.button*`, `call.actions`, `call.visualizer*`, `call.caption*`, `call.name*`, `call.status*`, `call.states` | the look (version 2) | See **Call screen** → **The look** |

A value this build does not know (a newer server's) is drawn as the first one
listed for it — `logo`, `circle`, `round`, `system`, `none`, `compact` — and an
unknown control is skipped. `showUpcomingCalls`, `showHistory` and
`newConversation` absent, or anything but a boolean (`"false"` included), are
`true`; `upcomingTitle` absent or not a string is `""`.

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
     can carry a chat: 38
     round, `accentSoft`, icon `chat` (on a call) or `call` (in the chat) in
     `text`.
  4. **Microphone** — calls only: 38 round, `accent`, icon `mic` in
     `onAccent`. Muted: `danger`, icon `micOff` in white. At 40% opacity while it
     cannot act (connecting, not ready, refused, error). Off a call it starts
     one; on a call it mutes; after a failed load it retries.
  5. **End** — 38 round. On a call: `danger`, icon `end` in white. Otherwise:
     `accentSoft`, icon `close` in `textMuted`.
- **Status words**, first match wins: error → "Unavailable"; in the chat →
  "Online" / "Connecting…", or "Disconnected" once Graine ended the chat's
  conversation (idle 4408, time limit 4410 — **How long a conversation lasts**,
  0.14.0) until the customer opens it or writes again; offline → "No connection"; reconnecting →
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
  `{ stage }`: `registering`, `dialling`, `retrying` (a fresh connection is
  being made), `switching` (moving to the other transport), `failed` (nothing
  could carry it; stop connecting). A call is connecting until it has been
  answered with its audio flowing — `graine:call` says `connected` only then —
  so the engine sends `retrying` and `switching` only for a call that was
  connected and is recovering. A first connect that has to dial again (on
  mobile data, usually a connection the phone dropped while idle) or move to
  the other transport reports `registering` and `dialling`, and stays on the
  connecting words. While `retrying` or `switching`, draw the **reconnecting**
  moment. A connect the customer has waited on for 4 s (their bar open, the
  page or the call not there yet) says "Still connecting…" — but only while
  the brand's connecting words are the default "Connecting…": a wording the
  brand chose, often not English, is never replaced. Ignore stages you do not
  know.
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

**Chat window** — above the bar when the customer is in the chat (React
Native, Flutter, the website), or the whole of `GraineChatView` (iOS, Android,
which host the engine page's chat). It reads the token set's `chat` group; every
colour in it is already checked against what it sits on.

- **Frame.** Fill `chat.background`, outline, corners `radius.xl`, shadow
  `elevation.floating` (or `hardOffset`). A chat that fills the screen draws no
  outline, corners or shadow. With `glass`, the fill is translucent as before.
- **Size** — how big the window opens: `chat.size`, unless the app says
  otherwise. The size passed to the open itself wins, then the size in the
  app's configuration, then the token (`chatSizeOf` in theme.ts). The same in
  light and dark; absent (an older server) or a value this build does not
  know: `compact`. A **phone** is an app window whose shorter side is under
  600 points, or a web page under 640 px wide; anything bigger is a tablet or
  a computer (`chatPresentation`).
  - `compact` — the window above the bar, exactly as it has always been (and
    `GraineChatView` wherever the app puts it).
  - `large` — on a phone, a **sheet**: up from the bottom edge, the full
    width, its top edge 8% of the screen's height below the top safe area
    (≈92% of the screen). Corners `radius.xl` at the top only, no outline,
    shadow `elevation.floating` (none with `hardOffset`); behind it
    `colors.scrim` at 40%, which closes the sheet when tapped. Along its top, a
    grab handle: a strip 16 high in the header's fill (the title bar's, with
    window chrome), and in it a bar 36 × 5, fully round, `chat.headerSubtext`
    (`textMuted` with window chrome) at 50%, centred. Dragging the strip or the
    header down more than 80 points (or flinging it) closes the sheet, as does
    the header's chevron. The sheet's fill runs under the home indicator; the
    composer sits above the bottom safe area.
    On a tablet or a computer, a **panel**: 420 wide × min(760, 90% of the
    screen's height) tall, the whole frame above (outline, four corners,
    shadow), in the bottom corner on the launcher's side, 24 in from the side
    and bottom edges of the safe area, over the bar. A website's panel stands
    where its window always did — 24 from the side, above the launcher — and is
    never taller than the page leaves room for. No scrim: the app stays
    usable beside it.
  - `full` — on a phone, the **screen**: all of it, the window's content inside
    the safe areas and its fill to every edge (the header's fill behind the
    status bar). No outline, corners or shadow. The close control is the
    chevron, pointing down, LEADING the header — 12 in, before the avatar —
    and the header's right end has none (with window chrome the title bar
    keeps its chevron at its right end). On a computer's web page, the large
    panel: a website covers the page only when it is narrower than 640 px
    (100vw × 100dvh). An app on a tablet that asks for the whole screen gets
    it.
  - Every size: the composer stays above the keyboard; the thread, components,
    starters, upcoming calls and an earlier conversation are drawn the same;
    moving between the call and the chat keeps the size; closing a sheet, a
    panel or a screen does what the chevron does (back to the call where the
    agent offers one, else the chat closes). Each agent opens at its own
    design's size, and a size passed to one open lasts for that open.
  - A sheet and a screen slide up from the bottom and back at the design's
    pace (`motion.durationMs`; none at 0 or with reduced motion); a panel
    rises like the compact window. A platform's own sheet (iOS's sheet,
    Android's bottom sheet, Flutter's modal sheet) may draw its own handle,
    scrim and slide at these sizes.
- **Header**, 50 high on `chat.headerBackground`, bottom outline `borderWidth` in
  `border`. With `glass`, translucent and blurred only while
  `chat.headerBackground` is `colors.surface` (an accent header is solid).
  Left to right, 12 in from each side, items 10 apart:
  1. **Avatar**, 30, per `chat.avatar`:
     - `logo` — the brand's logo, whole, on its plate (**The brand's logo**), in
       the launcher shape (a pill drawn as a circle). No logo uploaded: draw it
       as `glyph` when `icons.launcher` is set, else the title's first letter,
       bold, `onAccent` on `accent`.
     - `glyph` — the launcher mark: the launcher shape (a pill drawn as a
       circle) in `accent`, `icons.launcher` in `onAccent` at 46%. The server
       sends `glyph` only when `icons.launcher` is set.
     - `none` — no avatar; the title starts at the edge padding.
  2. **Title and status**, taking the rest. Title: `chat.title`, or the
     Appearance heading when it is `""`; the heading family at `weights.title`
     and `min(font.scale.title, 16)`, `chat.headerText`, one line. Under it, at
     caption size in `chat.headerSubtext`: while connecting, "Connecting…" (the
     loader's connecting words); otherwise a 6-point dot — `success` on a
     surface header (`chat.headerBackground` is `colors.surface`),
     `chat.headerText` on any other — then 6 apart
     `chat.subtitle`, or "Online" when it is `""`.
  3. **New conversation** — only while a restored turn is on screen (an
     earlier conversation, **Earlier messages**) and `chat.newConversation`
     is not `false`: the `compose` icon at 18 in `chat.headerSubtext`, in a 44
     tap target, just before the chevron (with window chrome: in the title bar,
     beside its chevron, in `textMuted`). A tap starts a fresh thread — the
     SDK's `newConversation()` — with no confirmation (Call History keeps the
     old one). Off, it is never drawn; `newConversation()` still works for an
     app's own button.
  4. **Chevron**, pointing down, `chat.headerSubtext`: back to the call (or
     closes a chat-only agent). A full-screen window draws it first instead,
     12 in, before the avatar (**Size**).
- **Thread** on `chat.background`, 12 in from each side. With `chat.pattern:
  "dots"`, dots 2 across in `chat.patternColor` on an 18-point square grid, the
  first centred 9 in from the thread's top-left, standing still while the
  messages scroll. `"none"`: no pattern (`patternColor` is then unused).
- **Bubbles** at most 84% wide, `spacing.gap` apart, corners `radius.lg` (the
  corner nearest the speaker `radius.sm`), padding `spacing.cardPadding × 0.75`
  vertical, `spacing.cardPadding` horizontal:
  - `filled` — customer `userBubble` / `userBubbleText`; agent `agentBubble` /
    `agentBubbleText`.
  - `outline` — both transparent with a `borderWidth` outline: customer in
    `accent`, agent in `border`; text in `text`.
  - `minimal` — the agent's messages are plain text on the page, no bubble; the
    customer's sit on `accentSoft` in `text`.
- **Timestamps** — only with `chat.showTimestamps`: under each message, 4 below
  it, aligned with it (the customer's to the right, the agent's to the left),
  `max(10, font.scale.caption − 1)`, the body family at `weights.body`,
  `textMuted`, in the device's short time format ("9:41", "21:41"). Never
  inside the bubble. A component card carries none.
- **Earlier messages** — a conversation that continues (inapp-platform-contract
  §2.6) opens on the turns kept from before (`restored`), drawn as below,
  while `chat.showHistory` is not `false`. Off, the restored turns are not
  drawn: the window opens like a new chat — the empty chat and the starters,
  no "New conversation" (nothing earlier is on screen) — while the agent
  still continues from them (the model has the history; only the display is
  off). What is said from then on is drawn as always. One rule for every
  surface: `chatPieces` (protocol.ts), pinned by sdk/contract/history.json →
  `display`; the day separators are laid out over the turns drawn.
- **Day separators** — in a conversation that continues (inapp-platform-contract
  §2.6): before the first message when it is not from today, and wherever the
  device's local day changes between messages. "Today", "Yesterday", else the
  date in the platform's short words (weekday, day, month — the year too when
  it is not this one). Centred, at caption size, the body family at
  `weights.strong`, `textMuted`, no background, `spacing.gap` from its
  neighbours like a message. A conversation all from today has none.
- **Earlier calls** — a run of the conversation's voice-call turns on one day
  is ONE centred row instead of bubbles: the `call` icon at 14 in `textMuted`,
  6 apart, "Voice call · {time}" (the call's first turn, in the device's short
  time format) at caption size, the body family at `weights.body`, `textMuted`.
  Never the call's transcript.
- **A new conversation under earlier lines** (0.14.0) — a connection opens and
  the agent's first message arrives with no `history` frame before it (any
  `history` frame means it continued, even one that restores nothing): the
  server began a NEW conversation instead of continuing (a customer Graine
  cannot recognise, memory off, or earlier messages that could not be read in
  time). The window says so instead of stacking a second welcome. It looks at
  the CURRENT conversation — the turns settled when that connection opened that
  come after the last marker (all of them when there is none): none → nothing;
  no line of the customer's after the last marker — settled, still waiting at
  that open, or sent since (an opening the customer never answered) → they
  are removed, so the new opening replaces the old one, and a marker that
  preceded them now precedes what comes next; otherwise → a marker before the
  first turn after the last marker that was not settled at that open (what went
  out once the connection opened belongs to the new conversation). A history that restores
  at least one turn takes away every marker before a turn it replaced. The
  marker: a centred row, "Disconnected · new conversation", drawn like a day
  separator (caption size, the body family at `weights.strong`, `textMuted`, no
  background, spaced like it), after any day separator of the
  turn it precedes; screen readers read "Disconnected. A new conversation
  started." It is the window's own row: not a turn, never sent, and New
  conversation or clear takes it away with the lines. Pinned by
  sdk/contract/history.json → `fresh`.
- **Empty chat** — before any message (a conversation that continues opens on
  its history instead), centred in the thread: the avatar at 48
  (the same rule as the header's; nothing for `none`), 12 below it the title
  (the header's words) in the heading family at `weights.title` and
  `min(font.scale.title, 18)`, `text`, centred, up to two lines; and 4 below,
  only when `chat.subtitle` is not `""`, the subtitle at caption size in
  `textMuted`. No dot here.
- **Starters** — `chat.suggestions` (0–4), shown while the customer has sent
  nothing in this conversation (and never on a conversation that continues —
  there is history to read), just above the composer, on the customer's
  side: right-aligned, wrapping onto more rows, 8 apart both ways, 12 from the
  edge and 8 above the composer. Each is a chip 34 high, 14 padding left and
  right, corners `min(radius.xl, 17)`, fill `chat.background` (solid, over any
  pattern), outline `max(1, borderWidth)` in `accent`, the words in `text` at
  `font.scale.body − 1`, the body family at `weights.strong`, one line with an
  ellipsis. A tap sends its words as the customer's message, exactly as typed;
  then the starters go for the rest of the conversation.
- **Composer**, 58 high on `chat.background` (no pattern): field 40 high,
  `chat.composerBackground`, outline `borderWidth` in `chat.composerBorder`,
  corners `radius.xl`, text `chat.composerText`, placeholder `chat.placeholder`
  in `chat.composerPlaceholder`; send button 40 round, `accent`, icon `send` in
  `onAccent`.
- **Upcoming calls** — while this customer has calls booked (the SDK's
  `scheduledCalls()`) and `chat.showUpcomingCalls` is not `false`, one card
  is the first thing in the thread, above the
  messages (or the empty chat), scrolling with them. Drawn as a component card
  (**Components**: `surfaceRaised`, outline `borderWidth` in `border`, corners
  `radius.lg`, padding `spacing.cardPadding`, shadow `elevation.card` or
  `hardOffset`; with window chrome its title moves into the bar):
  - **Title**: `chat.upcomingTitle`, or "Upcoming call" (one) / "Upcoming
    calls" (more) when it is `""`, the body
    family at `weights.strong`, `font.scale.caption`, `textMuted`; the rows
    `spacing.gap` below it and apart.
  - **A row per call**, soonest first, at most three: a disc 32 round in
    `accent` with icon `call` in `onAccent` at 16; 10 to its right, the time —
    in the device's own time zone and locale, "Today, 5:30 PM", "Tomorrow,
    9:00 AM", else the weekday and date ("Tue 14 Oct, 10:00 AM") — at
    `font.scale.body − 1`, the body family at `weights.strong`, `text`, one
    line; under it the call name (the agent's call name, else the heading) at
    caption size in `textMuted`. At the row's right, **Cancel**: 32 high, 12
    padding left and right, corners `min(radius.md, 16)`, outline
    `max(1, borderWidth)` in `border`, the word in `text` at caption size,
    `weights.strong`. A tap cancels that call (`cancelScheduledCall`): the
    time line reads "Cancelling…" until it is done, then the row goes (the card
    with its last row); if it fails, "Couldn’t cancel · tap to retry".
  - No booked calls — or `chat.showUpcomingCalls: false` — no card. Off, the
    SDK's `scheduledCalls()` and `cancelScheduledCall()` still work for an
    app's own list.
- **The words** are the brand's, already cleaned by the server (one line,
  bounded); draw them as they arrive. `""` means "draw the default named
  above", never an empty line.

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
  `font.scale.caption`, `textMuted`, lower case. The chat window shows its
  title — `chat.title`, or the heading when that is `""` — after a 6-point
  `success` dot when online; the caption card "captions"; a component card its
  own title.
- **The chat window's title bar replaces its header**: no avatar, and the
  `chat.header*` colours do not apply (the bar is `surfaceRaised`); the
  collapse chevron sits at the bar's right end, 12 in. Offline, the bar shows
  the title alone (no "Connecting…"). The thread, the starters and the
  composer are unchanged.
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

## Chat window v2

The chat window, piece by piece — header, bubbles, times, wallpaper, chips,
cards, composer, typing indicator, density, welcome, rating and call button —
each set in the Garage (Chat window → Style) and delivered, resolved, in the
token set's `chat` group beside the v1 tokens above. A **style preset** sets
them all at once; every knob can then be turned on its own.

**Compatibility.** Nothing here changes the v1 tokens: a design saved before
v2 resolves to exactly the tokens it always did, and its v2 groups describe
that same chat (`chat.preset: "classic"`). The v1 tokens of a v2 design are
the nearest an older SDK can draw — the header's fill is a gradient's first
colour, a pattern it does not know is its dots, times by group are times
under every message, a bubble colour the brand chose is also
`colors.userBubble` / `agentBubble`. **A server that sends no v2 groups** (an
older one): draw classic, derived from the v1 tokens exactly as the table
below says (theme.ts `resolveChat` is the reference). A group present but a
field missing or the wrong type: that field's classic value. A word this
build does not know: the first value listed for it (`surface`, `rounded`,
`never`, `solid`, `dots`, `outline`, `both`, `wrap`, `auto`, `filled`, `bar`,
`circle`, `loader`, `comfortable`, `centered`, `chips`, `thumbs`, `none`,
`bar` for `contextCard`).

### The tokens

Colours `#RRGGBB`, lengths in points, every text colour already checked
against what it sits on: 4.5:1, or 3:1 (`ON_ACCENT_MIN`, the accent's own
floor, as the v1 accent header and customer bubble have always had) for words
on the accent or a gradient of its shades. A gradient's text reads on both
ends; where the brand forced the ink (`ink: "light" | "dark"`) the colour,
not the ink, moved until it reads.

| Token | Value | Classic (derived from v1) |
|---|---|---|
| `chat.preset` | the style it came from (informational) | `"classic"` |
| `chat.header.style` | `surface` · `accent` · `gradient` · `plain` | `accent` when `headerBackground` ≠ `colors.surface`, else `surface` |
| `chat.header.align` | `start` · `center` | `start` |
| `chat.header.status` | `online` · `text` · `none` | `online` |
| `chat.header.typingStatus` | boolean | `false` |
| `chat.header.contextCard` | `bar` · `card` · `none` | `bar` |
| `chat.header.fill` | `{color, colorTo, text, textMuted}` | `{headerBackground, null, headerText, headerSubtext}` |
| `chat.header.dot`, `chat.header.divider` | colour; boolean | `success` on a surface header, else `headerText`; `true` |
| `chat.bubbles.shape` | `rounded` · `squircle` · `tail` · `flat` | `rounded` |
| `chat.bubbles.radius`, `tightRadius` | numbers | `radius.lg`, `min(radius.sm, radius.lg)` |
| `chat.bubbles.grouping` | boolean | `false` |
| `chat.bubbles.maxWidth` | share of the thread's width, 0.6–0.92 | `0.84` |
| `chat.bubbles.shadow` | `{y, blur, spread, opacity, color}` or `null` | `null` |
| `chat.bubbles.user`, `chat.bubbles.agent` | `{color, colorTo, text, textMuted, border, bare, link}` — `color` null = transparent, `colorTo` null = solid, `border` null = none, `bare` = no bubble at all | from `bubbles` exactly as v1 draws it: `filled` — `userBubble`/`userBubbleText`, `agentBubble`/`agentBubbleText`; `outline` — transparent, `text`, borders `accent` (customer) and `border` (agent); `minimal` — customer `accentSoft`/`text`, agent bare in `text`. `link`: `accent` where it reads at 4.5:1 on the fill, else the words' colour |
| `chat.timestamps` | `{show: never·group·always, style: below·inside, ticks}` | `{showTimestamps ? always : never, below, false}` |
| `chat.wallpaper` | `{style: solid·gradient·pattern, color, colorTo, pattern: none·dots·grid·diagonal, patternColor, intensity, text, textMuted}` | `{pattern = "dots" ? pattern : solid, background, null, pattern, patternColor, 0.5, colors.text, colors.textMuted}` |
| `chat.chips` | `{style: outline·filled·soft·elevated, placement: both·inline·above-composer, scroll: wrap·row, height, radius, fill, text, border, borderWidth, shadow}` | `{outline, both, wrap, 34, min(radius.xl, 17), the wallpaper's colour (its bottom one), colors.text (raised to 4.5:1 on the fill), accent, max(1, borderWidth), null}` |
| `chat.cards` | `{radius, shadow, hard, border, image: auto·square·landscape·wide, button: filled·outline·tonal·text, buttonFill, buttonText, buttonBorder}` | `{radius.lg, elevation.card, hardOffset ≠ null, true, auto, filled, accent, onAccent, null}` |
| `chat.composer` | `{style: bar·floating·pill, mic, attach, send: circle·rounded·icon, fill, text, placeholder, border, strip, shadow, height, radius, sendFill, sendIcon, icon}` | `{bar, false, false, circle, composerBackground, composerText, composerPlaceholder, composerBorder, background, null, 40, radius.xl, accent, onAccent, accent (raised to 3:1 on the field)}` (`icon` draws nothing in classic: no paperclip, microphone or call button) |
| `chat.typing` | `{style: loader·dots·wave·text, text ("" = "Typing…"), color}` | `{loader, "", accent}` |
| `chat.density` | `{name, gap, groupGap, padX, padY, edge}` | `{comfortable, spacing.gap, max(2, round(gap/4)), cardPadding, round(cardPadding × 0.75), 12}` |
| `chat.welcome` | `{layout: centered·card·hero·minimal, starters: chips·list}` | `{centered, chips}` |
| `chat.rating` | `{show, style: thumbs·stars·emoji, prompt ("" = "How was this chat?")}` | `{false, thumbs, ""}` |
| `chat.escalation` | `{call: none·header·composer}` | `{none}` |
| `chat.glass` | `{blur, opacity}` or `null` | `null` |

A side's `textMuted` (its times, inside the bubble) is its words mixed 30%
toward its fill — less, down to 5%, until it reads at 4.5:1 (3:1 on the
accent family) — else the words' own colour. `wallpaper.pattern` `none` is
no pattern; a pattern word this build does not know is `dots`.

The knobs the Garage saves (`DesignConfig.chat`) are the same groups in
words, numbers and colour roles (`accent`, `accentSoft`, `accentDeep`,
`accentBright`, `surface`, `surfaceRaised`, `background`, `text`, or a hex) —
see `DesignChatStyleKnobs` in theme.ts. Only the server reads them.

### Presets

`design.json` → `chatStyles`, `CHAT_STYLES` in theme.ts: data, never code —
a style is a set of knobs, so a new one reaches every phone without a
release. Each is drawn in the brand's own design, accent, light and dark.

| Style | What it is |
|---|---|
| Classic | The chat as it has always looked, in your design. The default. |
| Soft *(recommended for a new chat)* | Pastel bubbles, a floating composer, gentle grouping, emoji rating |
| Bold | A gradient header in your colour, tailed bubbles, filled chips, a hero welcome, a call button |
| Glass | Frosted pieces over a soft wash of your colour |
| Minimal | The agent's words on the page, a centred title, starters as a list |
| Crisp | High contrast: ink bubbles, a pinned context bar, list starters, thumbs rating |
| Cozy | A messaging app's warmth: an accent header, a patterned wallpaper, times inside the bubbles |
| Calm | Spacious: a centred header, a hero welcome, a soft gradient, stars rating |
| Compact | Dense: square-cornered bubbles, times inside, more on screen |
| Lively | Gradient bubbles, a dotted wallpaper, a wave while it types |

### Element by element (v2)

Everything the v1 **Chat window** says still holds — frame, sizes, header
pieces, earlier messages, day separators, the voice-call row, starters'
behaviour, upcoming calls, window chrome — except where a v2 token says
otherwise below.

**Wallpaper** — behind the thread, and also behind a `plain` header and a
`floating` composer: one surface from the window's top edge to its bottom.
- `solid` — `wallpaper.color`.
- `gradient` — linear, top → bottom of that surface, `color` → `colorTo`.
- `pattern` — `color`, and over it, standing still while the messages
  scroll, in `patternColor`: `dots` — 2 across on an 18-point grid, the
  first centred 9 in from the top-left (v1); `grid` — 1-wide lines at
  x = 9 + 18k and y = 9 + 18k; `diagonal` — 1-wide lines where x + y is a
  multiple of 14 (rising left to right). Patterns never mirror for RTL.
- Words drawn straight on it (a bare bubble, day separators, times under
  bubbles, the welcome's title) use `wallpaper.text` / `textMuted`.
- `chat.glass` set: the header (surface or plain), the composer, the chips
  and every agent bubble with a fill are translucent — their colour at
  `glass.opacity` — over the wallpaper blurred by `glass.blur` where the
  platform can (iOS: its thinnest material). A surface header still frosts
  with the design's own `glass` as in v1.

**Header** — 50 high (it grows with the text size).
- Fill by `header.style`: `surface` — `colors.surface`; `accent` —
  `header.fill.color`; `gradient` — linear from the leading edge
  (`fill.color`) to the trailing edge (`fill.colorTo`), mirrored in RTL;
  `plain` — none: the wallpaper runs under it. A `borderWidth` hairline in
  `colors.border` under it when `header.divider`.
- `align: "start"` (v1): [chevron, full screen only] [avatar 30] [title and
  status, taking the rest] [call button] [compose] [chevron]. `align:
  "center"`: the same controls at the same edges, and the avatar (24) with the
  title and status as one group centred on the header's whole width, kept 8
  clear of the controls (truncated before it would touch them).
- Title as v1, in `header.fill.text`. The status line, at caption size in
  `fill.textMuted`, first match wins: connecting → "Connecting…" (the
  loader's connecting words); reconnecting → "Reconnecting…"; ended by Graine
  (idle 4408, time limit 4410 — **How long a conversation lasts**, 0.14.0) →
  "Disconnected", no dot, until the customer opens or writes again; the agent
  typing and `typingStatus` → `typing.text` or "Typing…"; `status: "none"`
  → no line at all (the title centres vertically); `online` → a 6-point dot
  in `header.dot`, 6 apart, then `chat.subtitle` or "Online"; `text` → the
  words without the dot. Not connected and not trying to (offline with
  nothing waiting, or closed after the agent ended the conversation): the
  line as each SDK drew it there before v2 — the reconnect banner says the
  rest.
- **Call button** (`escalation.call: "header"`, and only where the agent
  takes calls and this SDK can start one from the chat): the `call` icon at
  18 in `fill.text`, in a 44 target, before compose. It starts a call with the
  agent exactly as the voice bar's call (or the chat → call switch) would.
  Screen reader: "Call {name}".

**Context card** — what the conversation is about (the item, request or
account the customer is looking at), pinned by the APP: `GraineChatPin {
title, subtitle?, imageUrl?, badge? }`, set and cleared through the chat API
(see SDK API below). Each word cleaned like the brand's own — one line,
control and direction characters out, spaces collapsed, cut in whole
characters: title ≤ 60, subtitle ≤ 80, badge ≤ 24; a pin with no title is no
pin; `imageUrl` only `http(s)`, ≤ 2048 characters (anything else draws the
letter disc). Nothing is drawn until the app pins one, nor for
`contextCard: "none"`. Never sent to the agent (describe the screen to it
with the screen context, as always).
- `bar` — under the header, full width, 52 high, `colors.surface` (frosted
  as the header is), a hairline in `colors.border` under it; 12 in, items 10
  apart: a 32 thumbnail (`imageUrl`, filled and cropped, corners
  `radius.sm`; none: a 32 disc of `accentSoft` with the title's first
  letter, 14, `weights.strong`, `text`), then the title (body family,
  `weights.strong`, `scale.body − 1`, `text`) over the subtitle (caption,
  `textMuted`), and at the trailing end the badge: a pill 22 high, 8 padding,
  `accentSoft`, the words at caption size `weights.strong` in `text`.
- `card` — the first item of the thread (after a hero welcome, before the
  upcoming-calls card), scrolling with it, drawn as a card (**Cards** below) with `cardPadding`: a
  44 thumbnail (corners `radius.md`), the title in the heading family at
  `weights.title`, `min(scale.title, 16)`, the subtitle and badge as in `bar`.
- Screen reader: "Pinned: {title}, {subtitle}, {badge}".

**How long a conversation lasts** (0.14.0) — one rule for every SDK's chat
and the website's. Closing the chat window does not end the conversation:
with no window showing it, it stays connected until it has been idle for 5
minutes — no message either way, the same window Graine's server applies — and
only then closes. Opening the chat again within that time continues the same
conversation: nothing on screen changes and there is no new welcome. A message
that arrives while no window shows it starts the 5 minutes again. The app going
to the background still ends it where the SDK did so before (`endOnBackground`),
and so does `close()`. When Graine ends the conversation itself — idle
(WebSocket close 4408, `idle_timeout`) or at the session's time limit (4410) —
the chat does NOT reconnect by itself: the lines stay, the header's status line
reads "Disconnected", and the next thing the customer does — opening the chat,
sending a message — connects again (continued when Graine can continue it,
else **A new conversation under earlier lines**). Any other unexpected close
is a dropped connection and reconnects as before, behind the reconnect banner.

**Who a chat belongs to** (0.14.0) — one rule for every SDK's chat and the
website's. A chat connection belongs to whoever was identified when its ticket
was made; before any `identify()`, that is nobody (an anonymous chat, which
Graine never continues). When the identity changes while the chat is connected
or connecting — nobody → a user (the app identified after the chat opened), a
user → another user, or a user → nobody (`identify(null)`) — the SDK:
1. closes that connection normally (no "Disconnected", no reconnect banner);
2. forgets the turns it carried — they belong to the identity before, never to
   the one after — any history it holds for it, its new-conversation markers,
   and the booked calls it showed (fetched again for the new identity);
3. keeps the customer's lines that have not gone out — still sending, or
   failed — and sends them on the next connection;
4. connects again at once with a ticket for the new identity if the chat is
   wanted (a window shows it, or the app called `open()`/`connect()` or sent a
   message); otherwise the next open connects. For a user, Graine then continues
   their conversation (a `history` frame) or greets them.
A chat that is neither connected nor connecting when the identity changes
forgets the same way (unsent lines kept), leaves a "Disconnected" state, and
connects at the next open or message.
`identify()` with the same identity value (other traits, memory on or off)
changes nothing on an open chat; it applies from the next connection. Apps
should still identify before showing the chat: the reconnect costs the
customer a moment and an unanswered anonymous opening.

**Reconnect banner** — while the chat reconnects after it was connected, or
is offline with a message waiting: a strip pinned under the header (and the
context bar), 28 high, `colors.surfaceRaised` (frosted with `chat.glass`), a
hairline under it; centred, at caption size in `textMuted`: the loader at 12
and "Reconnecting…", or a 6-point `warning` dot and "Offline · messages send
when you're back". It slides in and out over `motion.durationMs` (not under
reduced motion) and is announced politely once. Calm: never red, never a
modal.

**Thread** — `density.edge` in from each side; `density.gap` between rows;
between two messages of one group `density.groupGap`.

**Bubbles** — at most `bubbles.maxWidth` of the thread's inner width;
padding `density.padY` × `density.padX` (a bare one 2 × 2); words at
`scale.body`, the body family at `weights.body`, `lineHeight.body`, in
`side.text`.
- Fill `side.color` (null: transparent), or a linear gradient top → bottom
  from `color` to `colorTo`; outline `max(1, borderWidth)` in `side.border`
  when set; `bubbles.shadow` under it when set. `bare`: no box at all.
- **Groups** (`bubbles.grouping`; positions from `chatGroups` in
  protocol.ts, pinned by `chat-window.json` → `groups`). Without grouping
  every bubble is `single`. The speaker's side is the trailing side for the
  customer, the leading side for the agent (so both flip in RTL). Corners:
  - `rounded` — every corner `radius`, except the bottom speaker-side corner,
    always `tightRadius` (v1), and the top speaker-side corner, `tightRadius`
    for `middle` and `last`.
  - `squircle` — every corner `radius` (continuous corners on iOS,
    `ContinuousRectangleBorder`-like where the platform has one, else
    circular); in a group the top speaker-side corner is `tightRadius` for
    `middle` and `last`, the bottom one for `first` and `middle`.
  - `tail` — corners as `squircle`; on a `single` or `last` bubble the bottom
    speaker-side corner is square and carries a tail, filled like the bubble
    (a gradient's bottom colour) and outlined with it: for the customer, with
    the bubble's bottom-trailing corner at (W, H), the closed path
    M W,H−12 · Q W+1,H−2 W+8,H · L W−12,H · Z; for the agent the same
    mirrored at its bottom-leading corner (M 0,H−12 · Q −1,H−2 −8,H ·
    L 12,H · Z). The tail lies outside the bubble's width, in the margin.
  - `flat` — every corner `radius` (`tightRadius` equals it).
- **Formatting** — the agent's words are drawn from `chatMarkdown` (pinned
  by `chat-window.json` → `markdown`): paragraphs 6 apart, a line break a
  new line; bold at `weights.strong`; italic the platform's italic; code in
  the platform's monospace at `scale.body − 1` on `mixHex(fill, text, 0.1)`
  with 4 corners; links in `side.link`, always underlined — a tap opens
  http(s) in the platform's browser and mailto/tel with the system; bullets
  "•" and items "1." then 8, wrapped lines hanging under the words. The
  customer's own words are drawn as typed (no formatting).
- **Long-press** a bubble: copy its plain words (`chatMarkdownText`) with the
  platform's confirmation (a "Copied" toast or the system's menu); also a
  screen-reader action "Copy".
- **A reply still arriving** is revealed smoothly: draw
  `revealSlice(text, shown)`, advancing `shown` by `revealLength` every
  frame, parsed with `chatMarkdown(…, { live: true })`; when the turn ends
  the rest is revealed at the same pace, then drawn whole. No reveal (draw
  it as it arrives) when `motion.durationMs` is 0 or the OS asks for reduced
  motion. Only the live turn animates.

**Times** — whose time is drawn: `chatShowsTime(timestamps.show, position)`
(every message, the last of each group, or none). The device's short time
format, `max(10, scale.caption − 1)`, the body family at `weights.body`.
- `below` (v1) — 4 under the bubble, aligned with it, in
  `wallpaper.textMuted`.
- `inside` — inside the bubble, its own line under the words, 2 below them,
  at the trailing edge, in `side.textMuted` (a bare bubble: below, as
  `below`).
- `ticks` — beside each customer time, 4 after it, at 12: `clock` while the
  message waits for a connection, `check` once sent, `alert` in
  `colors.danger` if it could not be sent; in the time's colour otherwise.
- A component card carries no time.

**Message status** — a message waiting for a connection: at 60% opacity, and
"Waiting for connection…" under it (v1). A message that could not be sent
(an SDK whose reconnect gives up): full opacity, and under it, aligned with
it, the `alert` icon at 14 and "Not sent · Tap to retry" at caption size,
`weights.strong`, `colors.danger`. Tapping the bubble or the line sends it
again (it waits, and the chat reconnects). React Native never gives up, so
it has no "not sent"; there a tap on a waiting message tries to connect at
once. Screen reader: "…, waiting to send" / "…, not sent. Double-tap to
retry."

**Typing indicator** — while `typing` (v1 rule), as the agent's next row:
- `loader` (v1) — the Appearance loader (thinking) at 14 in an agent bubble,
  padding `round(cardPadding × 0.5)` vertical.
- `dots` — an agent bubble (its fill, border or bare) holding three 7-point
  dots 5 apart in `typing.color`, each fading 0.35 → 1 → 0.35 over 1200 ms,
  dot i starting i × 160 ms late, forever; all still at 0.6 under reduced
  motion.
- `wave` — the same dots, each rising 4 and back over 900 ms, i × 120 ms
  late; still under reduced motion.
- `text` — no bubble: `typing.text` or "Typing…" at caption size in
  `typing.color`, its opacity breathing 0.55 ↔ 1 over 1400 ms; still under
  reduced motion.
- Screen reader: "{name} is typing", announced politely.

**Chips** — the brand's starters and the agent's quick replies (the
`quick_replies` frame; see Wire below), one look: `chips.height` high (it
grows with the text), 14 padding left and right, corners `chips.radius`,
fill `chips.fill` (frosted with `chat.glass` for `elevated`), outline
`chips.borderWidth` in `chips.border` when set, `chips.shadow` when set; the
words in `chips.text` at `scale.body − 1`, `weights.strong`, one line with an
ellipsis.
- Where (`chips.placement`): `both` — starters docked above the composer
  (v1: on the customer's side, 12 from the edge, 8 above the composer),
  quick replies in the thread under the agent turn they follow, on its
  side, 8 below it; `inline` — starters at the end of the welcome, quick
  replies under their turn; `above-composer` — both docked above the
  composer (quick replies take the starters' place).
- `chips.scroll`: `wrap` — rows that wrap, 8 apart both ways (docked:
  trailing-aligned, v1); `row` — one row that scrolls sideways, 8 apart,
  starting `density.edge` in.
- Starters as a `list` (`welcome.starters`): always in the thread, at the
  end of the welcome, full width, 8 apart: rows 44 high (they grow),
  `colors.surfaceRaised` (frosted with `chat.glass`), outline `borderWidth`
  in `colors.border`, corners `radius.md`, 14 padding, the words in `text` at
  `scale.body − 1`, `weights.strong`, and the `chevron` icon turned to point
  to the trailing side at 16 in `textMuted`.
- A tap sends the chip's words — a quick reply's `text`, a starter as
  written — as the customer's own message: a real turn, as if typed. Then
  the starters go for the rest of the conversation and the quick replies
  until the agent offers new ones. Screen reader: a button, its label.

**Cards** — the agent's components, the upcoming-calls card, a context card
and the rating card: corners `cards.radius`, `surfaceRaised` (window chrome
as v1), outline `borderWidth` in `border` when `cards.border`, shadow
`cards.shadow`, or the design's hard offset when `cards.hard`.
- A component's large image (gallery, spotlight): `auto` — its own height as
  today (100 / 140 / 180); `square` — as tall as wide; `landscape` — 3:4 of
  its width; `wide` — 9:16; always 100–260.
- A component's main button where its own look says `filled`: `filled` —
  `buttonFill` / `buttonText` (v1); `outline` — no fill, `max(1,
  borderWidth)` outline in `buttonBorder`, words in `buttonText`; `tonal` —
  `buttonFill`, `buttonText`; `text` — no fill, the words in `buttonText` at
  `weights.strong`. Corners `radius.md` (v1). A component's own style still
  wins (v1).

**Composer** — the field, its buttons, and what is behind them:
- `bar` (v1) — a strip 58 high in `composer.strip`; the field
  `composer.height` (40), corners `composer.radius`, fill `composer.fill`,
  outline `borderWidth` in `composer.border`, words `composer.text`,
  placeholder `chat.placeholder` in `composer.placeholder`; the send button
  40, 10 from the sides, 8 apart.
- `pill` — a strip 60 high; the field is a pill (`composer.height` 44,
  corners 22) with the send button inside it at the trailing end, 36,
  4 in from the field's edge.
- `floating` — no strip (`composer.strip` null): one raised capsule
  `composer.height` (48) high, 10 above the window's bottom (the keyboard,
  the home indicator) and 10 in from the sides, corners `composer.radius`,
  `composer.fill` (frosted with `chat.glass`), outline in `composer.border`,
  shadow `composer.shadow`, the send button inside at the trailing end (36,
  6 in). The thread scrolls under it; its last row clears it.
- The send button: `circle` — round, `sendFill`, the `send` icon in
  `sendIcon` (v1); `rounded` — corners `min(radius.md, 12)`; `icon` — no
  fill, the `send` icon at 22 in `sendIcon`, in a 44 target.
- `composer.mic` (and the agent takes calls, and this SDK can start one from
  the chat): while the field is empty the send button shows `mic` instead,
  and a tap starts a call with the agent; with words in the field it is the
  send button. Screen reader: "Call {name}" / "Send".
- `composer.attach` and the app handles attachments (the SDK API's attach
  handler): the `attach` icon at 20 in `composer.icon`, a 40 target before
  the field (`bar`) or inside it at its leading end (`pill`, `floating`). A
  tap calls the app's handler; the SDK sends nothing itself.
- `escalation.call: "composer"` (agent takes calls, SDK can start one):
  the `call` icon at 20 in `composer.icon`, beside the paperclip; not drawn
  when `composer.mic` is on (the microphone already calls).

**Welcome** — the opening of a conversation that began here (nothing
restored on screen; a restored conversation has none). A card or a hero
stays at the top of its thread as the conversation grows; the starters
inside it follow the starters' rule (gone once the customer says
something):
- `centered` (v1) — the empty chat as v1, before any message.
- `card` — a card (**Cards**) as the first item of the thread (after a
  pinned card and the upcoming calls): the avatar (40) and beside it the
  title (heading family, `weights.title`, `min(scale.title, 17)`) over the
  subtitle (caption, `textMuted`, when not ""); the starters inside it,
  under a gap, when they are `list` or placed `inline`.
- `hero` — the thread's first block, the window's full width (a `plain`
  header sits over its top), filled with the header's fill (a gradient top →
  bottom), its bottom corners `radius.xl`: 24 padding (plus the header's
  height under a plain header), the avatar 56 centred, the title at
  `min(scale.display, 26)` in the heading family at `weights.title` in
  `header.fill.text`, centred, up to two lines, and the subtitle at
  `scale.body` in `fill.textMuted`; the starters on it, 16 under the words,
  centred (chips; a sideways row runs to its edges, starting 24 in) or as
  the list, 24 in; the next item 16 below.
- `minimal` — no welcome: the greeting is the first thing; starters as
  placed (a `list` in the thread).

**Rating** — after the agent ends the conversation (`conversation_end`; see
Wire) when `rating.show`: a card (**Cards**) at the end of the thread,
centred, `cardPadding` inside: `rating.prompt` or "How was this chat?" in
the heading family at `weights.title`, `min(scale.title, 16)`, `text`,
centred; under it, centred, the choices. The card's width and the choices'
size are `chatRatingLayout(style, thread, cardPadding)` (protocol.ts; pinned
by chat-window.json's `ratingLayout`), `thread` being the width the
thread's rows have: `min(300, 84% of the thread)`, wider — up to the
thread's own width — where the choices would not fit inside the padding;
the choices at full size, `CHAT_RATING_GAP` (8) apart, made smaller
together, icon or face with them, only where even the whole thread is too
narrow (a small phone, a large display setting). At full size:
- `thumbs` — two 44 round buttons, `thumbUp` and `thumbDown` at 22 in
  `text` on `accentSoft`; the chosen one `accent` with its icon in
  `onAccent`.
- `stars` — five 36 targets, the `star` icon at 28: filled `accent` up to
  the chosen one, stroked in `textMuted` after it.
- `emoji` — five 44 round targets with 😞 🙁 😐 🙂 😄 at 24; the chosen one
  on an `accentSoft` disc, scaled 1.15. The faces never scale with the
  system text size: they are pictures, sized by the layout.
- Once chosen, the choice stays drawn, nothing else can be chosen, and
  "Thanks for your feedback" appears under it (caption, `textMuted`). The
  answer goes on the conversation as a quiet app event (Wire) and to the
  app as an event. Given after the conversation has closed (the server's
  short wait ran out), it stays on screen and goes to the app, and is not
  sent. Screen reader: `CHAT_RATING_LABELS`, the chosen one selected.

**Scroll-to-latest** — the thread follows new rows while it is within 120 of
the bottom (v1). Scrolled further up, nothing moves it, and a pill appears
centred 8 above the composer (or the docked chips): 36 high, `surfaceRaised`,
outline in `border`, the card shadow, the `arrowDown` icon at 16 in `text` —
and, once something new has arrived since, "N new" (caption, `weights.strong`,
`text`; "9+" past nine) beside it. A tap scrolls to the newest row (animated,
except under reduced motion) and the pill goes. Screen reader: "Scroll to the
latest message".

**Accessibility, every surface** — each bubble one element: "{name}:
{plain words}" or "You: {plain words}", then the time when drawn and its
status; links inside it reachable; chips, list rows, rating choices and every
icon button are buttons with their labels; the typing indicator and the
reconnect banner announced politely. Text scales with the system's text size
(iOS Dynamic Type, Android sp, Flutter's text scaler, React Native font
scaling, the browser's zoom) and fixed heights grow with it. Reduced motion:
no reveal, no typing animation, no slides. RTL: the customer's side is the
trailing side, and tails, tight corners, alignment, the send icon and the
chevrons mirror; patterns and top → bottom gradients do not.

**Long conversations** — lazy, recycled lists (SwiftUI `LazyVStack`,
Android's platform `ListView` with stable ids — the SDK takes no
dependencies — Flutter `ListView.builder`, React Native `FlatList`);
at most `CHAT_HISTORY_CAP` (200) turns kept; formatting parsed once per
change of a turn's words; only the live turn animates; card images loaded
lazily and cached.

### Wire (protocol.ts; vectors: `chat-window.json`)

All optional and additive: a server that sends none of it leaves every chat
as it was; a client that does not know a frame ignores it.

- server → client `{"type":"quick_replies","replies":[…]}` — replies the
  agent suggests for the turn it just sent: each a string, or `{label,
  text}`; read with `readQuickReplies` (≤ 6, label ≤ 40 on one line, text ≤
  200, blanks and repeats out). `[]` takes them away. Gone also when the
  customer sends anything, the agent starts a new turn, an earlier
  conversation is restored, or the conversation ends (`foldChat`).
- server → client `{"type":"conversation_end"}` — the agent ended the
  conversation: the open turn closes, typing stops, the replies go, and the
  rating is asked for where the design does. The server keeps the socket
  open a short while (≤ 60 s, or until a rating arrives) before its 1000
  close.
- client → server, the customer's rating: `chatRatingFrame(chatRating(style,
  value))` — `{"type":"app_event","event":{"name":"chat_rating","quiet":true,
  "detail":"The customer rated this chat 4 out of 5.","data":{"value":4,
  "scale":5,"style":"stars"}}}`. Quiet: recorded on the conversation, never
  a nudge. thumbs 1 / 0 of 1; stars and emoji 1–5 of 5. One per conversation,
  only after `conversation_end`.
- A quick reply or a starter tapped is an ordinary `text` frame: the
  customer's turn.

### SDK API (additive)

| | Pin a context card | Attachments | Rating, to the app | Quick replies, to an app's own UI |
|---|---|---|---|---|
| iOS | `GraineChatPin`; `chat.pin(_:)`, `chat.pin(nil)` | `chat.onAttach` | `chat.onRated`, and `chat.rating` | `chat.quickReplies`, `chat.sendQuickReply(_:)` |
| Android | `GraineChatPin`; `chat.pin(pin)`, `chat.pin(null)` | `chat.onAttach` | `chat.onRated`, and `chat.rating` | `chat.quickReplies`, `chat.sendQuickReply(reply)` |
| Flutter | `GraineChatPin`; `chat.pin(pin)`, `chat.pin(null)` | `chat.onAttach` | `chat.onRated`, and `chat.rating` | `chat.quickReplies`, `chat.sendQuickReply(reply)` |
| React Native | `client.setChatPin(pin \| null)`, `useGraineAgent().setChatPin` | `onChatAttach` on `GraineProvider` | `onEvent` → `{type: "chat_rated", rating}`, `client.on("rated")` | `useGraineAgent().quickReplies` |
| Website | `GraineAgent.pin(pin \| null)` | — | `GraineAgent.on("chat_rated")` | — |
| Kotlin Multiplatform | `GraineChatPin`; `Graine.chat.pin(pin)`, `Graine.chat.pin(null)` | `Graine.chat.onAttach` | `Graine.chat.onRated`, and `Graine.chat.rating` | `Graine.chat.quickReplies`, `Graine.chat.sendQuickReply(reply)` |

The exact signatures follow each platform's existing chat API; every one is
optional, and an app that uses none of them gets the whole window.

## Call screen

The in-app call, in six moments (eight with **The look**'s placed call and reconnecting): a **scheduled call** ringing
(Graine rang it at a time booked earlier — the push carries `hosted: "1"`),
**any other incoming call** ringing, **connecting**, **on the call**, **ended**
and **missed** (a ring nobody answered). Android draws it full screen — the
incoming ring (over the lock screen) and the call. iOS draws it in the app once a call is answered or placed; the
ring itself is CallKit's, the system's own screen with the call name, the app's
CallKit icon and the ringtone (see **Ringtone**). React Native and Flutter show
these native screens with `callScreen: "sdk"`, and Kotlin Multiplatform's
`GraineCallScreen()` hosts them on both platforms.

It reads `call` from the token set picked as everywhere (`config.scheme`, else
the device's appearance). **Keep the last session's `design` on the device**: a
ring can wake an app that has not fetched a session since it last ran. Before
any session, draw Graine Classic's (`design.json` → the first case's
`light.call`).

The screen is drawn edge to edge, under the status bar and the home indicator;
the layout is measured inside the safe area, 24 in from each side, centred.

**Background.** `call.background`; when `call.backgroundTo` is not null, a
linear gradient from `background` at the top edge to `backgroundTo` at the
bottom edge. Status-bar icons are light when `call.text` is light (WCAG
luminance above 0.5), dark otherwise.

**Top to bottom:**

1. **Avatar**, 112 × 112, 72 below the top of the safe area, in
   `call.avatarShape`: `circle` fully round; `squircle` continuous corners at
   30% of the size (iOS `.continuous`, elsewhere a 30% radius);
   `rounded-square` corners at 25% of the size. Per `call.avatar`:
   - `logo` — the brand's logo, whole, on its plate (**The brand's logo**:
     an accent plate is `colors.accent`, a white plate `#FFFFFF` with a
     `max(1, borderWidth)` outline in `border`), fitted into
     `logoBox(avatarShape, 112)`. No logo uploaded: as `glyph` when
     `icons.launcher` is set, else as `initials`.
   - `glyph` — fill `call.avatarFill`; `icons.launcher` in `call.avatarText`
     at 46% of the size, drawn as the launcher draws it (stroke glyphs at
     `icons.stroke` in their 24-unit box, scaled). The server sends `glyph`
     only when `icons.launcher` is set.
   - `initials` — fill `call.avatarFill`; the call name's first letter (its
     first grapheme, upper-cased where the script has case) in
     `call.avatarText`, the heading family at `weights.title`, 40% of the size.
2. **Name**, 20 below: the call name (the caller name the call carries — the
   agent's call name, else the Appearance heading), the heading family at
   `weights.title`, 28, `letterSpacing.title`, `call.text`, one line with an
   ellipsis.
3. **Status**, 6 below: 16, the body family at `weights.body`,
   `call.textMuted`, one line, tabular digits. First match wins:
   - missed → `statusText.missed`;
   - ended → `statusText.ended` for a call that ended normally; the SDK's own
     words for "Call failed" and "Declined";
   - ringing, a scheduled call (`hosted: "1"`) → `statusText.scheduled`;
   - ringing, any other call → `statusText.ringing`;
   - connecting (either direction) or reconnecting → `statusText.connecting`;
   - on the call with `call.timer` → the time since it connected, `m:ss`, from
     an hour `h:mm:ss`, then " · Muted" while muted;
   - on the call without the timer → "Muted" while muted, "Speaking" while the
     agent's line is arriving, otherwise "Listening".
4. **Caption** — on the call, only with `call.captions`: 36 below the status,
   the latest line, centred, 17, the body family at `weights.strong`, line
   height 1.3, at most four lines (longer: its end, the start cut with an
   ellipsis). The agent's lines in `call.text`, the customer's in
   `call.textMuted`; 75% opacity while a line is still arriving.
5. **Buttons**, the bottom of their labels 56 above the bottom of the safe
   area. Each is a disc with an icon — the SDK's own icons in `icons.style` at
   `icons.stroke`, 30 across — and its label 8 under it: 13, the body family at
   `weights.body`, `call.textMuted`, centred, one line.
   - **Ringing** (either kind): two, each centred in half the width.
     **Decline** (left): 72 round, `call.declineFill`, icon `end` in
     `call.declineIcon`. **Answer** (right): 72 round, `call.acceptFill`, icon
     `call` in `call.acceptIcon`.
   - **Missed** — a ring the SDK drew ended unanswered (the ring timed out, or
     the call's ring deadline passed): no halo, no ring, and two buttons, each
     centred in half the width. **Close** (left): 72 round, `call.buttonFill`,
     icon `close` in `call.buttonIcon`. **Call back** (right): 72 round,
     `call.callbackFill`, icon `call` in `call.callbackIcon`; it places an
     ordinary app call to the agent, as the app's own call button would. The
     screen stays until one is tapped or 30 s pass, then closes.
   - **Connecting, on the call, ended**: `call.controls`, in that order, each
     centred in an equal share of the width:
     - `mute` — 64 round. Off: `call.buttonFill`, icon `mic` in
       `call.buttonIcon`, "Mute". Muted: `call.buttonActiveFill`, icon `micOff`
       in `call.buttonActiveIcon`, "Unmute".
     - `speaker` — 64 round, icon `speaker`, "Speaker". Off: `buttonFill` /
       `buttonIcon`; on: `buttonActiveFill` / `buttonActiveIcon`.
     - `chat` — 64 round, `buttonFill`, icon `chat` in `buttonIcon`, "Chat":
       opens the agent's chat with the call still on. Only when the agent
       offers chat and this SDK can open one during a call; otherwise left out
       and the shares close up.
     - `end` — `call.endFill`, icon `end` in `call.endIcon`, "End".
       `call.endShape`: `round` 64 round; `pill` 64 high and as wide as its
       share less 8 (at least 64), fully round ends, the icon centred.

     While connecting the buttons work. Once ended they sit at 40% opacity and
     do nothing, and the screen closes 1 s later.
   - Each button tells the screen reader its label, and on or off for mute and
     speaker; the avatar is decoration.

**Motion** — none when `motion.durationMs` is 0 or the OS asks for reduced
motion:

- Ringing (either kind): a ring 2 wide in `call.textMuted` grows from the
  avatar's edge to 1.35 × its size while fading from 60% opacity to nothing,
  over 1600 ms, again and again. None once missed.
- On the call: the avatar scales to 1.06 while the agent's line is arriving,
  back to 1 when it stops, each over `motion.durationMs` with the design's
  easing.
- Caption lines, and the change from the ring's buttons to the call's, fade in
  over `motion.durationMs`.

**Ringtone** — `call.ringtone`, while a screen the SDK draws is ringing
(Android's incoming screen; React Native and Flutter while they draw it):

- `system` — the phone's own ringtone.
- `graine`, `soft` — the tone patterns in `design.json` → `ringtones`
  (`RINGTONES` in theme.ts). Each note is a `wave` (`sine` or `triangle`) at
  `hz` (0 is a rest) for `ms`, at `gain` of full scale, rising linearly over
  its first 10 ms and falling linearly over its last 40 ms; the notes in
  order, then `pauseMs` of silence, again until the ring ends. Synthesise the
  samples (mono, 16-bit, any rate from 22.05 kHz) and play them on the
  ringtone stream (Android `USAGE_NOTIFICATION_RINGTONE`), so the ringer
  switch, silent mode and Do Not Disturb silence them exactly as they silence
  the phone's own; vibrate as the system ringtone would.
- iOS rings with CallKit, whose `ringtoneSound` must be a file in the app's
  own bundle — which an SDK cannot add — so there it is `system` unless the
  app bundled the pattern as a sound file and named it to the SDK.

**Fonts** as everywhere: the design's families (**Fonts**), the system font
until they arrive.

### The look (version 2)

The screen above, in a look the brand chooses in the Garage: a ready preset,
a backdrop (a gradient, a mesh of glows, the avatar blurred), a layout, the
avatar's size and ring, the buttons' style, shape, size and labels, a colour
per button, the agent's voice drawn, the caption's style, the type, and a
look per moment. `theme.ts` resolves it on the server (`withCallLook`); an SDK
draws the resolved tokens and never resolves a preset.

**Old designs, old SDKs.** Every look knob is optional. With none set, the
server writes today's screen out in the look's terms (below) and an SDK that
draws the look draws exactly the screen above. The flat tokens above keep
their names and types; a look that changes what they describe rewrites them
as its nearest flat stand-in (`background` = the first stop, `backgroundTo`
= the last, `buttonFill` = a glass button flattened over the bottom of the
screen, …), so an SDK that predates the look draws something close.

**An older server** sends `call` without the look's keys. Derive them, as the
server would for a design with no look knob: `preset` "classic", `layout`
"classic"; `backdrop` `{ type: backgroundTo ? "linear" : "solid", stops:
[background at 0, backgroundTo at 1], angle: 180, motion: "none", intensity:
0.5, scrim: 1 }`; `ink` "light" when `text`'s luminance is above 0.5, else
"dark"; `avatarSize` 112; `rings` `pulse` for `incoming` and `scheduled`,
`none` for the rest; `avatarRingColor` = `textMuted`; `buttonStyle` "solid",
`buttonShape` "circle", `buttonSize` 64, `buttonLabels` "below"; `actions`
from the flat colours (mute, speaker, chat and close from `buttonFill` /
`buttonIcon` / `buttonActive*`; end, answer = accept, decline, callback their
own, `activeFill`/`activeIcon` = `fill`/`icon`, `fillAlpha` 1, `stroke`
null); `visualizer` "none", `visualizerColor` = `text`, sensitivity 0.5;
`captionStyle` "plain", `captionPosition` "middle", `captionFill` black (light
ink) or white, `captionFillAlpha` 0; `nameSize` 28, `nameWeight` =
`font.weights.title`, `statusSize` 16, `statusWeight` = `font.weights.body`;
`states` `{}`; `statusText.outgoing` = `statusText.connecting`,
`.reconnecting` "Reconnecting…". A key this build does not know is ignored; a
word it does not know is the first one listed for it below.

#### The tokens

| Token | Value | Drawn as |
|---|---|---|
| `preset` | `classic` · `aurora` · `midnight` · `glass` · `orbit` · `daylight` · `bloom` · `studio` · `minimal` · `mono` | Informational: the preset it was resolved from |
| `layout` | `classic` · `full-bleed` · `card` · `orb` · `minimal` | **Layouts** |
| `backdrop` | `{ type, stops, angle, motion, intensity, scrim }` | **Backdrop** |
| `ink` | `light` · `dark` | Whether `text` is light: the status bar's icons follow it |
| `avatarSize` | 72–160 | The avatar, before its layout's share |
| `rings` | `{ <moment>: none · pulse · ripple · glow }` for each of the eight moments | **Avatar** |
| `avatarRingColor` | colour | The ring |
| `buttonStyle`, `buttonShape`, `buttonSize`, `buttonLabels` | `solid` · `glass` · `outline`; `circle` · `squircle` · `pill`; 52–80; `below` · `none` | **Buttons** |
| `actions` | `{ answer, decline, end, callback, mute, speaker, chat, close }`, each `{ fill, fillAlpha, icon, stroke, activeFill, activeIcon }` | **Buttons** |
| `visualizer`, `visualizerColor`, `visualizerSensitivity` | `none` · `bars` · `wave` · `orb`; colour; 0–1 | **The voice** |
| `captionStyle`, `captionPosition`, `captionFill`, `captionFillAlpha` | `plain` · `card` · `subtitle` · `bubble`; `middle` · `bottom`; colour; 0–1 | **Caption** |
| `nameSize`, `nameWeight`, `statusSize`, `statusWeight` | points; 100–900 | The name (the heading family) and the status line (the body family) |
| `states` | `{ <moment>: look }` | **Moments** |

A **look** is the keys that change from one moment to another: `backdrop`,
`ink`, `text`, `textMuted`, `avatarRingColor`, `actions`, `visualizerColor`,
`captionFill`, `captionFillAlpha`. The screen's own look is those keys on
`call` itself.

The server guarantees, on every look: `text`, `textMuted` and every icon at
least 4.5:1 on every colour of the backdrop — each stop, the colour halfway
between neighbouring stops (in a mesh, between any two and among all), a
blurred avatar's scrim over the brightest (light ink) or darkest (dark ink)
avatar it could cover, and under a glass button. A forced ink is kept and
the backdrop gives way (a blurred avatar's scrim thickens; the stops deepen
toward black for light ink, lighten toward white for dark ink); an automatic
ink is whichever asks the backdrop to change least. Answer,
Decline, End and Call back keep a white icon at 4.5:1 by taking a deeper shade
of their colour where it allows. An SDK draws the colours as they arrive.

#### Moments

The screen shows one of eight moments (`callMoment()` in theme.ts;
`design.json` → `callVectors.moments`), first match winning:

- `missed` — a ring this screen drew ended unanswered;
- `ended` — the call is over (or the screen is not this call's);
- `scheduled` / `incoming` — ringing, a call Graine rang at a booked time
  (`hosted: "1"`) / any other;
- `outgoing` / `connecting` — not through yet, a call the customer placed /
  an answered ring;
- `reconnecting` / `connected` — on the call, recovering its connection /
  otherwise. A WebRTC call is recovering from its connection's drop (ICE
  `disconnected` or `failed`, or its restart starting) after media was up
  until it is connected again; the SDKs report it (Android
  `GraineCalls.reconnecting`, iOS `GraineCalls.isReconnecting`, the calls
  host snapshot's `reconnecting`). A call that never had media never is. The
  WebSocket path opens one socket per call and a lost one ends the call, so it
  never shows this moment.

**The look of a moment** is `call.states[moment]` when present, complete,
else the screen's own (`callLook()`). The server already writes a scheduled
ring's look from a ring's and a placed call's from connecting's where they
have none of their own, so an SDK only ever looks up the moment itself. When
the moment changes, the backdrop and the colours fade from one look to the
other over `motion.durationMs` (at once with no motion).

**Status words** as in **Top to bottom** → 3, with `outgoing` →
`statusText.outgoing` and `reconnecting` → `statusText.reconnecting`
(`callStatusWords()`; `design.json` → `callVectors.status`). Drawn at
`statusSize`, the body family at `statusWeight`.

#### Layouts

Measured inside the safe area, 24 in from each side (`CALL_LAYOUTS`;
`design.json` → `callScreen.layouts`). The buttons are placed the same in
every layout (**Top to bottom** → 5).

| Layout | Align | Top | Avatar | Name | Then |
|---|---|---|---|---|---|
| `classic` | centred | 72 | `avatarSize` | 20 below the avatar, 28, one line | status 6 below; the voice; a middle caption |
| `full-bleed` | leading edge | 32 | half of `avatarSize` | 24 below, 40, up to two lines | as classic, everything against the leading edge |
| `card` | centred | 48 | 80% of `avatarSize` | 16 below, 24, one line | as classic, on one card: padding 24, corners 28, fill `captionFill` at `max(captionFillAlpha, 0.16)` |
| `orb` | centred | 40 | 75% of `avatarSize`, at the centre of an orb `avatarSize` × 2 across | first, 24, one line | status 6 below; the voice; then the orb, centred in the space left above the buttons; a middle caption 24 under it |
| `minimal` | centred | 120 | none (and no ring) | 34, up to two lines | as classic |

`nameSize` replaces the layout's name size when the brand set one; the name
is drawn in the heading family at `nameWeight` with `letterSpacing.title`.

#### Backdrop

The screen's fill, edge to edge (`callBackdropFrame()`; `design.json` →
`callVectors.frames`). Stops are `{ color, at }`, `at` 0–1, in order.

- `solid` — the first stop.
- `linear` — the gradient along `callGradientLine(angle, w, h)`: through the
  centre, angle read as CSS reads it (0 bottom to top, 90 left to right, 180
  top to bottom), long enough that the end stops reach the corners. Android's
  `LinearGradient` from its two points, iOS's gradient points divided by the
  screen's size, CSS's `linear-gradient(<angle>deg, …)`: the same ramp.
- `radial` — a circle centred at 50% across, 28% down, radius `(0.35 + 0.4 ×
  intensity) ×` the screen's diagonal; the last stop beyond it.
- `mesh` — glows at fixed places (shares of the screen: 0.15/0.12,
  0.85/0.2, 0.8/0.85, 0.18/0.78, and 0.5/0.48 for a fifth stop) in the stops'
  colours in turn (two stops: corner to corner). A pixel at (u, v) is every
  glow's colour weighted by `max(0, 0.9 − distance)⁴` (distance in shares of
  the screen) and averaged (`callMeshColor()`). Fill a bitmap of at least 32
  × 64 at the pixels' centres and draw it scaled up smoothly.
- `blurred-avatar` — the stops as a linear gradient at `angle`; over it the
  avatar as drawn in its slot (its plate or fill, its mark) at 4 ×
  `avatarSize`, centred on the avatar (in the orb and minimal layouts, 42% of
  the way down the screen, centred across), blurred by 48 points; over that the
  stops again at `scrim` opacity. Without a blur (Android before API 31), the
  avatar drawn into a bitmap 16 across and scaled up smoothly; before the
  avatar's image arrives, the stops alone.

**Motion** (`motion`), from the moment the screen shows, `t` in ms; at rest
with `none`:

- `drift` — a linear gradient's angle sways by `(6 + 18 × intensity) ×
  sin(2πt / 14000)` degrees; a radial's centre circles at `(0.03 + 0.06 ×
  intensity) ×` the width over 16 s; each mesh glow circles at `0.05 + 0.1 ×
  intensity` (shares) over 17, 19, 23, 29 and 31 s, glow *i* starting at
  phase `i × π / 2.5`; the blurred avatar circles at `(0.02 + 0.05 ×
  intensity) ×` the width over 18 s.
- `breathe` — `s = 1 + (0.05 + 0.12 × intensity) × sin(2πt / 7000)`: a
  linear gradient's line scaled by `s` about the centre, a radial's radius
  and the mesh's glows' distances from the centre likewise; the blurred
  avatar scaled by `1 + (0.04 + 0.08 × intensity) × sin(2πt / 7000)`.
- **Battery.** Nothing moves while the screen is not visible, when
  `motion.durationMs` is 0, or when the OS asks for reduced motion. On a
  connected call nobody has touched for 10 s, the backdrop comes to rest over
  600 ms; a touch starts it again from rest. A still backdrop is drawn once.

#### Avatar

`avatarSize` × the layout's share, in `avatarShape`, drawn as **Top to
bottom** → 1. While the agent's line is arriving on a connected call it
scales to 1.06 and back, as before.

**Rings** — `rings[moment]` in `avatarRingColor` (the moment's look's), behind
the avatar, in its shape. With no motion, none is drawn.

- `pulse` — a ring 2 wide grows from the avatar's edge to 1.35 × its size
  while fading from 60% to nothing, over 1600 ms, again and again (today's).
- `ripple` — three such rings, 1.5 wide, to 1.6 × while fading from 50%, over
  2400 ms, each starting a third of it after the one before.
- `glow` — a soft disc behind the avatar, 1.45 × its size across: the colour
  at 70% opacity out to 62% of the disc's radius, fading to nothing at its
  edge; breathing from half strength to full and 1 to 1.08 × over 2000 ms and
  back. On a connected
  call it follows the voice instead: strength `0.5 + 0.5 × level`, size `1 +
  0.08 × level`.

On a connected call a `pulse` or a `ripple` only runs while the agent's voice
is heard; every other moment wearing one runs it throughout.

#### The voice

On a connected call, `visualizer` in `visualizerColor` (`CALL_VISUALIZER`):

- `bars` — five bars 4 wide, 4 apart, fully round, centred in a strip 28 high
  16 under the status line (against the leading edge in `full-bleed`). Each
  stands between 4 and 28 high by `level ×` 0.5, 0.8, 1, 0.8, 0.5, and each
  wobbles between 35% and 100% of that over 600 ms, bar *i* starting *i* ×
  110 ms late, while `level` is above 0. At silence, 4 high and still.
- `wave` — a line 160 wide, 2 thick, round caps, in the same strip: `1.5`
  cycles of a sine across it, amplitude `12 × level ×` `sin(π x / 160)` (it
  tapers to its ends), moving left one cycle every 900 ms. At silence a flat
  line at 40% opacity, still.
- `orb` — two soft discs centred on the avatar, behind it and its ring,
  `(1.6 + 0.5 × level) ×` the avatar as drawn (its layout's share of
  `avatarSize`) across, or `(2 + 0.4 × level) × avatarSize` in the orb
  layout; the colour at 50% at the centre, 35% at 55% of the radius,
  nothing at the edge; the inner disc 72% of the outer. At silence, at rest
  and still. The orb layout draws its orb in every moment, still unless
  connected.

**The level** (0–1) is the agent's voice as the call hears it, on every
transport: over WebRTC the inbound audio level (five readings a second); over
the WebSocket the RMS of each 100 ms of the agent audio the SDK decodes, taken
when the speaker reaches it (at most ten a second, 0 once it has played out)
— Android `GraineCalls.agentLevel`, iOS `GraineCalls.agentLevel`. It is drawn
as `callVoiceLevel(level, visualizerSensitivity)` — the level in dB between a
floor (−30 dB at sensitivity 0, −60 at 1) and −6 dB. Until a call's first
level arrives: `callSpeakingLevel(t)` while the agent speaks, 0 otherwise. The drawn level follows it up over 60 ms and down over 240 ms
(exponential). With no motion the voice is drawn at rest.

#### Caption

On a connected call with `captions`, the latest line (as before, the agent's
in `text`, the customer's in `textMuted`, 75% while arriving):

- `plain` — as **Top to bottom** → 4.
- `card` — 16, at most four lines, on a rounded rectangle (corners 20,
  padding 16 × 12) in `captionFill` at `captionFillAlpha`, the width of the
  layout.
- `subtitle` — 15, centred, at most three lines, on a band hugging the words
  (corners 10, padding 10 × 6) in `captionFill` at `captionFillAlpha`.
- `bubble` — 15, at most four lines, on a bubble at most 85% wide (corners
  18, the speaker's lower corner 6): the agent's at the leading edge, the
  customer's at the trailing edge.

`captionPosition` `middle` puts it where **Top to bottom** → 4 does (under
the voice when there is one); `bottom`, 24 above the top of the buttons' row.
The caption's fill is the ink's opposite (black under light ink), so it only
ever adds contrast.

#### Buttons

Sizes from `callButtonSizes()`: the call's buttons `buttonSize`, the ring's
and the missed call's `round(buttonSize × 9/8)`, icons `round(buttonSize ×
30/64)` (64, 72 and 30 by default). The box of each from `callButtonFrame()`
(`design.json` → `callVectors.buttons`):

- `circle` — round, as before;
- `squircle` — continuous corners at 30% of the size (iOS `.continuous`,
  elsewhere a 30% radius);
- `pill` — `min(share − 8, 1.5 × size)` wide, at least `size`, round ends; a
  toggle that is on (muted, speaker on) draws its corners at 30% instead,
  changing shape over `motion.durationMs`;
- End with `endShape` `pill` is `max(size, share − 8)` wide in every shape,
  as before.

Each button's colours are `actions[<button>]` in the moment's look:

- `fillAlpha` 1 — filled with `fill`, the icon in `icon`.
- `fillAlpha` between 0 and 1 — glass: `fill` at that opacity over the
  backdrop, which is blurred behind the button by 20 where the platform can
  (Android API 31+ `RenderEffect` on a copy of the backdrop clipped to the
  button; iOS a material; elsewhere the translucent fill alone).
- `fillAlpha` 0 — an outline: 1.5 wide in `stroke`, nothing inside.
- Mute and speaker while on: `activeFill` (opaque), the icon in `activeIcon`.

Answer, Decline, End and Call back are always filled. Labels per
`buttonLabels`: `below` as before; `none` draws no label — the screen reader
still reads it.

#### What every SDK tests

`design.json` → `callScreen` holds the numbers (`layouts`, `screen`,
`rings`, `visualizer`, `motion`, `limits`, `moments`, the presets' ids and
names, the recommended one), and `callVectors` the answers: gradient lines,
backdrop frames at times, mesh colours, button boxes and sizes, voice levels,
the speaking level, moments, status words, and each moment's look on a design
with every knob turned. Each SDK's tests parse every case's look and agree
with every vector.

## Icons

The SDK's own icons — `mic`, `micOff`, `end`, `send`, `chat`, `call`, `chevron`,
`close`, `compose`, the call screen's `speaker`, and the chat window v2's `attach`, `thumbUp`,
`thumbDown`, `star`, `arrowDown`, `check`, `clock`, `alert` and `retry` — are 24 × 24 drawings in `design.json`
→ `icons`, the same paths the dashboard draws. Each has a closed `body` (may be filled) and `lines` (only ever
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
