import SwiftUI
import Shared

// MARK: - Search ViewModel (phase 5.1)
//
// Second production screen. Uses the SHARED MusicBrainz client through
// `IosContainer` — the first screen wired to the production Ktor
// HttpClient (the one with User-Agent injection + shared plugins, not
// the smoke-test's minimal client). Debounced search-as-you-type;
// results come back as flat Swift DTOs the container projects.

@MainActor
@Observable
final class SearchViewModel {

    private let container = IosContainer.companion.shared

    var query: String = ""
    var artists: [IosSearchArtist] = []
    var releases: [IosSearchRelease] = []
    var isSearching = false
    var hasSearched = false

    private var searchTask: Task<Void, Never>?

    /// Debounced search. Cancels the in-flight request when the query
    /// changes so we don't render stale results, waits 350ms after the
    /// last keystroke before hitting the network (MusicBrainz is
    /// 1 req/sec; no point firing on every character).
    func onQueryChange(_ newValue: String) {
        query = newValue
        searchTask?.cancel()
        let trimmed = newValue.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else {
            artists = []
            releases = []
            hasSearched = false
            isSearching = false
            return
        }
        searchTask = Task {
            try? await Task.sleep(nanoseconds: 350_000_000)
            if Task.isCancelled { return }
            await runSearch(trimmed)
        }
    }

    private func runSearch(_ query: String) async {
        isSearching = true
        let results = try? await container.search(query: query, limit: 8)
        if Task.isCancelled { return }
        artists = results?.artists ?? []
        releases = results?.releases ?? []
        hasSearched = true
        isSearching = false
    }
}

// MARK: - Search screen

struct SearchView: View {
    @State private var model = SearchViewModel()

    var body: some View {
        NavigationStack {
            List {
                if !model.artists.isEmpty {
                    Section("Artists") {
                        ForEach(model.artists, id: \.id) { artist in
                            VStack(alignment: .leading, spacing: 2) {
                                Text(artist.name)
                                    .font(.body)
                                if let d = artist.disambiguation, !d.isEmpty {
                                    Text(d)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }

                if !model.releases.isEmpty {
                    Section("Releases") {
                        ForEach(model.releases, id: \.id) { release in
                            VStack(alignment: .leading, spacing: 2) {
                                Text(release.title)
                                    .font(.body)
                                HStack(spacing: 4) {
                                    Text(release.artist)
                                    if let y = release.year {
                                        Text("· \(y)")
                                    }
                                }
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            }
                        }
                    }
                }

                if model.hasSearched
                    && model.artists.isEmpty
                    && model.releases.isEmpty
                    && !model.isSearching {
                    ContentUnavailableView.search(text: model.query)
                }
            }
            .navigationTitle("Search")
            .overlay {
                if model.isSearching && model.artists.isEmpty && model.releases.isEmpty {
                    ProgressView()
                }
            }
            .searchable(
                text: Binding(
                    get: { model.query },
                    set: { model.onQueryChange($0) }
                ),
                prompt: "Artists & releases (MusicBrainz)"
            )
        }
    }
}
