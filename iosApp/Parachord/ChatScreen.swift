import SwiftUI
import Shared

// MARK: - DJ Chat / Shuffleupagus (#223 / #202)
//
// iOS port of Android's ChatScreen. All orchestration (tool loop, per-provider
// history, providers, system prompt) is the SHARED AiChatService, reached through
// flat IosContainer methods. This file is just the SwiftUI surface + the
// drawing of the shared ChatContent blocks (Android draws the same).

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
    /// The providers were read and none has a key: the screen says how to set
    /// one up (Android's NoProvidersContent) instead of a disabled Send.
    var noProviderConfigured: Bool { providersLoaded && !providers.contains { $0.configured } }
    private var providersLoaded = false

    private static let known: [(String, String)] = [("chatgpt", "ChatGPT"), ("claude", "Claude"), ("gemini", "Gemini")]

    func start() async {
        // Re-read while none is configured, so a key added in Settings shows up.
        if providers.contains(where: { $0.configured }) { return }
        var list: [Provider] = []
        for (id, name) in Self.known {
            let configured = (try? await container.chatProviderConfigured(providerId: id))?.boolValue ?? false
            list.append(Provider(id: id, name: name, configured: configured))
        }
        providers = list
        providersLoaded = true
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

    /// Home's Ask: puts the queued in-app prompt in the input when it can be sent now
    /// (shared `ChatPrompt.shouldSend`); true when the caller should send it. Checked
    /// once the provider's messages are loaded (`start()` awaits `select`), so the
    /// load can't replace the shown prompt, and again after each reply.
    func takeQueuedInAppPrompt() -> Bool {
        let pending = container.pendingInAppChatPrompt()
        guard ChatPrompt.shared.shouldSend(pending: pending, chatOpen: true,
                                           providerId: selectedProviderId, loading: isLoading),
              let prompt = container.consumeInAppChatPrompt() else { return false }
        input = prompt
        return true
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
    /// Deep-link prefill (#256): pre-fills the input bar (not auto-sent) so the
    /// user can review/edit the seeded Shuffleupagus prompt before sending.
    var seedPrompt: String? = nil
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
    /// Sends the input; once the reply lands, sends an Ask prompt queued while it
    /// was loading (desktop re-checks its pending prompt whenever loading ends).
    private func doSend() {
        pushSnapshot()
        Task {
            await model.send()
            if model.takeQueuedInAppPrompt() { doSend() }
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            Divider().overlay(Color.white.opacity(0.08))
            messageList
            inputBar
        }
        .background(PC.Player.bg.ignoresSafeArea())
        .preferredColorScheme(.dark)
        .task {
            await model.start()
            // Home's Ask (in-app, sent at once) before a link's prefill (sent by the user).
            if model.takeQueuedInAppPrompt() { doSend() }
            else if let p = seedPrompt, !p.isEmpty, model.input.isEmpty { model.input = p }
        }
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
                    if model.noProviderConfigured {
                        noProvidersState.padding(.top, 80)
                    } else if model.messages.isEmpty && !model.isLoading {
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

    /// Android's NoProvidersContent.
    private var noProvidersState: some View {
        VStack(spacing: 10) {
            Image(systemName: "info.circle").font(.system(size: 40)).foregroundStyle(.white.opacity(0.35))
            Text(UiMessages.shared.NO_AI_PROVIDERS).font(.system(size: 15)).foregroundStyle(.white.opacity(0.6))
            Text(UiMessages.shared.NO_AI_PROVIDERS_HINT)
                .font(.system(size: 14)).foregroundStyle(.white.opacity(0.6)).multilineTextAlignment(.center)
                .padding(.horizontal, 32)
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
                .background(PC.fillAccent, in: .rect(topLeadingRadius: 16, bottomLeadingRadius: 16, bottomTrailingRadius: 4, topTrailingRadius: 16))
        }
    }

    private func assistantBubble(_ text: String) -> some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: "sparkles").font(.system(size: 13)).foregroundStyle(PC.accentSoft).padding(.top, 10)
            VStack(alignment: .leading, spacing: 6) {
                ChatBlocksView(text: text)
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
                    .background(model.input.trimmingCharacters(in: .whitespaces).isEmpty || model.isLoading ? PC.fillAccent.opacity(0.3) : PC.fillAccent, in: Circle())
            }
            .disabled(model.input.trimmingCharacters(in: .whitespaces).isEmpty || model.isLoading || model.selectedProviderId == nil)
        }
        .padding(.horizontal, 14).padding(.top, 8).padding(.bottom, 10)
    }
}

// MARK: - Message layout + card view
//
// The parsing and the punctuation rules around cards are the shared
// `ChatContent` (desktop's renderChatContent, Android draws the same blocks);
// this file only draws them.

/// A Shuffleupagus message: the shared `ChatContent.blocks`, drawn.
private struct ChatBlocksView: View {
    let blocks: [ChatBlock]

    init(text: String) {
        // Messages saved before replies were converted still get desktop's
        // plain-text fallback (it leaves a message that has cards alone).
        let content = ChatContent.shared
        blocks = content.blocks(content: content.convertPlainTextToCards(content: text))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(blocks.enumerated()), id: \.offset) { index, block in
                blockView(block, first: index == 0)
            }
        }
    }

    @ViewBuilder
    private func blockView(_ block: ChatBlock, first: Bool) -> some View {
        if let h = block as? ChatBlock.Header {
            let size: CGFloat = h.level == 1 ? 18 : h.level == 2 ? 16 : h.level == 3 ? 14 : 13
            Text(h.text).font(.system(size: size, weight: .semibold)).foregroundStyle(.white)
                .padding(.top, first ? 0 : 12).padding(.bottom, 4)
        } else if let c = block as? ChatBlock.CardLine {
            ChatCardView(card: c.card).padding(.vertical, 4)
        } else if let item = block as? ChatBlock.ListItem {
            // A card item centres its number on the card; a text item keeps
            // the line's top alignment (desktop).
            HStack(alignment: item.isCard ? .center : .top, spacing: 8) {
                Text("\(item.number).").font(.system(size: 14)).foregroundStyle(.white.opacity(0.45))
                    .frame(minWidth: 20, alignment: .leading)
                ChatInlinesView(inlines: item.inlines)
            }
            .padding(.top, first ? 0 : (item.isCard ? 4 : 12))
        } else if block is ChatBlock.Blank {
            Color.clear.frame(height: 8)
        } else if let line = block as? ChatBlock.Line {
            ChatInlinesView(inlines: line.inlines)
        }
    }
}

/// One line's runs: text, links, bold, italic and code flow together; a card
/// breaks the line and is drawn as its own row.
private struct ChatInlinesView: View {
    let inlines: [ChatInline]

    private enum Piece { case text(AttributedString); case card(ChatCardRef) }

    private var pieces: [Piece] {
        var out: [Piece] = []
        var run = AttributedString()
        func flush() {
            let trimmed = String(run.characters).trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty { out.append(.text(Self.trim(run))) }
            run = AttributedString()
        }
        for inline in inlines {
            if let card = inline as? ChatInline.Card {
                flush(); out.append(.card(card.card)); continue
            }
            var piece: AttributedString
            if let t = inline as? ChatInline.Text {
                piece = AttributedString(t.text)
            } else if let b = inline as? ChatInline.Bold {
                piece = AttributedString(b.text); piece.font = .system(size: 14, weight: .bold)
            } else if let i = inline as? ChatInline.Italic {
                piece = AttributedString(i.text); piece.font = .system(size: 14).italic()
            } else if let c = inline as? ChatInline.Code {
                piece = AttributedString(c.text); piece.font = .system(size: 13, design: .monospaced)
                piece.backgroundColor = .white.opacity(0.1)
            } else if let l = inline as? ChatInline.Link {
                piece = AttributedString(l.text)
                if let url = URL(string: l.url) { piece.link = url }
                piece.foregroundColor = PC.accentSoft
                piece.underlineStyle = .single
            } else {
                continue
            }
            run += piece
        }
        flush()
        return out
    }

    /// [s] without leading and trailing whitespace, attributes kept.
    private static func trim(_ s: AttributedString) -> AttributedString {
        var s = s
        while let f = s.characters.first, f.isWhitespace { s.characters.removeFirst() }
        while let l = s.characters.last, l.isWhitespace { s.characters.removeLast() }
        return s
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(pieces.enumerated()), id: \.offset) { _, piece in
                switch piece {
                case .text(let a):
                    Text(a).font(.system(size: 14)).foregroundStyle(Color(white: 0.9))
                        .frame(maxWidth: .infinity, alignment: .leading)
                case .card(let c):
                    ChatCardView(card: c).padding(.vertical, 4)
                }
            }
        }
    }
}

private struct ChatCardView: View {
    let card: ChatCardRef
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
                if !isPlaylist {
                    Image(systemName: "play.circle.fill").font(.system(size: 22)).foregroundStyle(PC.accentSoft)
                }
            }
            .padding(8)
            .background(.white.opacity(0.05), in: RoundedRectangle(cornerRadius: 8))
        }
        .buttonStyle(.plain)
        .task {
            if let t = card as? ChatCardRef.Track {
                art = try? await container.chatTrackArtwork(title: t.title, artist: t.artist, album: t.album)
            } else if let a = card as? ChatCardRef.Album {
                art = try? await container.chatAlbumArtwork(title: a.title, artist: a.artist)
            } else if let a = card as? ChatCardRef.Artist {
                art = try? await container.chatArtistImage(name: a.name)
            }
        }
    }

    private var isArtist: Bool { card is ChatCardRef.Artist }
    private var isPlaylist: Bool { card is ChatCardRef.Playlist }
    private var title: String {
        if let t = card as? ChatCardRef.Track { return t.title }
        if let a = card as? ChatCardRef.Album { return a.title }
        if let a = card as? ChatCardRef.Artist { return a.name }
        if let p = card as? ChatCardRef.Playlist { return p.name }
        return ""
    }
    private var subtitle: String {
        if let t = card as? ChatCardRef.Track { return t.album.isEmpty ? t.artist : "\(t.artist) • \(t.album)" }
        if let a = card as? ChatCardRef.Album { return a.artist }
        if let p = card as? ChatCardRef.Playlist {
            if let n = Int32(p.trackCount) { return "Playlist · " + FormattersKt.countOf(n: n, singular: "track", plural: nil) }
            return "Playlist"
        }
        return ""
    }
    private var badge: String {
        if card is ChatCardRef.Track { return "TRACK" }
        if card is ChatCardRef.Album { return "ALBUM" }
        if card is ChatCardRef.Artist { return "ARTIST" }
        return "PLAYLIST"
    }

    private func play() {
        Task {
            if let t = card as? ChatCardRef.Track {
                try? await container.chatPlayTrack(title: t.title, artist: t.artist, album: t.album.isEmpty ? nil : t.album)
            } else if let a = card as? ChatCardRef.Album {
                try? await container.chatPlayAlbum(title: a.title, artist: a.artist)
            } else if let a = card as? ChatCardRef.Artist {
                try? await container.chatPlayArtist(name: a.name)
            }
            // A playlist card opens nothing yet on iOS (no saved-playlist route from the chat).
        }
    }
}
