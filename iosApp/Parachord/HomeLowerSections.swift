import SwiftUI
import Shared

// MARK: - Home's lower sections (desktop `home-sections.js`, Android's HomeLowerSections.kt)
//
// The Fresh Drops / Critical Darlings / Pop of the Tops halves, Concerts near
// you and Ask Shuffleupagus. What shows, its order and every word come from the
// shared page (`IosHomePage`) or `HomeCopy`; Swift only draws.

private let halfGap: CGFloat = 16

// MARK: Halves

/// One half: a hero band in the page's gradient (the icon, the uppercase title, See all), then a row of
/// album cards, skeletons, or the compact error with Try again.
struct HomeHalfView: View {
    let half: IosHomeHalf
    let onSeeAll: () -> Void
    let onOpen: (IosHalfCard) -> Void
    let onPlay: (IosHalfCard) -> Void
    let onShown: (IosHalfCard) -> Void
    let onRetry: () -> Void

    private var page: DesignTokens.Page {
        switch half.kind {
        case "fresh": return .freshDrops
        case "critical": return .criticalDarlings
        default: return .popOfTheTops
        }
    }

    private var icon: AppIcon {
        switch half.kind {
        case "fresh": return PageIcon.freshDrops.icon
        case "critical": return PageIcon.criticalDarlings.icon
        default: return PageIcon.popOfTheTops.icon
        }
    }

    var body: some View {
        let radius = CGFloat(DesignTokens.shared.RADIUS_SHEET)
        VStack(alignment: .leading, spacing: 0) {
            band
            switch half.body {
            case "failed":
                VStack(alignment: .leading, spacing: 8) {
                    Text(half.errorTitle ?? "").font(.system(size: 14, weight: .semibold)).foregroundStyle(PC.fg1)
                    Text(half.errorText ?? "").font(.system(size: 13)).foregroundStyle(PC.fg2)
                    Button(action: onRetry) {
                        Text(UiMessages.shared.TRY_AGAIN).font(.system(size: 14, weight: .semibold)).foregroundStyle(PC.onAccent)
                            .padding(.horizontal, 20).frame(height: 44)
                            .background(Capsule().fill(PC.fillAccent))
                    }
                    .buttonStyle(.plain)
                }
                .padding(16)
            default:
                ScrollView(.horizontal, showsIndicators: false) {
                    // A plain HStack (3 cards): a lazy one left the skeletons zero height.
                    HStack(alignment: .top, spacing: halfGap) {
                        if half.body == "loading" {
                            ForEach(0..<Int(HomePageKt.HOME_HALF_ROW_CARDS), id: \.self) { _ in RecCardSkeleton(lines: 1) }
                        } else {
                            ForEach(half.cards, id: \.key) { card in
                                HomeHalfCard(card: card, onOpen: { onOpen(card) }, onPlay: { onPlay(card) })
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
        .padding(.horizontal, homeGutter)
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
    let onOpen: () -> Void
    let onPlay: () -> Void

    private let width = pcRecCardWidth

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ZStack {
                pcCover(card.imageUrl, seed: card.title, size: width, radius: CGFloat(DesignTokens.shared.RADIUS_ART),
                        artist: card.artist, album: card.title)
                if let badge = card.badge {
                    if let rgb = card.badgeColorRgb {
                        Text(badge)
                            .font(.system(size: 11, weight: .semibold)).foregroundStyle(PC.onAccent).lineLimit(1)
                            .padding(.horizontal, 6).padding(.vertical, 2)
                            .background(RoundedRectangle(cornerRadius: CGFloat(DesignTokens.shared.RADIUS_BADGE)).fill(pcToken(rgb.int32Value).opacity(0.9)))
                            .padding(4)
                            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                    } else {
                        HomeReasonBadge(text: badge, label: HomeCopy.shared.WHAT_THE_CRITICS_SAID)
                    }
                }
                PCArtPlayButton(title: card.title, onPlay: onPlay)
                    .padding(4)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
            }
            .frame(width: width, height: width)
            Spacer().frame(height: 8)
            RecCardText(text: card.title, primary: true)
            RecCardText(text: card.artist)
        }
        .frame(width: width)
        .contentShape(Rectangle())
        .onTapGesture(perform: onOpen)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(HomeCopy.shared.openLabel(title: card.title))
        .accessibilityAction(.default, onOpen)
        .accessibilityAction(named: HomeCopy.shared.playLabel(title: card.title), onPlay)
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
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
            if !concert.tickets.isEmpty { tickets }
        }
        .padding(12)
        .overlay(RoundedRectangle(cornerRadius: radius, style: .continuous).strokeBorder(PC.border))
        .contentShape(Rectangle())
        .onTapGesture { if let t = concert.tourArtist { onOpenTour(t) } }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(concert.a11yLabel)
        .modifier(ConcertRowAccessibility(
            tourArtist: concert.tourArtist,
            ticket: concert.tickets.count == 1 ? URL(string: concert.tickets[0].url) : nil,
            open: onOpenTour))
        .padding(.horizontal, homeGutter)
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
            if concert.tickets.count > 1 { Image(systemName: "chevron.down").font(.system(size: 10, weight: .bold)) }
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
                PCServiceLogo(serviceId: RecommendationPageKt.SHUFFLEUPAGUS_ID, tint: PC.fg1).frame(width: 20, height: 20)
                Text(HomeCopy.shared.ASK_TITLE).font(.system(size: 16, weight: .bold)).foregroundStyle(PC.fg1)
                    .accessibilityAddTraits(.isHeader)
            }
            Text(HomeCopy.shared.ASK_EXPLANATION).font(.system(size: 13)).foregroundStyle(PC.fg2)
            if chatAvailable == "no" {
                filled(HomeCopy.shared.SET_UP_SHUFFLEUPAGUS, enabled: true, action: onSetUp)
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
            filled(HomeCopy.shared.ASK_BUTTON, enabled: current.prompt != nil) { if let p = current.prompt { onAsk(p) } }
            Text(HomeCopy.shared.ASK_CAPTION).font(.system(size: 12)).foregroundStyle(PC.fg2)
        }
    }

    private func filled(_ text: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(text).font(.system(size: 14, weight: .semibold)).foregroundStyle(PC.onAccent)
                .padding(.horizontal, 20).frame(minHeight: 48)
                .frame(maxWidth: .infinity)
                .background(Capsule().fill(PC.fillAccent))
                .opacity(enabled ? 1 : 0.5)
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}
