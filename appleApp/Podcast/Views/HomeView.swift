import SwiftUI
import PodcastKit

/// Home: what to resume, then the new episodes, as every client shows them (LoadHomeState).
struct HomeView: View {
    @Environment(AppModel.self) private var model
    @State private var home: HomeState?
    @State private var error: String?

    var body: some View {
        let strings = model.strings
        List {
            if let home {
                if !home.resume.isEmpty {
                    Section(strings.resumeListening) {
                        ForEach(home.resume, id: \.episode.id) { EpisodeRow(item: $0) }
                    }
                }
                Section(strings.latestEpisodes) {
                    ForEach(home.latest, id: \.episode.id) { EpisodeRow(item: $0) }
                }
            }
        }
        .overlay {
            if let error {
                EmptyState(text: error, systemImage: "wifi.exclamationmark", retry: { Task { await load() } }, retryLabel: strings.retry)
            } else if home == nil {
                ProgressView()
            } else if home?.latest.isEmpty == true && home?.resume.isEmpty == true {
                EmptyState(text: strings.noEpisodes, systemImage: "headphones")
            }
        }
        .navigationTitle(strings.destination(destination: .home))
        .refreshable { await load() }
        .task(id: model.changes) { await load() }
    }

    private func load() async {
        do {
            let state = try await model.sdk.home(client: model.client)
            home = state
            error = nil
            model.resumeItem = state.resume.first
        } catch {
            if home == nil { self.error = model.message(error) }
        }
    }
}
