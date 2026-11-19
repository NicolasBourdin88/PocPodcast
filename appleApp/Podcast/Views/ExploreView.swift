import SwiftUI
import PodcastKit

/// Explore: the server's directory search, and a subscription by feed address for the feeds the
/// directories do not know.
struct ExploreView: View {
    @Environment(AppModel.self) private var model
    @State private var query = ""
    @State private var searched = ""
    @State private var results: [SearchResult]?
    @State private var searching = false
    @State private var unavailable = false
    @State private var subscribing: Set<String> = []
    @State private var addingFeed = false
    @State private var feedUrl = ""

    var body: some View {
        let strings = model.strings
        List {
            if let results {
                ForEach(results, id: \.feedUrl) { result in
                    HStack(spacing: 12) {
                        ArtworkView(podcast: result.asPodcast, size: 56)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(result.source).font(.caption).foregroundStyle(.secondary)
                            Text(result.title).font(.headline).lineLimit(2)
                            Text(result.author).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                        }
                        Spacer(minLength: 0)
                        if result.subscribed {
                            Label(strings.subscribed, systemImage: "checkmark").labelStyle(.iconOnly).foregroundStyle(Color.brand)
                                .accessibilityLabel(strings.subscribed)
                        } else if subscribing.contains(result.feedUrl) {
                            ProgressView()
                        } else {
                            Button(strings.subscribe) { Task { await subscribe(result.feedUrl) } }
                                .buttonStyle(.glass)
                        }
                    }
                }
            }
        }
        .overlay {
            if searching {
                ProgressView()
            } else if unavailable {
                EmptyState(text: strings.searchUnavailable, systemImage: "magnifyingglass")
            } else if results?.isEmpty == true {
                ContentUnavailableView.search(text: searched)
            } else if results == nil {
                EmptyState(text: strings.searchPlaceholder, systemImage: "magnifyingglass")
            }
        }
        .navigationTitle(strings.destination(destination: .explore))
        .searchable(text: $query, prompt: strings.searchPlaceholder)
        .onSubmit(of: .search) { Task { await search() } }
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button { addingFeed = true } label: { Label(strings.addFeedTitle, systemImage: "plus") }
            }
        }
        .alert(strings.addFeedTitle, isPresented: $addingFeed) {
            TextField(strings.feedUrl, text: $feedUrl)
                .autocorrectionDisabled()
                #if os(iOS)
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
                #endif
            Button(strings.cancel, role: .cancel) { feedUrl = "" }
            Button(strings.subscribe) {
                let address = feedUrl.trimmingCharacters(in: .whitespacesAndNewlines)
                feedUrl = ""
                Task { await subscribe(address) }
            }
        }
    }

    private func search() async {
        let text = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard text.count >= 2 else { return }
        searching = true
        defer { searching = false }
        do {
            let found = try await model.client.search(query: text)
            searched = text
            unavailable = found == nil
            results = found
        } catch {
            model.notice = model.message(error)
        }
    }

    private func subscribe(_ feedUrl: String) async {
        guard !feedUrl.isEmpty else { return }
        subscribing.insert(feedUrl)
        defer { subscribing.remove(feedUrl) }
        do {
            let item = try await model.client.subscribe(feedUrl: feedUrl)
            results = results?.map { $0.feedUrl == feedUrl ? $0.markedSubscribed : $0 }
            model.notice = model.strings.subscribedTo(title: item.podcast.title)
            model.changed()
        } catch {
            model.notice = model.message(error)
        }
    }
}

private extension SearchResult {
    /// Until subscribed, the placeholder artwork is keyed by the feed.
    var asPodcast: Podcast {
        Podcast(id: feedUrl, title: title, author: author, feedUrl: feedUrl, artworkUrl: artworkUrl, description: description_,
                websiteUrl: nil, language: nil, refreshStatus: .ok, refreshedAt: nil)
    }

    var markedSubscribed: SearchResult {
        SearchResult(title: title, author: author, feedUrl: feedUrl, artworkUrl: artworkUrl, description: description_, source: source, subscribed: true)
    }
}
