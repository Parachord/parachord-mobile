import SwiftUI

/// Sync status header (port of Android `SettingsScreen.kt` status card):
/// while syncing → spinner + the staged progress label; idle → "Last synced …";
/// a full-width "Sync now" button disabled while syncing AND when no service is
/// enabled.
struct SyncProgressView: View {
    @Bindable var model: SyncViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            if model.syncing {
                HStack(spacing: 10) {
                    ProgressView().controlSize(.small).tint(PC.accent)
                    Text(model.progressLabel ?? "Syncing…")
                        .font(.system(size: 14)).foregroundStyle(PC.fg1)
                        .lineLimit(2)
                }
            } else {
                Text("Last synced: \(model.lastSyncedText)")
                    .font(.system(size: 14)).foregroundStyle(PC.fg2)
            }

            Button {
                Task { await model.syncNow() }
            } label: {
                Text(model.syncing ? "Syncing…" : "Sync now")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 11)
                    .background(RoundedRectangle(cornerRadius: 8).fill(syncEnabled ? PC.accent : PC.fg3))
            }
            .buttonStyle(.plain)
            .disabled(!syncEnabled)
        }
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 12).fill(PC.cardBg))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(PC.cardBorder, lineWidth: 1))
        .padding(.horizontal, 16)
    }

    /// "Sync now" is enabled only when not already syncing AND at least one
    /// service is enabled (Android: `!isSyncing && (anyProviderEnabled)`).
    private var syncEnabled: Bool { !model.syncing && model.anyOn }
}
