import SwiftUI
import Shared

// MARK: - Home's lower sections (desktop `home-sections.js`, Android's HomeLowerSections.kt)
//
// The Fresh Drops / Critical Darlings / Pop of the Tops halves, Concerts near
// you and Ask Shuffleupagus. What shows, its order and every word come from the
// shared page (`IosHomePage`) or `HomeCopy`; Swift only draws.

private let halfGap: CGFloat = 16

// MARK: Halves

/// Home's halves, laid out for the window: side by side once both fit three
/// cards (`LayoutRules.halvesSideBySide`), else stacked full width, with cards
/// that grow to fill each half (`LayoutRules.rowCardWidth`). `half` draws one
/// half at the card width it's given.
struct HomeHalvesLayout<Half: View>: View {
    let halves: [IosHomeHalf]
    var stackSpacing: CGFloat = 24
    @ViewBuilder let half: (IosHomeHalf, CGFloat) -> Half
    @Environment(\.pcLayoutWidth) private var layoutWidth

    var body: some View {
        let sideBySide = halves.count > 1 && LayoutRules.shared.halvesSideBySide(available: Double(layoutWidth))
        let outer = sideBySide ? (layoutWidth - 2 * homeGutter - halfGap) / 2 : layoutWidth - 2 * homeGutter
        // Each half pads its row 16 on both sides.
        let cardWidth = PCLayout.rowCardWidth(outer, cards: Int(HomePageKt.HOME_HALF_ROW_CARDS), gutter: 16, gap: halfGap)
        Group {
            if sideBySide {
                HStack(alignment: .top, spacing: halfGap) {
                    ForEach(halves, id: \.kind) { h in half(h, cardWidth).frame(maxWidth: .infinity) }
                }
            } else {
                VStack(spacing: stackSpacing) {
                    ForEach(halves, id: \.kind) { h in half(h, cardWidth) }
                }
            }
        }
        .padding(.horizontal, homeGutter)
    }
}

/// One half: a hero band in the page's gradient (the icon, the uppercase title, See all), then a row of
/// album cards, skeletons, or the compact error with Try again.
struct HomeHalfView: View {
    let half: IosHomeHalf
    let onSeeAll: () -> Void
    let onOpen: (IosHalfCard) -> Void
    let onPlay: (IosHalfCard) -> Void
    let onShown: (IosHalfCard) -> Void
    let onOpenArtist: (IosHalfCard) -> Void
    let onRetry: () -> Void
    /// The cards' width (`HomeHalvesLayout` sizes it for the window).
    var cardWidth: CGFloat = pcRecCardWidth

    private var page: DesignTokens.Page {
        switch half.kind {
        case IosHomePageKt.HALF_KIND_FRESH: return .freshDrops
        case IosHomePageKt.HALF_KIND_CRITICAL: return .criticalDarlings
        default: return .popOfTheTops
        }
    }

    private var icon: AppIcon {
        switch half.kind {
        case IosHomePageKt.HALF_KIND_FRESH: return PageIcon.freshDrops.icon
        case IosHomePageKt.HALF_KIND_CRITICAL: return PageIcon.criticalDarlings.icon
        default: return PageIcon.popOfTheTops.icon
        }
    }

    var body: some View {
        let radius = CGFloat(DesignTokens.shared.RADIUS_SHEET)
        VStack(alignment: .leading, spacing: 0) {
            band
            switch half.body {
            case IosHomePageKt.HALF_BODY_FAILED:
                VStack(alignment: .leading, spacing: 8) {
                    Text(half.errorTitle ?? "").font(.system(size: 14, weight: .semibold)).foregroundStyle(PC.fg1)
                    Text(half.errorText ?? "").font(.system(size: 13)).foregroundStyle(PC.fg2)
                    homeFilledButton(UiMessages.shared.TRY_AGAIN, action: onRetry)
                }
                .padding(16)
            default:
                ScrollView(.horizontal, showsIndicators: false) {
                    // A plain HStack (3 cards): a lazy one left the skeletons zero height.
                    HStack(alignment: .top, spacing: halfGap) {
                        if half.body == IosHomePageKt.HALF_BODY_LOADING {
                            ForEach(0..<Int(HomePageKt.HOME_HALF_ROW_CARDS), id: \.self) { _ in RecCardSkeleton(lines: 1, width: cardWidth) }
                        } else {
                            ForEach(half.cards, id: \.key) { card in
                                HomeHalfCard(card: card, width: cardWidth, onOpen: { onOpen(card) }, onPlay: { onPlay(card) }, onOpenArtist: { onOpenArtist(card) })
                                    .onAppear { if card.needsArt { onShown(card) } }
                            }
                        }
                    }
                    .padding(16)
                }
            }
        }
        .background(PC.bgPrimary)
        .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: radius, style: .continuous).strokeBorder(PC.border))
    }

    private var band: some View {
        HStack(spacing: 12) {
            HStack(spacing: 8) {
                PCIcon(icon: icon, size: 16, tint: PC.onAccent)
                Text(half.title.uppercased())
                    .font(.system(size: 20, weight: .light)).tracking(4)
                    .foregroundStyle(PC.onAccent)
                    .lineLimit(1).minimumScaleFactor(0.7)
                    .accessibilityAddTraits(.isHeader)
            }
            Spacer(minLength: 0)
            Button(action: onSeeAll) {
                Text(HomeCopy.shared.SEE_ALL).font(.system(size: 13, weight: .semibold)).foregroundStyle(PC.onAccent)
                    .frame(minHeight: 44)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(HomeCopy.shared.seeAllIn(title: half.title))
        }
        .padding(.horizontal, 16).padding(.vertical, 4)
        .background(LinearGradient(
            colors: PC.pageGradient(page).map { Color(uiColor: UIColor(hex: $0)) },
            startPoint: .topLeading, endPoint: .bottomTrailing))
    }
}

/// A half's album card: cover with the play disc, Fresh Drops' release-type pill top-right (the type's
/// colour), Critical Darlings' review pill bottom-left, then title and artist.
private struct HomeHalfCard: View {
    let card: IosHalfCard
    var width: CGFloat = pcRecCardWidth
    let onOpen: () -> Void
    let onPlay: () -> Void
    let onOpenArtist: () -> Void
    @Environment(QueuePlaybackCoordinator.self) private var coordinator

    var body: some View {
        let pill: CardPill? = card.badgeColorRgb.flatMap { rgb in
            card.badge.map { CardPill(text: $0, color: rgb.int32Value, alpha: 0.9) }
        }
        let review: AnyView? = (card.badge != nil && card.badgeColorRgb == nil)
            ? AnyView(HomeReasonBadge(text: card.badge!, label: HomeCopy.shared.WHAT_THE_CRITICS_SAID)) : nil
        PCAlbumCard(title: card.title, artist: card.artist, artworkUrl: card.imageUrl, width: width,
                    pill: pill, artBadge: review, onOpen: onOpen, onPlay: onPlay)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(HomeCopy.shared.openLabel(title: card.title))
        .accessibilityAction(.default, onOpen)
        .accessibilityAction(named: HomeCopy.shared.playLabel(title: card.title), onPlay)
        .pcAlbumContextMenu(
            title: card.title, artist: card.artist, artworkUrl: card.imageUrl, coordinator: coordinator,
            onGoToAlbum: onOpen,
            onGoToArtist: card.artist.isEmpty ? nil : { onOpenArtist() })
    }
}

// MARK: Concerts near you

struct HomeConcertCard: View {
    let concert: IosHomeConcert
    let onOpenTour: (String) -> Void
    @Environment(\.openURL) private var openURL

    private let art: CGFloat = 72

    var body: some View {
        let radius = CGFloat(DesignTokens.shared.RADIUS_SHEET)
        HStack(spacing: 12) {
            photo
            VStack(alignment: .leading, spacing: 2) {
                Text(concert.artist).font(.system(size: 14, weight: .semibold)).foregroundStyle(PC.fg1).lineLimit(1)
                Text(concert.venue).font(.system(size: 12)).foregroundStyle(PC.fg2).lineLimit(1)
                if !concert.place.isEmpty {
                    Text(concert.place).font(.system(size: 12)).foregroundStyle(PC.fg2).lineLimit(1)
                }
                // Under the text, aligned to the end, as Android draws it.
                if !concert.tickets.isEmpty {
                    tickets.frame(maxWidth: .infinity, alignment: .trailing).padding(.top, 4)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(12)
        .overlay(RoundedRectangle(cornerRadius: radius, style: .continuous).strokeBorder(PC.border))
        .contentShape(Rectangle())
        .onTapGesture { if let t = concert.tourArtist { onOpenTour(t) } }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(concert.a11yLabel)
        .modifier(ConcertRowAccessibility(
            tourArtist: concert.tourArtist,
            tickets: concert.tickets.compactMap { t in URL(string: t.url).map { (label: t.label, url: $0) } },
            open: onOpenTour))
    }

    private var photo: some View {
        ZStack(alignment: .bottomLeading) {
            RoundedRectangle(cornerRadius: CGFloat(DesignTokens.shared.RADIUS_CARD), style: .continuous).fill(PC.bgSecondary)
            if let url = concert.photoUrl, let u = URL(string: url) {
                // Held a quarter of the way down (desktop's `center 25%`) so faces stay in.
                PCCachedImage(url: u) { img in
                    img.resizable().aspectRatio(contentMode: .fill)
                        .frame(width: art, height: art, alignment: Alignment(horizontal: .center, vertical: .concertPhotoFocus))
                } placeholder: { Color.clear }
                    .frame(width: art, height: art)
            }
            if let date = concert.dateBadge {
                Text(date.uppercased())
                    .font(.system(size: 11, weight: .bold)).tracking(0.4).foregroundStyle(PC.onAccent).lineLimit(1)
                    .padding(.horizontal, 6).padding(.vertical, 2)
                    .background(RoundedRectangle(cornerRadius: CGFloat(DesignTokens.shared.RADIUS_BADGE)).fill(PC.pageFill(.concerts)))
                    .padding(4)
            }
        }
        .frame(width: art, height: art)
        .clipShape(RoundedRectangle(cornerRadius: CGFloat(DesignTokens.shared.RADIUS_CARD), style: .continuous))
    }

    /// Tickets: one seller opens its page; several open a menu naming each.
    @ViewBuilder private var tickets: some View {
        let label = HStack(spacing: 4) {
            Text(HomeCopy.shared.TICKETS).font(.system(size: 12, weight: .semibold))
            if concert.tickets.count > 1 { PCIcon(icon: .chevrondown, size: 16, tint: PC.onAccent) }
        }
        .foregroundStyle(PC.onAccent)
        .padding(.horizontal, 12).frame(minHeight: 44)
        .background(RoundedRectangle(cornerRadius: CGFloat(DesignTokens.shared.RADIUS_BUTTON)).fill(PC.pageFill(.concerts)))
        if concert.tickets.count == 1, let u = URL(string: concert.tickets[0].url) {
            Button { openURL(u) } label: { label }.buttonStyle(.plain)
        } else {
            Menu {
                ForEach(concert.tickets, id: \.url) { t in
                    Button(t.label) { if let u = URL(string: t.url) { openURL(u) } }
                }
            } label: { label }
        }
    }
}

// MARK: Ask Shuffleupagus

/// The heading with the Shuffleupagus mark, the explanation, the field above the button and the caption.
/// With no AI plug-in ("no") it offers "Set up Shuffleupagus" instead. The field (`AskField`) lives in the caller.
struct HomeAsk: View {
    let example: String
    let chatAvailable: String
    @Binding var field: AskField?
    let onAsk: (String) -> Void
    let onSetUp: () -> Void

    var body: some View {
        let radius = CGFloat(DesignTokens.shared.RADIUS_SHEET)
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                HomeReasonMark(outlined: true)
                Text(HomeCopy.shared.ASK_TITLE).font(.system(size: 16, weight: .bold)).foregroundStyle(PC.fg1)
                    .accessibilityAddTraits(.isHeader)
            }
            Text(HomeCopy.shared.ASK_EXPLANATION).font(.system(size: 13)).foregroundStyle(PC.fg2)
            if chatAvailable == "no" {
                homeFilledButton(HomeCopy.shared.SET_UP_SHUFFLEUPAGUS, action: onSetUp)
            } else {
                form
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: radius, style: .continuous).fill(PC.bgSecondary))
        .overlay(RoundedRectangle(cornerRadius: radius, style: .continuous).strokeBorder(PC.border))
        .padding(.horizontal, homeGutter)
        .onAppear { sync() }
        .onChange(of: example) { _, _ in sync() }
    }

    /// Seeds the field, and a new example replaces it only while untouched (`withExample`).
    private func sync() {
        field = field.map { $0.withExample(newExample: example) } ?? AskField.companion.seeded(example: example)
    }

    private var form: some View {
        let current = field ?? AskField.companion.seeded(example: example)
        return VStack(alignment: .leading, spacing: 12) {
            TextField("", text: Binding(
                get: { current.text },
                set: { field = current.edited(value: $0) }))
                .font(.system(size: 16))
                .foregroundStyle(current.muted ? PC.fg3 : PC.fg1)
                .submitLabel(.send)
                .onSubmit { if let p = current.prompt { onAsk(p) } }
                .padding(.horizontal, 12).frame(minHeight: 48)
                .overlay(RoundedRectangle(cornerRadius: CGFloat(DesignTokens.shared.RADIUS_ART), style: .continuous).strokeBorder(PC.border))
                .accessibilityLabel(HomeCopy.shared.ASK_FIELD_LABEL)
            homeFilledButton(HomeCopy.shared.ASK_BUTTON, pill: true, enabled: current.prompt != nil) { if let p = current.prompt { onAsk(p) } }
            Text(HomeCopy.shared.ASK_CAPTION).font(.system(size: 12)).foregroundStyle(PC.fg2)
        }
    }
}

/// A fill-accent button with white text (design system: never white on the theme accent), Android's
/// `FilledButton`: a pill when `pill`, else a rounded rect of `RADIUS_BUTTON`; at least 48 high.
private func homeFilledButton(_ text: String, pill: Bool = false, enabled: Bool = true, action: @escaping () -> Void) -> some View {
    let shape = RoundedRectangle(cornerRadius: pill ? 1000 : CGFloat(DesignTokens.shared.RADIUS_BUTTON), style: .continuous)
    return Button(action: action) {
        Text(text).font(.system(size: 14, weight: .semibold)).foregroundStyle(PC.onAccent)
            .lineLimit(1)
            .padding(.horizontal, 20).padding(.vertical, 12)
            .frame(minHeight: 48)
            .background(shape.fill(PC.fillAccent))
            .opacity(enabled ? 1 : 0.5)
    }
    .buttonStyle(.plain)
    .disabled(!enabled)
}
