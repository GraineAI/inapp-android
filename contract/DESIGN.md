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
| `chat` | The chat window's words and colours — see **Chat window**. Absent (an older server): draw the chat window as before |
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
`close`, and the call screen's `speaker` — are 24 × 24 drawings in `design.json`
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
