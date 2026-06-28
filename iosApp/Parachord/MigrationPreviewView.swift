import SwiftUI
import UIKit
import Shared

// Swift-side Identifiable view models (the Ios* DTOs are flattened into these so
// SwiftUI ForEach has stable ids). #289 brick #5C — iOS.
struct MPTrack: Identifiable { let id = UUID(); let label: String }
struct MPProvider: Identifiable { let id = UUID(); let providerId: String; let removes: [MPTrack]; let adds: [MPTrack] }
struct MPPlaylist: Identifiable { let id = UUID(); let name: String; let providers: [MPProvider] }
struct MPProtected: Identifiable { let id = UUID(); let name: String; let reason: String }
struct MPSummary {
    let changed: [MPPlaylist]
    let protectedList: [MPProtected]
    let totalAdds: Int
    let totalRemoves: Int
    let noopCount: Int
    let hasChanges: Bool
}

@MainActor
@Observable
final class MigrationPreviewModel {
    enum Phase { case loading, error(String), loaded(MPSummary, recomputed: Bool) }
    private let container = IosContainer.companion.shared
    var phase: Phase = .loading

    private func toSwift(_ s: IosMigrationSummary) -> MPSummary {
        let changed = s.changed.map { pl in
            MPPlaylist(name: pl.displayName, providers: pl.providers.map { p in
                MPProvider(
                    providerId: p.providerId,
                    removes: p.removes.map { MPTrack(label: "\($0.artist) — \($0.title)") },
                    adds: p.adds.map { MPTrack(label: "\($0.artist) — \($0.title)") }
                )
            })
        }
        return MPSummary(
            changed: changed,
            protectedList: s.protectedList.map { MPProtected(name: $0.displayName, reason: $0.reason) },
            totalAdds: Int(s.totalAdds), totalRemoves: Int(s.totalRemoves),
            noopCount: Int(s.noopCount), hasChanges: s.hasChanges
        )
    }

    func load() {
        phase = .loading
        Task {
            do { phase = .loaded(toSwift(try await container.previewMigration()), recomputed: false) }
            catch { phase = .error(error.localizedDescription) }
        }
    }

    /// Accept: recompute, flip to `new` only if unchanged; else re-show the fresh
    /// plan. Calls [onFlipped] (which dismisses) when the cutover actually happens.
    func accept(onFlipped: @escaping () -> Void) {
        phase = .loading
        Task {
            do {
                let r = try await container.acceptMigration()
                if r.flipped { onFlipped() } else { phase = .loaded(toSwift(r.summary), recomputed: true) }
            } catch { phase = .error(error.localizedDescription) }
        }
    }

    func report() {
        let r = container.migrationReport()
        UIPasteboard.general.string = r.body
        if let url = URL(string: r.githubUrl) { UIApplication.shared.open(url) }
    }
}

/// Preview → approve / report / cancel for the new-sync cutover (mirrors the
/// Android Compose dialog). Nothing is armed until Accept.
struct MigrationPreviewView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var model = MigrationPreviewModel()

    var body: some View {
        NavigationStack {
            ScrollView { content.padding() }
                .navigationTitle("Use new sync")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                }
        }
        .onAppear { model.load() }
    }

    @ViewBuilder private var content: some View {
        switch model.phase {
        case .loading:
            ProgressView("Computing what the switch would change…")
                .frame(maxWidth: .infinity).padding(.top, 40)
        case .error(let msg):
            Text("Couldn't compute the preview: \(msg)").foregroundStyle(.secondary)
        case .loaded(let summary, let recomputed):
            loadedBody(summary, recomputed)
        }
    }

    @ViewBuilder private func loadedBody(_ summary: MPSummary, _ recomputed: Bool) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            if recomputed {
                Text("The plan changed since you last looked — review again before accepting.")
                    .font(.footnote).foregroundStyle(.red)
            }
            // Cross-client coexistence nudge (#289).
            Text("Using Parachord on another device (desktop, etc.)? Switch it to new sync too — running the old and new engines on the same playlists can churn them until all your devices match.")
                .font(.footnote).foregroundStyle(.secondary)

            if !summary.hasChanges && summary.protectedList.isEmpty {
                if summary.noopCount > 0 {
                    Text("Reviewed \(summary.noopCount) playlist\(summary.noopCount == 1 ? "" : "s") — all already in sync. Switching is a zero-risk cutover.")
                } else {
                    Text("No changes needed — your playlists are already in sync. Switching is a zero-risk cutover.")
                }
            } else {
                Text("Switching to the new sync engine would make these changes:").font(.subheadline)
                ForEach(summary.changed) { pl in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(pl.name).fontWeight(.semibold)
                        ForEach(pl.providers) { p in
                            Text(p.providerId).font(.caption).foregroundStyle(.secondary).padding(.leading, 8)
                            ForEach(p.removes) { t in
                                Text("− \(t.label)").font(.footnote).foregroundStyle(.red).padding(.leading, 16)
                            }
                            ForEach(p.adds) { t in
                                Text("+ \(t.label)").font(.footnote).padding(.leading, 16)
                            }
                        }
                    }
                }
                if !summary.protectedList.isEmpty {
                    Text("Protected from a destructive change:").fontWeight(.semibold)
                    ForEach(summary.protectedList) { p in
                        let why = p.reason == "total-wipe" ? "would empty the playlist" : "an unexpectedly large drop"
                        Text("• \(p.name) (\(why))").font(.footnote)
                    }
                }
                let tail = summary.noopCount > 0 ? " · \(summary.noopCount) unchanged" : ""
                Text("\(summary.totalAdds) add, \(summary.totalRemoves) remove across \(summary.changed.count) playlist(s)\(tail)")
                    .font(.footnote).foregroundStyle(.secondary)
            }

            HStack {
                Button(recomputed ? "Review & accept" : "Accept changes") { model.accept { dismiss() } }
                    .buttonStyle(.borderedProminent)
                Spacer()
                if summary.hasChanges || !summary.protectedList.isEmpty {
                    Button("Report a problem") { model.report() }
                }
            }
            .padding(.top, 8)
        }
    }
}
