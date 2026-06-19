import SwiftUI
import Shared

// MARK: - DJ Chat / Shuffleupagus (#223 / #202)
//
// iOS port of Android's ChatScreen. All orchestration (tool loop, per-provider
// history, providers, system prompt) is the SHARED AiChatService, reached through
// flat IosContainer methods. This file is just the SwiftUI surface + the
// {{type|…}} card parsing (mirrors Android's parseMessage / ChatCardRow).

@MainActor
@Observable
final class ChatViewModel {
    private let container = IosContainer.companion.shared

    struct Msg: Identifiable { let id = UUID(); let role: String; let content: String }
    struct Provider: Identifiable { let id: String; let name: String; let configured: Bool }

    var messages: [Msg] = []
    var providers: [Provider] = []
    var selectedProviderId: String?
    var input = ""
    var isLoading = false
    var progress: String?

    var selectedProvider: Provider? { providers.first { $0.id == selectedProviderId } }

    private static let known: [(String, String)] = [("chatgpt", "ChatGPT"), ("claude", "Claude"), ("gemini", "Gemini")]

    func start() async {
        if !providers.isEmpty { return }
        var list: [Provider] = []
        for (id, name) in Self.known {
            let configured = (try? await container.chatProviderConfigured(providerId: id))?.boolValue ?? false
            list.append(Provider(id: id, name: name, configured: configured))
        }
        providers = list
        let saved = try? await container.chatSelectedProvider()
        let target = list.first { $0.id == saved && $0.configured } ?? list.first { $0.configured }
        if let t = target { await select(t.id) }
    }

    func select(_ id: String) async {
        selectedProviderId = id
        try? await container.chatSetSelectedProvider(id: id)
        await reload()
    }

    private func reload() async {
        guard let id = selectedProviderId else { messages = []; return }
        let msgs = (try? await container.chatMessages(providerId: id)) ?? []
        messages = msgs.map { Msg(role: $0.role, content: $0.content) }
    }

    func send() async {
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let id = selectedProviderId, !text.isEmpty, !isLoading else { return }
        input = ""
        isLoading = true
        progress = nil
        messages.append(Msg(role: "user", content: text))
        _ = try? await container.chatSend(providerId: id, userMessage: text) { [weak self] p in
            Task { @MainActor in self?.progress = p }
        }
        await reload()
        isLoading = false
        progress = nil
    }

    func clear() async {
        guard let id = selectedProviderId else { return }
        try? await container.chatClear(providerId: id)
        messages = []
    }
}

// MARK: - Screen

struct ChatScreen: View {
    var onClose: () -> Void = {}
    @Environment(QueuePlaybackCoordinator.self) private var coordinator
    @State private var model = ChatViewModel()
    @FocusState private var inputFocused: Bool
    private let container = IosContainer.companion.shared

    /// Push live playback state so the system-prompt context + shuffle/control
    /// tools read fresh state (the Kotlin side can't read @MainActor cross-thread).
    private func pushSnapshot() {
        container.updateChatPlaybackSnapshot(
            currentTrack: coordinator.currentTrack, isPlaying: coordinator.isPlaying,
            upNext: coordinator.upNext, shuffleEnabled: coordinator.shuffleEnabled)
    }
    private func doSend() { pushSnapshot(); Task { await model.send() } }

    var body: some View {
        VStack(spacing: 0) {
            header
            Divider().overlay(Color.white.opacity(0.08))
            messageList
            inputBar
        }
        .background(PC.Player.bg.ignoresSafeArea())
        .preferredColorScheme(.dark)
        .task { await model.start() }
    }

    // Top bar: close, title + provider picker, clear.
    private var header: some View {
        HStack(spacing: 12) {
            Button { onClose() } label: {
                Image(systemName: "chevron.right").font(.system(size: 16, weight: .semibold)).foregroundStyle(.white)
                    .frame(width: 32, height: 32).background(.white.opacity(0.1), in: Circle())
            }
            VStack(alignment: .leading, spacing: 1) {
                Text("Shuffleupagus").font(.system(size: 16, weight: .bold)).foregroundStyle(.white)
                if !model.providers.isEmpty {
                    Menu {
                        ForEach(model.providers) { p in
                            Button { Task { await model.select(p.id) } } label: {
                                if p.id == model.selectedProviderId { Label(p.name, systemImage: "checkmark") }
                                else { Text(p.configured ? p.name : "\(p.name) (not configured)") }
                            }.disabled(!p.configured)
                        }
                    } label: {
                        HStack(spacing: 3) {
                            Text(model.selectedProvider?.name ?? "Select AI").font(.system(size: 12))
                            Image(systemName: "chevron.down").font(.system(size: 9, weight: .semibold))
                        }.foregroundStyle(PC.accentSoft)
                    }
                }
            }
            Spacer()
            if !model.messages.isEmpty {
                Button { Task { await model.clear() } } label: {
                    Image(systemName: "trash").font(.system(size: 15)).foregroundStyle(.white.opacity(0.6))
                }
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 10)
    }

    private var messageList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 12) {
                    if model.messages.isEmpty && !model.isLoading {
                        emptyState.padding(.top, 80)
                    }
                    ForEach(model.messages) { m in
                        if m.role == "user" { userBubble(m.content) } else { assistantBubble(m.content) }
                    }
                    if model.isLoading { loadingBubble }
                    Color.clear.frame(height: 1).id("bottom")
                }
                .padding(.horizontal, 14).padding(.vertical, 12)
            }
            .onChange(of: model.messages.count) { withAnimation { proxy.scrollTo("bottom", anchor: .bottom) } }
            .onChange(of: model.isLoading) { withAnimation { proxy.scrollTo("bottom", anchor: .bottom) } }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 10) {
            Image(systemName: "sparkles").font(.system(size: 34)).foregroundStyle(PC.accentSoft)
            Text("Ask your DJ").font(.system(size: 17, weight: .semibold)).foregroundStyle(.white)
            Text("“Play something upbeat”, “Queue an album like Kid A”, “Make me a focus playlist”.")
                .font(.system(size: 13)).foregroundStyle(.white.opacity(0.5)).multilineTextAlignment(.center)
                .padding(.horizontal, 40)
        }
    }

    private func userBubble(_ text: String) -> some View {
        HStack {
            Spacer(minLength: 40)
            Text(text).font(.system(size: 14)).foregroundStyle(.white)
                .padding(.horizontal, 14).padding(.vertical, 10)
                .background(PC.accent, in: .rect(topLeadingRadius: 16, bottomLeadingRadius: 16, bottomTrailingRadius: 4, topTrailingRadius: 16))
        }
    }

    private func assistantBubble(_ text: String) -> some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: "sparkles").font(.system(size: 13)).foregroundStyle(PC.accentSoft).padding(.top, 10)
            VStack(alignment: .leading, spacing: 6) {
                ForEach(Array(parseSegments(text).enumerated()), id: \.offset) { _, seg in
                    switch seg {
                    case .text(let t):
                        if let clean = cleanSegment(t) {
                            markdownText(clean).font(.system(size: 14)).foregroundStyle(Color(white: 0.9))
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    case .card(let c): ChatCardView(card: c)
                    }
                }
            }
            .padding(.horizontal, 14).padding(.vertical, 10)
            .background(.white.opacity(0.07), in: .rect(topLeadingRadius: 16, bottomLeadingRadius: 4, bottomTrailingRadius: 16, topTrailingRadius: 16))
            Spacer(minLength: 40)
        }
    }

    private var loadingBubble: some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: "sparkles").font(.system(size: 13)).foregroundStyle(PC.accentSoft).padding(.top, 10)
            HStack(spacing: 6) {
                if let p = model.progress {
                    Text(p).font(.system(size: 13)).foregroundStyle(.white.opacity(0.6))
                } else {
                    ProgressView().controlSize(.small).tint(.white.opacity(0.6))
                }
            }
            .padding(.horizontal, 14).padding(.vertical, 12)
            .background(.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 14))
            Spacer(minLength: 40)
        }
    }

    private var inputBar: some View {
        HStack(spacing: 10) {
            TextField("Ask your DJ…", text: $model.input, axis: .vertical)
                .font(.system(size: 14)).foregroundStyle(.white).tint(PC.accent)
                .focused($inputFocused).lineLimit(1...4)
                .padding(.horizontal, 14).padding(.vertical, 10)
                .background(.white.opacity(0.06), in: Capsule())
                .overlay(Capsule().stroke(inputFocused ? PC.accent.opacity(0.5) : .white.opacity(0.1), lineWidth: 1))
                .onSubmit { doSend() }
            Button { doSend() } label: {
                Image(systemName: "arrow.up").font(.system(size: 16, weight: .bold)).foregroundStyle(.white)
                    .frame(width: 40, height: 40)
                    .background(model.input.trimmingCharacters(in: .whitespaces).isEmpty || model.isLoading ? PC.accent.opacity(0.3) : PC.accent, in: Circle())
            }
            .disabled(model.input.trimmingCharacters(in: .whitespaces).isEmpty || model.isLoading || model.selectedProviderId == nil)
        }
        .padding(.horizontal, 14).padding(.top, 8).padding(.bottom, 10)
    }
}

// MARK: - Card parsing + view (mirrors Android parseMessage / ChatCardRow)

enum ChatCard: Equatable {
    case track(title: String, artist: String, album: String)
    case album(title: String, artist: String)
    case artist(name: String)
}

private enum ChatSegment { case text(String); case card(ChatCard) }

/// Lifting cards out of inline prose ("…tracks: {{card}}, {{card}}.") leaves the
/// joining punctuation as standalone text segments. In the vertical card layout a
/// lone "," / "." / "and" renders as an orphaned line — drop those; otherwise
/// return the trimmed text.
private func cleanSegment(_ raw: String) -> String? {
    var t = raw.trimmingCharacters(in: .whitespacesAndNewlines)
    if t.isEmpty { return nil }
    // Drop a segment that's ONLY separator/markdown noise — a lone "." / "," or a
    // "* " / "- " / "• " bullet left when a card was lifted out of a list.
    let orphan = CharacterSet(charactersIn: " ,.;:!?&·•*_#>~`-–—\n\t")
    if t.unicodeScalars.allSatisfy({ orphan.contains($0) }) { return nil }
    if ["and", "&", "plus", "with"].contains(t.lowercased()) { return nil }
    // Strip leading SENTENCE punctuation from a card continuation (". It shares…")
    // — but NOT markdown markers (* _ `), which carry bold/italic for the render.
    let lead = CharacterSet(charactersIn: " ,.;:!?\n\t")
    while let f = t.unicodeScalars.first, lead.contains(f) { t.removeFirst() }
    t = t.trimmingCharacters(in: .whitespacesAndNewlines)
    return t.isEmpty ? nil : t
}

/// Render assistant text as inline markdown (bold/italic/links/code) — falls back
/// to plain text if the run isn't valid markdown.
private func markdownText(_ s: String) -> Text {
    if let a = try? AttributedString(markdown: s, options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace)) {
        return Text(a)
    }
    return Text(s)
}

/// Split assistant text into plain-text + `{{type|…}}` card segments.
private func parseSegments(_ text: String) -> [ChatSegment] {
    guard let regex = try? NSRegularExpression(pattern: "\\{\\{(track|album|artist|playlist)\\|([^}]*)\\}\\}") else {
        return [.text(text)]
    }
    let ns = text as NSString
    var out: [ChatSegment] = []
    var cursor = 0
    regex.enumerateMatches(in: text, range: NSRange(location: 0, length: ns.length)) { m, _, _ in
        guard let m = m else { return }
        if m.range.location > cursor {
            out.append(.text(ns.substring(with: NSRange(location: cursor, length: m.range.location - cursor))))
        }
        let type = ns.substring(with: m.range(at: 1))
        let parts = ns.substring(with: m.range(at: 2)).components(separatedBy: "|")
        switch type {
        case "track": out.append(.card(.track(title: parts.first ?? "", artist: parts.count > 1 ? parts[1] : "", album: parts.count > 2 ? parts[2] : "")))
        case "album": out.append(.card(.album(title: parts.first ?? "", artist: parts.count > 1 ? parts[1] : "")))
        case "artist": out.append(.card(.artist(name: parts.first ?? "")))
        default: break // playlist cards (no iOS playlist entity yet) render as nothing
        }
        cursor = m.range.location + m.range.length
    }
    if cursor < ns.length { out.append(.text(ns.substring(from: cursor))) }
    return out.isEmpty ? [.text(text)] : out
}

private struct ChatCardView: View {
    let card: ChatCard
    private let container = IosContainer.companion.shared
    @State private var art: String?

    var body: some View {
        Button { play() } label: {
            HStack(spacing: 10) {
                pcCover(art, seed: title, size: 40, radius: isArtist ? 20 : 4)
                VStack(alignment: .leading, spacing: 1) {
                    Text(title).font(.system(size: 13, weight: .medium)).foregroundStyle(.white).lineLimit(1)
                    if !subtitle.isEmpty {
                        Text(subtitle).font(.system(size: 12)).foregroundStyle(.white.opacity(0.55)).lineLimit(1)
                    }
                }
                Spacer(minLength: 4)
                Text(badge).font(.system(size: 9, weight: .semibold)).foregroundStyle(.white.opacity(0.4))
                Image(systemName: "play.circle.fill").font(.system(size: 22)).foregroundStyle(PC.accentSoft)
            }
            .padding(8)
            .background(.white.opacity(0.05), in: RoundedRectangle(cornerRadius: 8))
        }
        .buttonStyle(.plain)
        .task {
            art = switch card {
            case .track(let t, let a, let al): try? await container.chatTrackArtwork(title: t, artist: a, album: al)
            case .album(let t, let a): try? await container.chatAlbumArtwork(title: t, artist: a)
            case .artist(let n): try? await container.chatArtistImage(name: n)
            }
        }
    }

    private var isArtist: Bool { if case .artist = card { return true }; return false }
    private var title: String {
        switch card { case .track(let t, _, _): return t; case .album(let t, _): return t; case .artist(let n): return n }
    }
    private var subtitle: String {
        switch card {
        case .track(_, let a, let al): return al.isEmpty ? a : "\(a) • \(al)"
        case .album(_, let a): return a
        case .artist: return ""
        }
    }
    private var badge: String {
        switch card { case .track: return "TRACK"; case .album: return "ALBUM"; case .artist: return "ARTIST" }
    }

    private func play() {
        Task {
            switch card {
            case .track(let t, let a, let al): try? await container.chatPlayTrack(title: t, artist: a, album: al.isEmpty ? nil : al)
            case .album(let t, let a): try? await container.chatPlayAlbum(title: t, artist: a)
            case .artist(let n): try? await container.chatPlayArtist(name: n)
            }
        }
    }
}
