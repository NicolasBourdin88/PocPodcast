import SwiftUI
import PodcastKit

/// The user's playlists (ADR 0010), static or dynamic.
struct PlaylistsView: View {
    @Environment(AppModel.self) private var model
    @State private var playlists: [Playlist]?

    var body: some View {
        let strings = model.strings
        List(playlists ?? [], id: \.id) { playlist in
            NavigationLink(value: playlist) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(playlist.title).font(.headline)
                    Text("\(strings.playlistKind(kind: playlist.kind)) · \(strings.episodeCount(count: playlist.episodeCount))")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .contentListStyle()
        .overlay {
            if playlists == nil { ProgressView() } else if playlists?.isEmpty == true { EmptyState(text: strings.emptyPlaylists, systemImage: "music.note.list") }
        }
        .navigationTitle(strings.destination(destination: .playlists))
        .navigationDestination(for: Playlist.self) { PlaylistView(playlist: $0).playerBar() }
        .refreshable { await load() }
        .task(id: model.changes) { await load() }
    }

    private func load() async {
        await model.attempt { playlists = try await model.client.playlists() }
    }
}

/// A playlist's episodes, in their order; play them all, or shuffle a random one.
struct PlaylistView: View {
    @Environment(AppModel.self) private var model
    let playlist: Playlist
    @State private var details: PlaylistDetails?

    var body: some View {
        let strings = model.strings
        let episodes = details?.episodes ?? []
        List {
            if let description = playlist.description_, !description.isEmpty {
                Text(description).foregroundStyle(.secondary)
            }
            ForEach(episodes, id: \.episode.id) { EpisodeRow(item: $0) }
        }
        .contentListStyle()
        .overlay {
            if details == nil { ProgressView() } else if episodes.isEmpty { EmptyState(text: strings.emptyPlaylist, systemImage: "music.note.list") }
        }
        .navigationTitle(playlist.title)
        .toolbar {
            ToolbarItemGroup {
                Button { model.playback.playAll(episodeIds: episodes.map { $0.episode.id }) } label: {
                    Label(strings.playAll, systemImage: "play.fill")
                }
                .disabled(episodes.isEmpty)
                if playlist.rules?.order == .random {
                    Button { Task { await shuffle() } } label: { Label(strings.shuffle, systemImage: "shuffle") }
                }
            }
        }
        .refreshable { await load() }
        .task { await load() }
    }

    private func load() async {
        await model.attempt { details = try await model.client.playlist(id: playlist.id) }
    }

    private func shuffle() async {
        await model.attempt { details = try await model.client.shufflePlaylist(id: playlist.id) }
    }
}
