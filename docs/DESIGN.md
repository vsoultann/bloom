# Bloom design rules

The rules every screen follows, so the app feels like one thing. Based on Material 3
(spacing, touch targets, shape scale, motion), WCAG contrast, and Android's Compose
performance guidance. When adding UI, use the tokens below instead of raw numbers.

## Motion (`Motion` in `ui/theme/Theme.kt`)
All animation goes through `Motion`, so the Customize settings (animations on/off, speed
0.5x–2x) apply everywhere. Never write a bare `tween(...)`, `spring(...)`, `fadeIn()` or
`expandVertically()`: the defaults ignore those settings and move differently from the rest.

| Token | Use for |
|---|---|
| `Motion.spatial()` | things that move or resize: panels, sheets, pills, indicators |
| `Motion.spatialFast()` | small, quick movements: press feedback, badges, toggles |
| `Motion.effects()` | fades and colour changes (no overshoot) |
| `Motion.smooth()` | content arriving: messages, reactions (no overshoot) |
| `Motion.tween(ms, easing)` | fixed-length entrances that must not wobble |
| `Motion.spring(d, k)` | a hand-tuned spring (still follows speed + on/off) |
| `Motion.expandIn()` / `shrinkOut()` | showing/hiding a row or bar |
| `Motion.popIn()` / `popOut()` | showing/hiding a floating button or badge |
| `Motion.fadeIn()` / `fadeOut()` | cross-fades |

Expressive looks (Material You) get gentle bounce; flat looks (Discord, Root) are critically damped.

**Only one thing animates a size.** If children use `AnimatedVisibility(expandVertically)`,
the parent must not also use `animateContentSize`: the two chase each other and wobble.

## Performance (keeps animations at the display's refresh rate)
- Values that change every frame (drag offsets, animated alpha, scale, blur, scroll) are
  read in the **draw or layout phase**, never in composition:
  `Modifier.graphicsLayer { … }`, `Modifier.offset { … }`, `Modifier.layout { … }`,
  `drawBehind { … }`. Reading them in a composable body recomposes the whole subtree every frame.
- Derive booleans from fast-changing state with `derivedStateOf` (e.g. "panel is slid open"),
  so the UI recomposes when the boolean flips, not on every pixel of movement.
- Pass progress into small child composables as a lambda (`progress: () -> Float`) so only
  that child updates during a gesture (see `ReplyIcon` in `ChatPane.kt`).
- Blur with `graphicsLayer { renderEffect = blurEffect(r) }` (cached per whole-pixel radius),
  not `Modifier.blur(animatedValue)`.
- Never allocate in draw lambdas that run every frame (paints, shaders, mask filters); cache them.
- Don't restart animations from a `LaunchedEffect` keyed on a value that changes every frame;
  collect it with `snapshotFlow` instead.

## Shape (`Radii` in `ui/theme/Theme.kt`)
Corners come from the look and the Customize → rounding slider. Don't hard-code corner radii
for surfaces.

| Token | Use for |
|---|---|
| `Radii.row` | list rows, channel rows, settings rows |
| `Radii.tile` | thumbnails, small tiles inside cards |
| `Radii.card` | inline cards: embeds, menus, toasts, text fields |
| `Radii.panel` | standalone cards and panels |
| `Radii.dialog` | dialogs |
| `Radii.sheet` | bottom sheets (top corners only) |
| `Radii.pill` | chips, tabs, badges, fully rounded buttons |

Small icon tiles (≤ 48 dp, e.g. channel pictures) may keep a fixed 10–12 dp corner.

## Spacing and size
- 4 dp grid: paddings and gaps are multiples of 4 (8, 12, 16, 20, 24). Screen edge padding 16–20 dp.
- Touch targets at least 48×48 dp (icons can be 20–24 dp inside that); 8 dp between targets.
- Icons: 24 dp default, 20 dp in dense rows, 18 dp in chips, 16 dp or less only as decorations.

## Type
Use `MaterialTheme.typography` roles, not raw `fontSize`. Titles `titleMedium`/`titleLarge`,
body `bodyLarge` (16 sp), secondary `bodySmall` in `onSurfaceVariant`, labels `labelMedium`/`labelLarge`.
Raw sizes are only for display art (the You page, login logo).

## Colour
- Use colour roles from `MaterialTheme.colorScheme`, never hex values in screens (hex lives only in `Theme.kt`).
- Text pairs must reach **4.5:1** contrast (3:1 for large text and icons). Settings → Colors
  warns when a custom text colour falls below that and offers a one-tap fix.
- Text on avatars/hues uses `contentOn(bg)`.

## Consistency checklist for a new screen
1. Background from a surface role; panels from `panel(...)`.
2. Back/close in the top-left (or top-right for overlays), 48 dp target.
3. Corners from `Radii`, motion from `Motion`, type from `typography`.
4. Pressable surfaces get `pressScale(src)` like the rest of the app.
5. Lists: stable `key`s, `animateItem(placementSpec = Motion.smooth())`.
