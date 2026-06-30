import SwiftUI

/// One provider sync card (port of Android `SettingsScreen.kt` Spotify/AM/LB
/// cards). A section header names the provider; the card shows an enabled/disabled
/// title + "what's synced" summary + a Switch, then (when enabled) the last-synced
/// line, a divider, and a "Configure what syncs" button. The whole card is only
/// rendered when the provider is connected (parent gates visibility), so the
/// toggle is always actionable.
struct SyncCardView: View {
    let providerName: String        // section header, e.g. "Spotify Sync"
    let enabled: Bool
    let busy: Bool                  // AM acquiring the MUT
    let summary: String            // "Liked Songs · Albums · …" (when enabled)
    let defaultDesc: String
    let note: String?              // AM API-limitation note
    let lastSyncedText: String
    let onToggle: (Bool) -> Void
    let onConfigure: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(providerName.uppercased())
                .font(.system(size: 11, weight: .semibold)).tracking(1.4)
                .foregroundStyle(PC.fg3)
                .padding(.horizontal, 20).padding(.bottom, 8)

            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .top, spacing: 12) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(enabled ? "Syncing enabled" : "Syncing disabled")
                            .font(.system(size: 16, weight: .medium)).foregroundStyle(PC.fg1)
                        Text(subtitle)
                            .font(.system(size: 12)).foregroundStyle(PC.fg2)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Spacer(minLength: 0)
                    if busy {
                        ProgressView().controlSize(.small).tint(PC.accent)
                    } else {
                        Toggle("", isOn: Binding(get: { enabled }, set: { onToggle($0) }))
                            .labelsHidden().tint(PC.accent)
                    }
                }

                Text("Last synced: \(lastSyncedText)")
                    .font(.system(size: 12)).foregroundStyle(PC.fg3)
                    .padding(.top, 12)

                if enabled {
                    Divider().background(PC.cardBorder).padding(.vertical, 14)
                    Button { onConfigure() } label: {
                        HStack {
                            Text("Configure what syncs")
                                .font(.system(size: 14, weight: .medium)).foregroundStyle(PC.accent)
                            Spacer()
                            Image(systemName: "chevron.right")
                                .font(.system(size: 12, weight: .semibold)).foregroundStyle(PC.accent)
                        }
                    }
                    .buttonStyle(.plain)

                    if let note {
                        Text(note)
                            .font(.system(size: 11)).foregroundStyle(PC.fg3)
                            .padding(.top, 12)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
            .padding(16)
            .background(RoundedRectangle(cornerRadius: 12).fill(PC.cardBg))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(PC.cardBorder, lineWidth: 1))
            .padding(.horizontal, 16)
        }
    }

    private var subtitle: String {
        if enabled && !summary.isEmpty { return "Syncing: \(summary)" }
        return defaultDesc
    }
}
