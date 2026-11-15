# Parachord mobile design system

The design rules both mobile apps follow. Desktop is the source of truth: its tokens are in `index.html`, its rules in the "Design system: don't let it drift" section of its `CLAUDE.md` (#1380), and its shared components in `src/renderer/components/` ([Parachord/parachord](https://github.com/Parachord/parachord)). This file translates them to Compose and SwiftUI. When desktop changes a rule, change it here in the same piece of work.

Where the apps stand against these rules today: [the 2026-11-15 design audit](plans/2026-11-15-mobile-design-audit.md).

Where it lives in code:
- **Shared (the values):** `shared/commonMain/.../ui/DesignTokens.kt`. One table, plain `0xRRGGBB` ints, that both apps read: the theme colours (light and dark), the fill accent, semantic colours, the always-dark player surfaces, page colours and hero gradients (`DesignTokens.Page`), release-type colours (`releaseTypeColor`), and the type, spacing and radius scales. Service colours are `ServiceColors` in the same file: each plugin's manifest `color`, reported by the shared `PluginManager` as it loads plugins, with a fallback for before they load that a test keeps equal to the bundled manifests (`ServiceColorsManifestTest`). Change a value here, never in a platform file.
- Android: `app/.../ui/theme/` only converts them (`Color.kt`: `tokenColor`, `FillAccent`, `serviceColor`, `releaseTypeColor`, `Page.composeColor` / `composeGradient`), plus `Theme.kt`, `Type.kt`, `Shape.kt` and `app/.../ui/components/`.
- iOS: `iosApp/Parachord/Theme.swift` only converts them (`PC`, including `PC.fillAccent`, `PC.page(_:)`, `PC.pageGradient(_:)`, `PC.releaseType(_:)`; `ResolverColor.solid(_:)` for services). `PCSpace` / `PCRadius` are there too.
- Shared rules both apps apply (which source icons show and dim, copy strings) live in `shared/commonMain` too (`ResolverBadges`, `UiMessages`, `PlaybackMessages`).

---

## 1. Colour

### Theme tokens

| Token | Light | Dark | Use |
|---|---|---|---|
| bg-primary | `#ffffff` | `#161616` | page background |
| bg-secondary | `#f9fafb` | `#1e1e1e` | secondary surfaces, idle chips |
| bg-elevated | `#ffffff` | `#252525` | sheets, dialogs |
| bg-inset | `#f3f4f6` | `#1a1a1a` | recessed areas |
| card-bg | `#ffffff` | `#1e1e1e` | cards |
| card-border | `#e5e7eb` | `#2a2a2a` | card edge |
| text-primary | `#111827` | `#f3f4f6` | titles, body |
| text-secondary | `#6b7280` | `#9ca3af` | artist names, metadata, labels |
| text-tertiary | `#9ca3af` | `#6b7280` | placeholders only |
| border-default | `#e5e7eb` | `#2e2e2e` | dividers, outlines |
| border-light | `#f3f4f6` | `#252525` | faint dividers |
| accent | `#7c3aed` | `#a78bfa` | links, outlines, active icons, selected text |
| accent-hover / pressed | `#6d28d9` | `#8b5cf6` | |
| accent-surface | `#ede9fe` | `#8b5cf6` at 15% | accent-tinted surface |
| **fill-accent** | `#7c3aed` | `#7c3aed` | anything filled that carries white text |
| success | `#10b981` | `#10b981` | |
| warning | `#f59e0b` | `#f59e0b` | |
| error | `#ef4444` | `#ef4444` | |
| on-tour | `#10c9b4` | `#10c9b4` | On Tour dot |
| hosted chip | `#3b82f6` on `#eff6ff` | same | the `🌐 Hosted` chip |

Always-dark player surfaces (mini player, Now Playing, the queue, the car-style surfaces): bg `#161616`, elevated `#1e1e1e`, text `#f3f4f6` / `#9ca3af`, in both themes.

Rules:
- **White text never sits on the theme accent.** In dark mode the accent is `#a78bfa`, and white on it is 2.7:1. Filled buttons, selected chips, play buttons and badges with white text use **fill-accent** (`#7c3aed`) in both themes. Android's Material `primary` is the theme accent, so a filled Material component (`Button`, `Switch`, `FilterChip`, `FloatingActionButton`) needs fill-accent set explicitly, or `onPrimary` must not be white in dark.
- **Text-tertiary is for placeholders only.** Labels, hints and metadata use text-secondary: tertiary fails 4.5:1 in both themes.
- **No colour literals outside the theme files.** Use a token. If none fits, add one to `DesignTokens` (both apps then read it) and to this table. Success, warning and error are always the tokens, never their own hex.
- **Never Material You dynamic colour, never the iOS system tint.** Every interactive element gets an explicit colour.
- Dark mode follows the in-app setting: Android `ParachordTheme.isDark` (never `isSystemInDarkTheme()`), iOS `ThemeObserver` → `.preferredColorScheme`.

### Service colours

A service's colour is its plugin's manifest `color`, never a hand-copied hex: both apps ask the shared `ServiceColors` (Android `serviceColor(id)`, iOS `ResolverColor.solid(id)`). Current manifest values, for reference:

| Service | Colour |
|---|---|
| Spotify | `#1DB954` |
| Apple Music | `#FA243C` |
| Amazon Music | `#25D1DA` |
| YouTube | `#FF0000` |
| Bandcamp | `#629AA9` |
| SoundCloud | `#FF5500` |
| Subsonic | `#14b8a6` |
| Local Files | `#6366f1` |
| Last.fm | `#D51007` |
| ListenBrainz | `#353070` |

The source icon is the service's logo on a square of that colour. A filter chip for a service uses the same colour (see FilterChips).

### Page colours

Each page has one colour, used by its drawer or tab entry, its hero and any "See all" link that leads to it.

| Page | Colour | Hero gradient |
|---|---|---|
| Home | `#7c3aed` | violet |
| Collection | `#06b6d4` | |
| History | `#3b82f6` | cyan → blue → indigo |
| Fresh Drops | `#10b981` | emerald → teal → cyan |
| Pop of the Tops | `#f97316` | orange → pink → purple |
| Critical Darlings | `#ef4444` | amber → orange → red |
| Concerts | `#10c9b4` | teal → cyan |
| Playlists | `#ec4899` | rose → pink → fuchsia |
| Recommendations | `#f59e0b` | amber → orange |

Hero titles are uppercase, light weight, wide tracking (the collapsed desktop hero), styled with an uppercase modifier, never typed in caps.

### Release types

One map for every screen (Fresh Drops, an artist's discography, an album page, Home): `DesignTokens.releaseTypeColor`. Album `#6366f1`, EP `#a855f7`, Single `#db2777` (desktop's Fresh Drops chips), Live `#d97706`, Compilation `#0eb3a0`, anything else `#9ca3af`. A second map on a platform is a bug.

---

## 2. Scales

- **Type (sp / pt):** 11 · 12 · 13 · 14 · 16 · 20 · 24 · 28 · 48. Weights 400 / 500 / 600 / 700. Small uppercase labels: 11, 600, tracking 0.08em.
  - iOS: text should scale with Dynamic Type: prefer `.font(.system(size:…, relativeTo:))` or `@ScaledMetric` over a fixed size.
- **Spacing:** 4-point steps: 2 4 6 8 12 16 20 24 32 40 48. The screen gutter is **16** on phones (desktop's 24 is for its wide content area). 16 between a section header and its content, 24 between cards in a row, 32–40 between sections.
- **Radius:** 4 badge · 6 art inside a card, inputs · 8 buttons, rows, menus · 10 cards · 12 panels · 16 sheets and dialogs · full (pills, circles).
- **Motion:** 100–400 ms, on opacity and transform only.
- **Touch targets:** at least 48dp (Android) / 44pt (iOS), even when the icon is smaller.

---

## 3. Components

Each of these is one shared component per platform. A screen uses it; it never rebuilds or restyles it. Before writing a UI element, look for the shared one; if you'd write a second copy of something, extract it first.

### SectionHeader (desktop `section-header.js`, #1370)
- Title: **20, 700, text-primary**, sentence case. Not uppercase, not accent.
- Optional subtitle: 12, text-secondary, 2 below.
- Right side: an optional "See all" link, 13 / 600, in the destination page's colour (accent when it acts in place).
- Sub-section title: 15, 600.
- 16 below it before the content.
- Page heroes, dialog titles, settings headings and small uppercase group labels are other elements.

### FilterChips (desktop `filter-chips.js`, #1371)
Every row of "pick one" buttons (a source, a release type, a period, a category):
- Pill, 12 / 600, padding 8×12, min height 36 (touch target 48/44), gap 8.
- Idle: bg-secondary, text-primary; a chip with a colour (a service, a release type) shows an 8-point dot in it.
- Selected: filled with its colour, text white or near-black, whichever has more contrast (WCAG). A chip with no colour (All, a period, a category) fills with fill-accent and white text.
- Only one is selected.

### Source icons (desktop `visible-sources.js`, #1407; shared `ResolverBadges`)
One rule for every track list, the queue included:
- Only playable sources (not `noMatch`, at or above the 0.60 floor), from services that can play on this account, one per service, in the user's order.
- A low-confidence source is dimmed (alpha 0.6). **Mobile dims at or below 0.80; desktop dims below 0.95.** Change `ResolverBadges.DIM_AT_OR_BELOW` only together with desktop.
- Row states: resolving (shimmer where the icons go), unplayable (the whole row dimmed, no icons), playable.

### Cards
- Album and playlist cards are one card per platform: art radius 6 inside a 10-radius card, title 13 / 500 text-primary (one line), subtitle 12 text-secondary.
- Cards in a horizontal row are a fixed width; grids use the 16 gutter.
- Long-press opens the item's context menu (`AlbumContextMenu`, `ArtistContextMenu`, `TrackContextMenu`), and the menu is the same on every screen.
- A playlist card: 2×2 mosaic; subtitle per the Home rules below.

### Buttons
- Primary: fill-accent, white text, radius 8 (or full for a pill).
- Secondary: outline border-default, text-primary.
- Destructive: error colour, and only for actions that lose data.
- Text link: accent, no chrome.
- Play button: a fill-accent circle with a white triangle.

### States
- **Loading:** a skeleton with the same box as the content (grid, card size, row count), so nothing moves when it lands. A layout change updates its skeleton in the same change.
- **Empty:** icon (optional), message, hint, and an action when there is one to take.
- **Error:** "Couldn't X. Why. What to do." in text-primary, with a fill-accent "Try again" button. One shared component per platform (Android `ErrorState`, iOS `PCErrorState`).

### Banners
Announcement severities: error red, warn amber, success green, info purple; success and warn are dark enough for white text at 4.5:1. Announcements show on Home only, one at a time.

---

## 4. Home

Desktop redesigned Home in November 2026 (desktop `docs/superpowers/specs/2026-11-12-home-redesign-design.md`, #1354, #1361, #1362, #1366). Mobile Home should follow it, top to bottom:

1. Announcement banner (Home only).
2. Greeting by time of day, with one line on the freshest playlist change ("Daily Brew picked up 12 new tracks this morning"); a "Finish setting up · N of M" button (dismissable setup items, plus the "What's new" row) and "Play Collection Radio".
3. **Jump back in**: four tiles: the freshest playlist, Recent Loves (last 50 loves), the album behind the latest love, the first history mix. Never what's playing or queued. Tapping a tile opens it; its own Play button plays it.
4. **Made from your listening**: Recent Loves first, then the services' play-history mixes (the plugin method `getHistoryMixes`), up to six, with source chips.
5. **Your playlists, freshest first**: six cards ordered by when their tracks last changed (`tracksChangedAt`), a "Δ N" badge for the tracks the latest change added (within 7 days), subtitle "Refreshed 2h ago" / "Since 2023"; See all → Playlists.
6. **Albums you might love**: six albums from the plugins' recommendations and Shuffleupagus, with source chips; See all → Recommendations.
7. **Fresh Drops** and **Critical Darlings**, three cards each, each opening with a slim band in its page's gradient.
8. **Concerts near you**: four cards (date, artist, venue, city); Tickets is the only action.
9. **Ask Shuffleupagus**: a prompt box with an example made locally from recent artists.

Removed on desktop: Recently Added, Continue Listening, the second playlist block, artist suggestions, setup cards in the page.

The data rules (`getHistoryMixes`, `tracksChangedAt` / `lastChangeAdded`, setup dismissal) are shared code and belong in `shared/commonMain`, built once for both apps.

---

## 5. Copy

- **Terms:** *plugin* (what you install), *service* (Spotify, Apple Music…), *source* (where a track plays from). Never "resolver" in UI text. Also *Collection*, *Loves*, *Shuffleupagus*, *track* (not song), *album* / *edition*, *sync* (two-way) / *import* (one-time).
- Sentence case everywhere except screen titles, tabs and Settings section headings. Never type caps; use the uppercase modifier.
- "…", never "...".
- Plurals through one helper, never a hard-coded "tracks".
- Errors: what happened and what to do, never a raw exception message. Retry is "Try again" (`UiMessages.TRY_AGAIN`).
- A confirm button is named for its action and sits next to Cancel. A destructive confirmation says what is lost.
- Success is a snackbar or toast, not a dialog.
- Strings both apps show come from shared code (`UiMessages`, `PlaybackMessages`, `CarMessages`).

## 6. Accessibility

- Every icon-only control has a label (Android `contentDescription`, iOS `accessibilityLabel`). Decorative icons next to text get `null` / `.accessibilityHidden(true)`.
- Long-press actions are also reachable another way (an overflow button or the item's page).
- Text contrast at least 4.5:1: the fill-accent and text-tertiary rules above exist for this.
- iOS text scales with Dynamic Type.

## 7. Checking a UI change

Look at it in light mode, dark mode and the smallest phone width before calling it done, and update its skeleton in the same change. When the change is visible on desktop too, check desktop's look first.
