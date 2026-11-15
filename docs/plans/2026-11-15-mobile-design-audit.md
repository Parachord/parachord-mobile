# Mobile design audit against desktop

**Date:** 2026-11-15. **Code audited:** parachord-android `main` at 5123e1b8 (Android and iOS); desktop `main` at 5737c770. Read-only.

**Desktop reference:**
- the "Design system: don't let it drift" rules in desktop's `CLAUDE.md` (#1380)
- desktop's own audit, `docs/plans/2026-11-13-design-audit.md`
- the Home redesign spec, `docs/superpowers/specs/2026-11-12-home-redesign-design.md`
- the shared components `section-header.js` (#1370), `filter-chips.js` (#1371) and `visible-sources.js` (#1407)

The rules that came out of this are in [DESIGN_SYSTEM.md](../DESIGN_SYSTEM.md).

## 1. Summary

The colour values match desktop on both apps. Desktop's November changes have reached neither app: the section titles, the filter chips, the fill-accent rule and the Home redesign.

| | Android | iOS |
|---|---|---|
| Theme colour values | match | match |
| Fill accent (no white on `#a78bfa`) | missing: Material `primary` is `#A78BFA` with `onPrimary` white in dark | missing: about 11 white-on-`PC.accent` fills |
| Dark `accent-surface` | opaque `#1E1E2E` | opaque `0x1e1e2e` |
| Card shadow token | none (hard-coded elevations) | none (24 ad-hoc `.shadow`) |
| Colour literals outside the theme | 228 (about 20 are `#7C3AED`) | 152 |
| Service colours from the plugin manifest | no (hand-copied table, plus a second palette) | no (two hand-copied tables, plus a Settings catalog) |
| Section title | shared, but uppercase accent micro-caps | `pcSectionHeader` (purple uppercase 14) on 3 Home calls; other screens differ |
| "See all" links | none | none |
| Filter chips | no shared one; about 13 styles | no shared one; 7 copies |
| Source icon dimming | shared `ResolverBadges`, at or below 0.80 | same |
| Unplayable row dimmed | no | yes (`hasPlayableSource`) |
| One album / playlist card | no | no |
| Home redesign | not started | not started |
| Type / radius scales | about 40 off-scale font uses, about 22 off-scale radii | about 35% of font sizes off-scale; `PCSpace` / `PCRadius` unused |
| Dynamic Type | n/a | 463 fixed `.system(size:)` |

## 2. Shared gaps (both apps, so fix once where possible)

1. **Token values are kept twice**, in `ui/theme/Color.kt` and `Theme.swift`, and have already drifted from the iOS handoff's `colors_and_type.css`. A token table in `shared/commonMain` (hex values, scales, page colours, release-type colours) that both platforms read would hold them in one place. The rest of the app already works this way: the logic lives in shared code and the drawing lives on each platform.
2. **Service colours.** Both apps hand-copy the colours. Local Files is wrong on both (`#A855F7` / `#4b5563`; the manifest says `#6366f1`), and iOS also has Bandcamp `#1da0c3`, Apple Music `#fa233b` and YouTube `#ff0033`. The shared `PluginManager` already exposes each plugin's manifest `color` (`PluginManager.kt:761`), and every bundled plugin sets it. Both apps should read it, as desktop does.
3. **Dimming threshold.** The shared `ResolverBadges.DIM_AT_OR_BELOW` is 0.80, and desktop dims below 0.95. It's one constant in shared code, so the change is small, but it has to be a decision to follow desktop, since at 0.95 every fuzzy match (exactly 0.95) stays bright and only nonstandard scores dim.
4. **Release-type colours.** iOS has two maps that disagree (`CuratedAlbumScreens.swift:461`, `DetailScreens.swift:1154`), and Android has its own in `FreshDropsScreen`. One shared map is the fix.
5. **Copy:**
   - "..." instead of "…": Android 9, iOS 6.
   - "resolver" in UI text: Android `SettingsScreen.kt:1365`, iOS "Content Resolvers" in `SettingsView.swift:722`.
   - "songs" instead of "tracks": Pop of the Tops on both, plus "No songs yet" on Android Library.
   - Hard-coded plurals: both; there's no shared plural helper. One belongs in `shared/.../format/`.
   - Title Case section titles: both ("Recently Added", "Your Playlists", "Recent Searches").
   - Raw `e.message` reaching the UI: Android `MigrationPreviewDialog.kt:61`, `SettingsViewModel.kt`.
   - "Retry" vs "Try Again" vs "Try again": both.
6. **Home.** Neither app has started the November redesign. Its data rules belong in shared code for both apps, as the desktop spec's "Mobile" section asks: `getHistoryMixes`, `tracksChangedAt` / `lastChangeAdded`, the setup checklist and its dismissals, and Jump back in's "not already playing" check.

## 3. Android

- **Fill accent.** `Theme.kt:17-18` sets `primary = PurpleDark` and `onPrimary = White` for dark mode, so every filled Material component draws white on `#A78BFA`. `NowPlayingScreen.kt:392,540` and `QueueSheet.kt:115` fill with `PurpleDark` too. About 20 hard-coded `Color(0xFF7C3AED)` (`PlaylistDetailScreen`, `CreatePlaylistDialog`, `PlaylistPickerSheet`, `ImportPlaylistDialog`, `DeepLinkConfirmationDialog`) are the right colour, but the token should be named.
- **Page colours.** In `DrawerContent.kt:95-103`, History is `#06B6D4` (that's Collection's colour; History is `#3b82f6`) and Concerts is `#14B8A6` (desktop and the Concerts screen itself use `#10C9B4`). History, Concerts, Recommendations and Playlists have no hero gradient.
- **SectionHeader** (`ui/components/SectionHeader.kt`) is uppercase, `labelMedium` semibold, in `primary`. It has no subtitle and no link slot. It's used on Home, Search, Settings and Artist; the Discover pages, History and Recommendations have no section titles. `DrawerSectionHeader` and `ConfigSectionLabel` are separate copies.
- **Chips.** There are about 9 raw `FilterChip` calls plus `SourceFilterChip`, `AssistChip`, `PlaylistSourceChip` and `MirrorLinkChip`. Fresh Drops and Concerts select with a 20% tint; History with `primary` at 20%. Recommendations puts white text on its source colour, which fails on amber.
- **Service colours.** `ResolverIcon.kt:~47` is a hand-copied table, and `Color.kt`'s `ResolverColors` (used by `ResolverBadge.kt`) is a second palette of Tailwind tints in which Apple Music equals YouTube.
- **Unplayable rows.** `TrackRow` has no dimmed state for a track with no playable source; iOS does.
- **Cards.** `RecentAlbumCard`: 130dp wide, art radius 8, `bodySmall` title. `PlaylistCard`: 12dp clip. `AiAlbumCard` and `WeeklyPlaylistCard` are their own. `ParachordCard`: radius 8, padding 16. Desktop's card is radius 10, padding 10, 13/500 title.
- **Home** still has Continue Listening, Recently Added, Friend Activity, the collection stats, Recent Loves, Weekly Jams / Exploration and the Discover grid. Shuffleupagus album suggestions and Your Playlists exist without the Δ badge or See all.
- **States.** `ErrorState` is duplicated privately in `HistoryScreen.kt:667` and `RecommendationsScreen.kt:418`, as an outlined button.
- **Scales.** Off-scale font sizes: 10 (12), 18 (11), 15 (10), 9, 7, 22, 17. Off-scale radii: 2, 3, 20, 24. `Type.kt` sets no sizes of its own, so the app is on Material's defaults.
- **Accessibility.** There are 101 `contentDescription = null` uses, not individually classified; most are probably decorative. Touch targets weren't checked.

## 4. iOS

- **Fill accent.** White text on `PC.accent` (`#a78bfa` in dark) in selected period/source chips and buttons: `HistoryScreen.swift:182`, `RecommendationsScreen.swift:179`, `FriendProfileScreen.swift:262`, `DetailScreens.swift:1203`, and about 7 more.
- **Text-tertiary carries labels** (`SearchView.swift:215`, the `PCEmptyState` hint).
- **Semantic colours as raw hex**: `0x10B981` in `HomeScreen.swift:240`, `CollectionView.swift:393` and `CuratedAlbumScreens.swift:592`; `0xef4444` in `HomeScreen.swift:759`; `0x22C55E` in `FriendProfileScreen.swift:205`.
- **Service colours** are in three places: `ResolverColor.solid` (`Theme.swift:127`), `ResolverIconSquare` (`ResolverBadgeRow.swift:22`) and the Settings catalog (`SettingsView.swift:50`, with no Subsonic or Amazon). The Settings catalog falls back to the manifest colour only for plugins it doesn't list.
- **Source icons** are initials on coloured squares, not the service logos. There's no "resolving" shimmer.
- **Page colours.** The drawer matches, except Pop of the Tops (`ea580c`; desktop `#f97316`). Critical Darlings' gradient runs red→amber, the opposite of desktop. History's is cyan only (desktop is cyan→blue→indigo). The tab bar has no page colours, which is fine for a tab bar.
- **Section titles.** `pcSectionHeader` is purple uppercase 14/600 and used 3 times, all on Home. Search (13/600 `fg2`), Settings (11/700 uppercase `fg3`), `PlaylistDetailView`'s `sectionLabel` and `ProviderConfigSheet` each have their own.
- **Chips.** There are 7 inline copies. History, Friend and Recommendations share code but not a view. Fresh Drops uses a tinted fill with a border, Concerts a teal tint, and Pop of the Tops a bare menu.
- **Cards.** Home cards are 130pt, radius 10, 13/500 (which matches desktop). Pop of the Tops cards are flexible, radius 8, 14/500. There's no shared card.
- **Home** has the same old sections as Android (Continue Listening, Recently Added, Your Playlists with "N tracks", Discover tiles, Weekly Jams / Exploration, Friend Activity, AI suggestions, stats, Recent Loves).
- **States.** `DetailScreens.swift:1195` hand-rolls an error state ("Something went wrong…", "Try Again").
- **Scales.** Of 463 `.system(size:)` calls, off-scale sizes include 15 (65 uses), 10 (17), 18 (12), 21/22 (12), 9 (8) and 17 (7). Off-scale radii: 3, 5, 14 (7), 18, 28, 32. `PCSpace` and `PCRadius` have no uses outside `Theme.swift`. Gutters: 20 (102 uses), 16 (28) and 24 (11).
- **Accessibility.** There are 7 `accessibilityLabel`s against 115 SF Symbol images and 288 buttons. Sampled icon-only buttons in Shell, Chat and Home have none. Text doesn't scale with Dynamic Type.

## 5. Suggested order

1. **Tokens in shared code**, plus the small fixes in the same PR:
   - fill-accent on both apps, including Android's dark `primary` / `onPrimary`
   - dark `accent-surface`
   - Android drawer History and Concerts colours, iOS Pop of the Tops colour and Critical Darlings gradient direction
   - semantic colours via tokens
   This is low risk and visible at once.
2. **Service colours from the plugin manifest** on both apps; delete the hand-copied tables. Includes Local Files.
3. **SectionHeader and FilterChips** as one shared component per platform, then move every screen onto them, with "See all" links in page colours.
4. **One card per platform** (album, playlist), and the unplayable-row dim on Android.
5. **Copy pass**: a shared plural helper, "…", glossary terms, sentence case, "Try again", no raw errors.
6. **Home redesign**: shared data rules first (`getHistoryMixes`, freshness, setup items), then each app's layout.
7. **Accessibility**: labels on icon-only buttons, touch targets, Dynamic Type on iOS.
8. **Decide the dimming threshold** with desktop (0.80 vs 0.95).

None of this needs a desktop ticket: desktop is the reference here. The one open question for desktop is whether mobile should follow its 0.95 dim.
