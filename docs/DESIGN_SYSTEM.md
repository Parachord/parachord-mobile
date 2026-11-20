# Parachord mobile design system

The design rules both mobile apps follow. Desktop is the source of truth: its tokens are in `index.html`, its rules in the "Design system: don't let it drift" section of its `CLAUDE.md` (#1380), and its shared components in `src/renderer/components/` ([Parachord/parachord](https://github.com/Parachord/parachord)). This file translates them to Compose and SwiftUI. When desktop changes a rule, change it here in the same piece of work.

Where the apps stand against these rules today: [the 2026-11-15 design audit](plans/2026-11-15-mobile-design-audit.md).

Where it lives in code:
- **Shared (the values):** `shared/commonMain/.../ui/DesignTokens.kt`. One table, plain `0xRRGGBB` ints, that both apps read: the theme colours (light and dark), the fill accent, semantic colours, the always-dark player surfaces, page colours and hero gradients (`DesignTokens.Page`), release-type colours (`releaseTypeColor`), and the type, spacing and radius scales. Service colours are `ServiceColors` in the same file: each plugin's manifest `color`, reported by the shared `PluginManager` as it loads plugins, with a fallback for before they load that a test keeps equal to the bundled manifests (`ServiceColorsManifestTest`). Change a value here, never in a platform file.
- Android: `app/.../ui/theme/` only converts them (`Color.kt`: `tokenColor`, `FillAccent`, `serviceColor`, `releaseTypeColor`, `Page.composeColor` / `composeGradient`), plus `Theme.kt`, `Type.kt`, `Shape.kt` and `app/.../ui/components/`.
- iOS: `iosApp/Parachord/Theme.swift` only converts them (`PC`, including `PC.fillAccent`, `PC.page(_:)`, `PC.pageGradient(_:)`, `PC.releaseType(_:)`; `ResolverColor.solid(_:)` for services). `PCSpace` / `PCRadius` are there too.
- Shared rules both apps apply (which source icons show and dim, copy strings) live in `shared/commonMain` too (`ResolverBadges`, `UiMessages`, `PlaybackMessages`), and so do desktop's icons and logos (`shared/.../ui/icons/`, see Icons).

---

## 1. Colour

### Theme tokens

Values from desktop's design audit ([parachord#1393](https://github.com/Parachord/parachord/pull/1393), merged into desktop `main`): `index.html` and `docs/DESIGN_SYSTEM.md` there.

| Token | Light | Dark | Use |
|---|---|---|---|
| bg-primary | `#ffffff` | `#161616` | page background |
| bg-secondary | `#f9fafb` | `#1e1e1e` | secondary surfaces, idle chips |
| bg-elevated | `#ffffff` | `#252525` | sheets, dialogs, menus |
| bg-inset | `#f3f4f6` | `#1a1a1a` | recessed areas, neutral chips |
| card-bg | `#ffffff` | `#252525` | cards |
| card-border | `#e5e7eb` | `#2e2e2e` | card edge |
| placeholder-bg | `#e5e7eb` | `#383838` | image placeholders, skeleton bars |
| text-primary | `#111827` | `#f3f4f6` | titles, body |
| text-secondary | `#4b5563` | `#9ca3af` | artist names, metadata, labels |
| text-tertiary | `#6b7280` | `#8b8f97` | hints, counts, timestamps |
| border-default | `#e5e7eb` | `#2e2e2e` | dividers, outlines |
| border-light | `#f3f4f6` | `#252525` | faint dividers |
| border-strong | `#d1d5db` | `#404040` | stronger outlines |
| control-off | `#d1d5db` | `#707070` | a switch track that's off |
| accent | `#7c3aed` | `#a78bfa` | links, outlines, active icons, selected text |
| accent-hover | `#6d28d9` | `#8b5cf6` | |
| accent-secondary | `#8b5cf6` | `#c4b5fd` | |
| accent-soft | `#a78bfa` | `#c4b5fd` | |
| accent-surface | `#ede9fe` | `#8b5cf6` at 15% | accent-tinted surface |
| **fill-accent** | `#7c3aed` | `#7c3aed` | anything filled that carries white text (hover `#6d28d9`) |
| on-accent | `#ffffff` | `#ffffff` | text on any fill |
| fill-neutral | `#4b5563` | `#4b5563` | a mark or fill with white text that has no page or service colour (Android `FillNeutral`, iOS `PC.fillNeutral`) |
| on-tour | `#10c9b4` | `#10c9b4` | On Tour dot |
| hosted chip | `#3b82f6` on `#eff6ff` | same | the `🌐 Hosted` chip |

Status colours, three kinds:

| Use | Success | Warning | Error / danger | Info |
|---|---|---|---|---|
| Dots, icons, indicators (light / dark) | `#10b981` / `#34d399` | `#f59e0b` / `#fbbf24` | `#ef4444` / `#f87171` | `#2563eb` / `#60a5fa` |
| Status text on a page or card, ≥ 4.5:1 (light / dark) | `#15803d` / `#4ade80` | `#b45309` / `#fbbf24` | `#b91c1c` / `#f87171` | `#1d4ed8` / `#93c5fd` |
| Fills with white text: toasts, banners, a confirm button (both themes) | `#15803d` | `#b45309` | `#b91c1c` (hover `#991b1b`) | `#2563eb` |

The neutral fill with white text, for a mark with no page or service colour (a getting-started tile with no service colour), is **fill-neutral** `#4b5563` in both themes.

Always-dark player surfaces (mini player, Now Playing, the queue, the car-style surfaces): bg `#161616`, elevated `#1e1e1e`, text `#f3f4f6` / `#9ca3af`, in both themes.

Rules:
- **White text never sits on the theme accent.** In dark mode the accent is `#a78bfa`, and white on it is 2.7:1. Filled buttons, selected chips, standalone Play buttons, switch tracks and badges with white text use **fill-accent** (`#7c3aed`) in both themes. Android's Material `primary` is the theme accent, so its dark `onPrimary` is `#161616`, not white: a Material component filled with `primary` gets dark content. A fill that carries white uses `FillAccent` explicitly (iOS: `PC.fillAccent`).
- **Status text uses the status text tokens, status fills the fill tokens.** The plain status colours are for dots and icons; they're too light for text on white and too light under white text.
- **Secondary and tertiary text both pass 4.5:1** on the page and on cards in both themes. Map Tailwind greys as gray-900/800 → primary, 700/600 → secondary, 500/400 → tertiary.
- **No colour literals outside the theme files.** Use a token. If none fits, add one to `DesignTokens` (both apps then read it) and to this table. Status colours are always the tokens, never their own hex.
- **Never Material You dynamic colour, never the iOS system tint.** Every interactive element gets an explicit colour.
- Dark mode follows the in-app setting: Android `ParachordTheme.isDark` (never `isSystemInDarkTheme()`), iOS `ThemeObserver` → `.preferredColorScheme`. Android's status colours (`Success`, `Warning`, `Error`, …) are composable reads that follow it.

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
| Custom LLM | `#8b5cf6` |
| Last.fm | `#D51007` |
| ListenBrainz | `#353070` |

The source icon is the service's logo (shared `ServiceLogos`, see Icons) on a square of that colour. A filter chip for a service uses the same colour (see FilterChips).

### Page colours

Each page has one colour, used by its drawer or tab entry (and its 10% tint), its hero, any "See all" link that leads to it, and the page's own controls; a darker **fill** shade for its buttons with white text (≥ 4.5:1). A port of desktop's `lib/page-colors.js`; no two heroes start in the same colour family. Shared: `DesignTokens.Page` (`color`, `fill`, `gradient`).

| Page | Colour | Fill | Hero gradient |
|---|---|---|---|
| Home | the accent | fill-accent | purple → violet → indigo |
| Playlists | `#ec4899` | `#be185d` | rose → pink → fuchsia |
| Collection | `#06b6d4` | `#0e7490` | cyan → cyan → blue |
| History | `#3b82f6` | `#1d4ed8` | sky → blue → indigo |
| Fresh Drops | `#22c55e` | `#15803d` | lime → green → emerald |
| Recommendations | `#f59e0b` | `#b45309` | amber → amber → orange |
| Pop of the Tops | `#f97316` | `#c2410c` | orange → pink → purple |
| Critical Darlings | `#ef4444` | `#b91c1c` | red → red → rose |
| Concerts | `#10c9b4` | `#0f766e` | teal → teal → cyan |

Hero titles are uppercase, light weight, wide tracking (the collapsed desktop hero), styled with an uppercase modifier, never typed in caps.

### Release types

One map for every screen (Fresh Drops, an artist's discography, an album page, Home): `DesignTokens.releaseTypeColor`. Album `#6366f1`, EP `#a855f7`, Single `#db2777` (desktop's Fresh Drops chips), Live `#d97706`, Compilation `#0eb3a0`, anything else `#9ca3af`. A second map on a platform is a bug.

---

## 2. Scales

- **Type (sp / pt):** 11 · 12 · 13 · 14 · 16 · 20 · 24 · 28 · 48. Weights 400 / 500 / 600 / 700. Small uppercase labels: 11, 600, tracking 0.08em.
  - Line height: 1.5 for text that can wrap (desktop's, from Tailwind's preflight; `DesignTokens.LINE_HEIGHT`). On Android set it on secondary text such as a section subtitle or a caption: a bare `Text` takes the theme's body style, 24 sp, twice a 12 sp line.
  - iOS: text should scale with Dynamic Type: prefer `.font(.system(size:…, relativeTo:))` or `@ScaledMetric` over a fixed size.
- **Spacing:** 4-point steps: 2 4 6 8 12 16 20 24 32 40 48. The screen gutter is **16** on phones (desktop's 24 is for its wide content area). 16 between a section header and its content, 24 between cards in a row, 32–40 between sections.
- **Radius:** 4 badge · 6 art inside a card, inputs · 8 buttons, rows, menus · 10 cards · 12 panels · 16 sheets and dialogs · full (pills, circles).
- **Motion:** 100–400 ms, on opacity and transform only.
- **Touch targets:** at least 48dp (Android) / 44pt (iOS), even when the icon is smaller.

---

## 3. Icons

Mobile draws desktop's icons for everything that's Parachord's own, and the platform's icon for a short list of system actions (below). Desktop's set is `src/renderer/components/icons.js` (Lucide paths, ISC licence, plus Spinner, Spinoff and Playlist), and its logos are `brand-logos.js`. Both are copied into shared code by `scripts/sync-desktop-icons.js`, which writes `shared/.../ui/icons/DesktopIcons.kt`:
- `AppIcon`: every icon, named as on desktop (`AppIcon.Play`, `.Heart`, `.Spinoff` …).
- `ServiceLogos.spec(id)`: the source badge logo (`SERVICE_LOGO_PATHS`).
- `ServiceLogos.large(id)`: the large logo, used for Settings tiles and the meta services (`SERVICE_LOGOS`).
- `ServiceLogos.badgeOrLarge(id)`: the badge logo, else the large one.

Never edit the generated file. Change the icon on desktop, then run `node scripts/sync-desktop-icons.js <desktop checkout> origin/main`. With a ref it reads that ref through `git show`, whatever the checkout has checked out.

The shared `SvgPath` turns each path into move / line / cubic / close commands, so the platforms only draw:
- **Android:** `ParachordIcon(AppIcon.X, contentDescription)`, and `serviceLogoVector(id)` for a logo.
- **iOS:** `PCIcon(icon: .x)`, and `PCServiceLogo(serviceId:)` for a logo.

Two logos are bitmaps (`ServiceLogos.BITMAPS`: SoundCloud, Subsonic). Each app bundles desktop's image for them: Android `drawable-nodpi`, iOS `ServiceLogoSoundcloud` / `ServiceLogoSubsonic` in the asset catalog.

**System actions keep the platform's icon.** A few icons carry the platform's own meaning, and people find them by habit. These use SF Symbols on iOS and Material icons on Android, never desktop's version:

| Action | iOS (SF Symbol) | Android (Material) |
|---|---|---|
| Back (navigation bar) | `chevron.left` | `Icons.AutoMirrored.Filled.ArrowBack` |
| Share | `square.and.arrow.up` | `Icons.Filled.Share` |
| More / overflow menu | `ellipsis` (`ellipsis.circle` in a navigation bar) | `Icons.Filled.MoreVert` |
| Close a sheet or dialog | `xmark` | `Icons.Filled.Close` |
| Search (tab bar, navigation bar) | `magnifyingglass` | `Icons.Filled.Search` |

Surfaces the system draws (lock screen and notification media controls, CarPlay, Android Auto, the share sheet, widgets' system chrome) show the system's glyphs anyway. Everything else (transport controls inside the app, love, queue, Spinoff, Shuffleupagus, sources, playlists, page icons, settings rows, empty states) is desktop's.

**Page icons** (the tab bar and the side menu) come from the shared `PageIcon` (`shared/.../ui/icons/PageIcons.kt`), a port of desktop's `lib/page-icons.js`: Home, Search, Playlists `ListMusic`, Collection `Library`, History `RotateCcw`, Fresh Drops `Droplet`, Recommendations `Radar`, Pop of the Tops `ListOrdered`, Critical Darlings `Trophy`, Concerts `Ticket`, Settings. Each entry draws in its page colour. The Search tab is the one exception: it keeps the platform's magnifier, as in the table above.

Rules for desktop's icons:
- **Stroke and fill:** stroke 2, round caps and joins. The transport icons (Play, Pause, SkipBack, SkipForward) are solid; the rest are outlines.
- **Sizes:** 16 in rows, chips, small buttons and inline with text. 20 in buttons and dialog titles. 12 only inside a badge. 32–48 only for an empty state's illustration.
- **Text size:** an icon beside text scales with that text (Dynamic Type on iOS, font scale on Android), as a system icon would. Today's `PCIcon` and `ParachordIcon` are fixed sizes; #709 adds the scaling.
- **Right-to-left:** directional icons (chevrons, arrows, back, list-with-arrow icons such as `QueueAdd` and `ListPlus`) flip in a right-to-left language, as SF Symbols and Android's AutoMirrored icons do. Which icons flip is a shared decision (one list in `shared/.../ui/icons/`), added in #709.
- **Colour:** an icon takes its context's colour. A logo is white on its service's colour square, except where it has its own colours (Achordion).
- **Labels:** an icon is decorative (no label) unless it's the only thing in a control. Then the control carries the label.
- **No other icons:** apart from the system actions above, never a Material icon or SF Symbol, never a text glyph (✕ ✓ ▶) as an icon, never a pasted SVG path, never a hand-copied logo. An icon desktop doesn't have is added on desktop first (`SHAPES` in `icons.js`), then synced.

## 4. Components

Each of these is one shared component per platform. A screen uses it; it never rebuilds or restyles it. Before writing a UI element, look for the shared one; if you'd write a second copy of something, extract it first.

### SectionHeader (desktop `section-header.js`, #1370)
Android `SectionHeader`, iOS `PCSectionHeader`; sizes in the shared `SectionHeaderSpec`.
- Title: **20, 700, text-primary**, sentence case. Not uppercase, not accent.
- Optional subtitle: 12, text-secondary, 2 below.
- Right side: an optional link, 13 / 600: "See all in <page>" in that page's colour, or an in-place action ("Show less") in the accent.
- Sub-section title: 16, 600.
- 16 below it before the content.
- The title never truncates: when the title and the link don't fit on one line, the link goes on its own line under the title and subtitle (`LINK_GAP_BELOW_TITLE` below), and a title wider than the row wraps.
- Page heroes, dialog titles, settings headings and small uppercase group labels are other elements. Settings' group labels are Android `SettingsGroupLabel`; the drawer's and the sync config sheets' small labels keep their own styles too.

### FilterChips (desktop `filter-chips.js`, #1371)
Android `FilterChips` (rows of `FilterChipItem`), iOS `PCFilterChips`; sizes and each chip's look in the shared `FilterChipSpec.look`. Every row of "pick one" buttons (a source, a release type, a period, a category):
- Pill, 12 / 600, padding 8×12, min height 36 (touch target 48/44), gap 8.
- Idle: bg-secondary, text-primary; a chip with a colour (a service, a release type) shows an 8-point dot in it.
- Selected: filled with its colour. Its text is white whenever white reaches 3:1 (Apple Music's red, SoundCloud's orange), else whichever of white or near-black contrasts more (Spotify's green, Amazon's cyan get near-black): the shared `ReadableText`, a port of desktop's `readable-text.js`. A chip with no colour (All, a period, a category) fills with the page's fill on a page with its own colour (Fresh Drops, History, Concerts, Recommendations), else fill-accent, with white text.
- The row scrolls sideways when it doesn't fit (desktop wraps).
- Settings' Light / Dark / System switch is a control, not a filter, and isn't a chip row.
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
- Play button over art (a card's cover, a tile): desktop's `card-art.js` button, a white disc with a large shadow and the triangle in on-art ink (`#111827`, `ON_ART_INK`), the icon 46% of the disc and nudged right 4%. Always shown on a phone, and the art isn't dimmed. One component per platform: Android `ArtPlayButton`, iOS `PCArtPlayButton` (spec in the shared `ArtPlayButtonSpec`): 40 on a 140 card, 36 on a 72 tile, in a 48 touch target.
- A standalone Play control (Now Playing, a page's Play button, Collection Radio): a fill-accent circle or pill with a white triangle.

### States
- **Loading:** a skeleton with the same box as the content (grid, card size, row count), so nothing moves when it lands. A layout change updates its skeleton in the same change.
- **Empty:** icon (optional), message, hint, and an action when there is one to take.
- **Error:** "Couldn't X. Why. What to do." in text-primary, with a fill-accent "Try again" button. One shared component per platform (Android `ErrorState`, iOS `PCErrorState`).

### Banners
Announcement severities: error red, warn amber, success green, info purple; success and warn are dark enough for white text at 4.5:1. Announcements show on Home only, one at a time.

---

## 5. Home

Desktop redesigned Home in November 2026 (desktop `docs/superpowers/specs/2026-11-12-home-redesign-design.md`, #1354, #1361, #1362, #1366). Mobile Home should follow it, top to bottom:

1. Announcement banner (Home only).
2. Greeting by time of day, with one line on the freshest playlist change ("Daily Brew picked up 12 new tracks this morning"); a "Finish setting up · N of M" button (dismissable setup items, plus the "What's new" row) and "Play Collection Radio".
3. **Jump back in**: four tiles: the freshest playlist, Recent Loves (last 50 loves), the album behind the latest love, the first history mix. Never what's playing or queued. Tapping a tile opens it; its own Play button plays it.
4. **Made from your listening**: Recent Loves first, then the services' play-history mixes (the plugin method `getHistoryMixes`), up to six, with source chips.
5. **Your playlists, freshest first**: six cards ordered by when their tracks last changed (`tracksChangedAt`), a "Δ N" badge for the tracks the latest change added (within 7 days), subtitle "Refreshed 2h ago" / "Since 2023"; See all → Playlists.
6. **Albums you might love**: six albums from the plugins' recommendations and Shuffleupagus, with source chips; See all → Recommendations.
7. **Fresh Drops** and **Critical Darlings**, three cards each, each opening with a slim band in its page's gradient.
8. **Concerts near you**: four cards (the artist's photo with the date, artist, venue, city); the card opens the artist's On Tour tab, Tickets opens the seller.
9. **Ask Shuffleupagus**: a prompt box with an example made locally from recent artists.

Removed on desktop: Recently Added, Continue Listening, the second playlist block, artist suggestions, setup cards in the page.

The data rules (`getHistoryMixes`, `tracksChangedAt` / `lastChangeAdded`, setup dismissal) are shared code and belong in `shared/commonMain`, built once for both apps.

---

## 6. Copy

- **Terms:** *plugin* (what you install), *service* (Spotify, Apple Music…), *source* (where a track plays from). Never "resolver" in UI text. Also *Collection*, *Loves*, *Shuffleupagus*, *track* (not song), *album* / *edition*, *sync* (two-way) / *import* (one-time).
- Sentence case everywhere except screen titles, tabs and Settings section headings. Never type caps; use the uppercase modifier.
- "…", never "...".
- Plurals through one helper, never a hard-coded "tracks".
- Errors: what happened and what to do, never a raw exception message. Retry is "Try again" (`UiMessages.TRY_AGAIN`).
- A confirm button is named for its action and sits next to Cancel. A destructive confirmation says what is lost.
- Success is a snackbar or toast, not a dialog.
- Strings both apps show come from shared code (`UiMessages`, `PlaybackMessages`, `CarMessages`).

## 7. Accessibility

- Every icon-only control has a label (Android `contentDescription`, iOS `accessibilityLabel`). Decorative icons next to text get `null` / `.accessibilityHidden(true)`.
- Long-press actions are also reachable another way (an overflow button or the item's page).
- Text contrast at least 4.5:1: the fill-accent and status-text rules above exist for this.
- iOS text scales with Dynamic Type.

## 8. Checking a UI change

Look at it in light mode, dark mode and the smallest phone width before calling it done, and update its skeleton in the same change. When the change is visible on desktop too, check desktop's look first.
