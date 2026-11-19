import SwiftUI
import UniformTypeIdentifiers
import PodcastKit

extension Color {
    /// 0xRRGGBB, as the shared design tokens give it.
    init(rgb: Int32) {
        let value = Int(rgb)
        self.init(red: Double((value >> 16) & 0xFF) / 255, green: Double((value >> 8) & 0xFF) / 255, blue: Double(value & 0xFF) / 255)
    }

    /// The app's color (DesignTokens), for the controls.
    static let brand = Color(rgb: DesignTokens.shared.light.primary)
}

/// The podcast's artwork; until it loads (or without one), its initials on its color.
struct ArtworkView: View {
    let podcast: Podcast
    let size: CGFloat

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: size * 0.2, style: .continuous)
        ZStack {
            shape.fill(Color(rgb: podcast.artworkColor))
            Text(podcast.initials)
                .font(.system(size: size * 0.32, weight: .bold, design: .rounded))
                .foregroundStyle(.white)
            if let url = podcast.artworkUrl.flatMap(URL.init(string:)) {
                AsyncImage(url: url) { image in
                    image.resizable().scaledToFill()
                } placeholder: {
                    Color.clear
                }
            }
        }
        .frame(width: size, height: size)
        .clipShape(shape)
        .accessibilityHidden(true)
    }
}

/// A profile picture (ADR 0013): its emoji on its color.
struct AvatarView: View {
    let avatar: Avatar
    var size: CGFloat = 40

    var body: some View {
        Text(avatar.emoji)
            .font(.system(size: size * 0.58))
            .frame(width: size, height: size)
            .background(Color(rgb: avatar.color), in: RoundedRectangle(cornerRadius: size * 0.24, style: .continuous))
    }
}

/// An episode in a list: what it is, where it stands, and its actions — a button to play it,
/// swipes and a context menu for the rest.
struct EpisodeRow: View {
    @Environment(AppModel.self) private var model
    let item: EpisodeItem

    var body: some View {
        let strings = model.strings
        let episode = item.episode
        let details = [strings.duration(seconds: episode.durationSeconds), strings.status(episode: episode)] + strings.marks(episode: episode)
        HStack(spacing: 12) {
            ArtworkView(podcast: item.podcast, size: 56)
            VStack(alignment: .leading, spacing: 3) {
                Text("\(item.podcast.title) · \(model.sdk.publishedOn(episode: episode))")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                Text(episode.title)
                    .font(.headline)
                    .lineLimit(2)
                Text(details.joined(separator: " · "))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                if episode.progress > 0 && episode.progress < 1 {
                    ProgressView(value: Double(episode.progress)).tint(Color.brand)
                }
            }
            Spacer(minLength: 0)
            Button {
                model.togglePlay(item)
            } label: {
                Image(systemName: model.isPlaying(item) ? "pause.fill" : "play.fill")
                    .frame(width: 22, height: 22)
            }
            .buttonStyle(.glass)
            .buttonBorderShape(.circle)
            .accessibilityLabel(model.isPlaying(item) ? strings.pause : strings.play)
        }
        .swipeActions(edge: .leading) {
            Button { model.enqueue(item) } label: { Label(strings.addToQueue, systemImage: "text.badge.plus") }
                .tint(.indigo)
        }
        .swipeActions(edge: .trailing) {
            Button { Task { await model.markPlayed(item) } } label: {
                Label(strings.action(name: "mark-played"), systemImage: "checkmark.circle")
            }
            .tint(.green)
        }
        .contextMenu {
            Button { model.play(item) } label: { Label(strings.play, systemImage: "play") }
            Button { model.enqueue(item, next: true) } label: { Label(strings.playNext, systemImage: "text.line.first.and.arrowtriangle.forward") }
            Button { model.enqueue(item) } label: { Label(strings.addToQueue, systemImage: "text.badge.plus") }
            Button { Task { await model.markPlayed(item) } } label: { Label(strings.action(name: "mark-played"), systemImage: "checkmark.circle") }
        }
    }
}

/// A centered message in place of a list: empty, or a failure with a way to try again.
struct EmptyState: View {
    let text: String
    var systemImage = "tray"
    var retry: (() -> Void)? = nil
    var retryLabel = ""

    var body: some View {
        ContentUnavailableView {
            Label(text, systemImage: systemImage)
        } actions: {
            if let retry { Button(retryLabel, action: retry).buttonStyle(.glass) }
        }
    }
}

extension UTType {
    static let opml = UTType(filenameExtension: "opml", conformingTo: .xml) ?? .xml
}

/// The subscriptions as an OPML file, for the system's file exporter.
struct OpmlDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.opml, .xml] }
    var text: String

    init(text: String) { self.text = text }

    init(configuration: ReadConfiguration) throws {
        text = String(decoding: configuration.file.regularFileContents ?? Data(), as: UTF8.self)
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: Data(text.utf8))
    }
}

extension String {
    /// Text without the HTML of feed descriptions.
    var plainText: String {
        replacingOccurrences(of: "<[^>]+>", with: " ", options: .regularExpression)
            .replacingOccurrences(of: "&nbsp;", with: " ")
            .replacingOccurrences(of: "&amp;", with: "&")
            .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
