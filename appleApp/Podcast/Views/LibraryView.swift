import SwiftUI
import UniformTypeIdentifiers
import PodcastKit

/// Library: the subscriptions, or the search of their episodes (ADR 0012).
struct LibraryView: View {
    @Environment(AppModel.self) private var model
    @State private var showEpisodes = false

    var body: some View {
        let strings = model.strings
        Group {
            if showEpisodes { EpisodeSearchView() } else { SubscriptionsView() }
        }
        .navigationTitle(strings.destination(destination: .library))
        .toolbar {
            ToolbarItem(placement: .principal) {
                Picker(strings.destination(destination: .library), selection: $showEpisodes) {
                    Text(strings.subscriptionsTab).tag(false)
                    Text(strings.episodesTab).tag(true)
                }
                .pickerStyle(.segmented)
                .fixedSize()
            }
        }
        .navigationDestination(for: SubscriptionItem.self) { PodcastView(item: $0) }
    }
}

/// The subscriptions, filtered as everywhere else (words and a tag), with OPML in and out.
struct SubscriptionsView: View {
    @Environment(AppModel.self) private var model
    @State private var items: [SubscriptionItem]?
    @State private var error: String?
    @State private var text = ""
    @State private var tag: String?
    @State private var importing = false
    @State private var exported: OpmlDocument?

    var body: some View {
        let strings = model.strings
        let all = items ?? []
        let shown = all.filter { $0.matches(text: text, tag: tag) }
        let tags = TagRules.shared.sorted(tags: all.flatMap { $0.subscription.tags })
        List(shown, id: \.subscription.id) { item in
            NavigationLink(value: item) {
                HStack(spacing: 12) {
                    ArtworkView(podcast: item.podcast, size: 52)
                    VStack(alignment: .leading, spacing: 2) {
                        if !item.subscription.tags.isEmpty {
                            Text(item.subscription.tags.joined(separator: " · ")).font(.caption).foregroundStyle(Color.brand)
                        }
                        Text(item.podcast.title).font(.headline).lineLimit(1)
                        Text(item.subscription.note.flatMap { $0.isEmpty ? nil : $0 } ?? item.podcast.author)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }
            }
        }
        .overlay {
            if let error {
                EmptyState(text: error, systemImage: "wifi.exclamationmark", retry: { Task { await load() } }, retryLabel: strings.retry)
            } else if items == nil {
                ProgressView()
            } else if shown.isEmpty {
                EmptyState(text: all.isEmpty ? strings.emptyLibrary : strings.noSubscriptionFound, systemImage: "square.stack")
            }
        }
        .searchable(text: $text, prompt: strings.filterLibrary)
        .toolbar {
            if !tags.isEmpty {
                ToolbarItem {
                    Menu {
                        Picker(strings.tags, selection: $tag) {
                            Text(strings.allTags).tag(String?.none)
                            ForEach(tags, id: \.self) { Text($0).tag(String?.some($0)) }
                        }
                    } label: {
                        Label(tag ?? strings.tags, systemImage: tag == nil ? "tag" : "tag.fill")
                    }
                }
            }
            ToolbarItem {
                Menu {
                    Button { importing = true } label: { Label(strings.opmlImport, systemImage: "square.and.arrow.down") }
                    Button { Task { await export() } } label: { Label(strings.opmlExport, systemImage: "square.and.arrow.up") }
                } label: {
                    Label(strings.moreActions, systemImage: "ellipsis")
                }
            }
        }
        .refreshable { await load() }
        .task(id: model.changes) { await load() }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.opml, .xml]) { result in
            if case .success(let url) = result { Task { await importOpml(url) } }
        }
        .fileExporter(isPresented: Binding(get: { exported != nil }, set: { if !$0 { exported = nil } }), document: exported,
                      contentType: .opml, defaultFilename: "podcasts.opml") { result in
            if case .success(let url) = result { model.notice = strings.opmlSaved(name: url.lastPathComponent) }
        }
    }

    private func load() async {
        do {
            items = try await model.client.subscriptionItems()
            error = nil
        } catch {
            if items == nil { self.error = model.message(error) }
        }
    }

    private func export() async {
        await model.attempt { exported = OpmlDocument(text: try await model.client.exportOpml()) }
    }

    /// The system's file picker: reading needs the file's permission while it lasts.
    private func importOpml(_ url: URL) async {
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        guard let text = try? String(contentsOf: url, encoding: .utf8) else { return }
        await model.attempt {
            let report = try await model.client.importOpml(opml: text)
            model.notice = model.strings.opmlReport(imported: report.imported, alreadySubscribed: report.alreadySubscribed, failed: Int32(report.failed.count))
            model.changed()
        }
    }
}

/// A podcast seen through its subscription: note, tags, episodes.
struct PodcastView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State var item: SubscriptionItem
    @State private var episodes: [Episode]?
    @State private var editing = false
    @State private var confirmingUnsubscribe = false

    var body: some View {
        let strings = model.strings
        let podcast = item.podcast
        List {
            Section {
                HStack(alignment: .top, spacing: 16) {
                    ArtworkView(podcast: podcast, size: 104)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(podcast.title).font(.title3.bold())
                        Text(podcast.author).foregroundStyle(.secondary)
                        if !item.subscription.tags.isEmpty {
                            Text(item.subscription.tags.joined(separator: " · ")).font(.caption).foregroundStyle(Color.brand)
                        }
                    }
                }
                if let note = item.subscription.note, !note.isEmpty {
                    Label(note, systemImage: "pencil.line")
                }
                if !podcast.description_.isEmpty {
                    Text(podcast.description_.plainText).font(.callout).foregroundStyle(.secondary).lineLimit(5)
                }
            }
            Section(strings.episodes) {
                ForEach(episodes ?? [], id: \.id) { EpisodeRow(item: EpisodeItem(episode: $0, podcast: podcast)) }
            }
        }
        .overlay { if episodes == nil { ProgressView() } }
        .navigationTitle(podcast.title)
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .toolbar {
            ToolbarItem {
                Menu {
                    Button { editing = true } label: { Label("\(strings.note) · \(strings.tags)", systemImage: "pencil") }
                    Button(role: .destructive) { confirmingUnsubscribe = true } label: { Label(strings.unsubscribe, systemImage: "minus.circle") }
                } label: {
                    Label(strings.moreActions, systemImage: "ellipsis")
                }
            }
        }
        .confirmationDialog(strings.unsubscribeConfirm(title: podcast.title), isPresented: $confirmingUnsubscribe, titleVisibility: .visible) {
            Button(strings.unsubscribe, role: .destructive) { Task { await unsubscribe() } }
        }
        .sheet(isPresented: $editing) {
            SubscriptionEditor(item: item) { item = $0 }
        }
        .refreshable { await load() }
        .task { await load() }
    }

    private func load() async {
        await model.attempt { episodes = try await model.client.podcastEpisodes(podcastId: item.podcast.id, limit: 100) }
    }

    private func unsubscribe() async {
        await model.attempt {
            try await model.client.unsubscribe(subscriptionId: item.subscription.id)
            model.notice = model.strings.unsubscribedFrom(title: item.podcast.title)
            model.changed()
            dismiss()
        }
    }
}

/// The note and the tags of a subscription, with the shared rules (TagRules).
struct SubscriptionEditor: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let item: SubscriptionItem
    let onSaved: (SubscriptionItem) -> Void
    @State private var note = ""
    @State private var tags = ""

    var body: some View {
        let strings = model.strings
        let parsed = TagRules.shared.parse(text: tags)
        let valid = TagRules.shared.valid(tags: parsed)
        NavigationStack {
            Form {
                Section(strings.note) {
                    TextField(strings.notePlaceholder, text: $note, axis: .vertical).lineLimit(2...5)
                }
                Section {
                    TextField(strings.tags, text: $tags)
                        .autocorrectionDisabled()
                } header: {
                    Text(strings.tags)
                } footer: {
                    Text(valid ? strings.tagsHint : strings.tagsInvalid(count: TagRules.shared.MAX_TAGS, length: TagRules.shared.MAX_LENGTH))
                        .foregroundStyle(valid ? Color.secondary : Color.red)
                }
            }
            .formStyle(.grouped)
            .navigationTitle(item.podcast.title)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(strings.cancel) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(strings.save) { Task { await save(parsed) } }.disabled(!valid)
                }
            }
        }
        .onAppear {
            note = item.subscription.note ?? ""
            tags = item.subscription.tags.joined(separator: ", ")
        }
    }

    private func save(_ parsed: [String]) async {
        await model.attempt {
            let text = note.trimmingCharacters(in: .whitespacesAndNewlines)
            let saved = try await model.client.updateSubscription(
                subscriptionId: item.subscription.id, note: text.isEmpty ? nil : text, autoDownload: item.subscription.autoDownload, tags: parsed
            )
            onSaved(saved)
            model.changed()
            dismiss()
        }
    }
}

/// The search of the user's episodes (ADR 0012): words in the search field, the other criteria
/// in a menu; sent a moment after the last change.
struct EpisodeSearchView: View {
    @Environment(AppModel.self) private var model
    @State private var text = ""
    @State private var status: EpisodeStatus?
    @State private var favoritesOnly = false
    @State private var minRating: Int32 = 0
    @State private var page: EpisodePage?

    private struct Criteria: Equatable {
        let text: String
        let status: EpisodeStatus?
        let favoritesOnly: Bool
        let minRating: Int32
    }

    var body: some View {
        let strings = model.strings
        List {
            if let page {
                Section(strings.episodeCount(count: page.total)) {
                    ForEach(page.items, id: \.episode.id) { EpisodeRow(item: $0) }
                }
            }
        }
        .overlay {
            if page == nil { ProgressView() } else if page?.items.isEmpty == true { EmptyState(text: strings.noEpisodeFound, systemImage: "magnifyingglass") }
        }
        .searchable(text: $text, prompt: strings.searchEpisodes)
        .toolbar {
            ToolbarItem {
                Menu {
                    Picker(strings.statusLabel, selection: $status) {
                        Text(strings.anyStatus).tag(EpisodeStatus?.none)
                        ForEach(EpisodeStatus.allCases, id: \.self) { Text(strings.statusFilter(status: $0)).tag(EpisodeStatus?.some($0)) }
                    }
                    Toggle(strings.favoritesOnly, isOn: $favoritesOnly)
                    Picker(strings.minRating, selection: $minRating) {
                        Text(strings.anyRating).tag(Int32(0))
                        ForEach(Int32(1)...Int32(5), id: \.self) { Text(strings.stars(rating: $0)).tag($0) }
                    }
                } label: {
                    Label(strings.statusLabel, systemImage: "line.3.horizontal.decrease.circle")
                }
            }
        }
        .task(id: Criteria(text: text, status: status, favoritesOnly: favoritesOnly, minRating: minRating)) {
            if page != nil { try? await Task.sleep(for: .milliseconds(300)) }
            guard !Task.isCancelled else { return }
            let query = EpisodeQuery(text: text, status: status, favoritesOnly: favoritesOnly, minRating: minRating, from: nil, to: nil, podcastId: nil, tag: nil)
            await model.attempt { page = try await model.client.searchEpisodes(query: query, limit: 100, offset: 0) }
        }
    }
}
