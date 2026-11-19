import SwiftUI
import PodcastKit

private let skipBack = PlaybackController.companion.SKIP_BACK_SECONDS
private let skipForward = PlaybackController.companion.SKIP_FORWARD_SECONDS

/// iPhone: the episode playing (or to resume) above the tab bar, as Music does; a tap opens the player.
struct MiniPlayer: View {
    @Environment(AppModel.self) private var model
    #if os(iOS)
    @Environment(\.tabViewBottomAccessoryPlacement) private var placement
    #endif
    let item: EpisodeItem

    var body: some View {
        let strings = model.strings
        HStack(spacing: 10) {
            ArtworkView(podcast: item.podcast, size: 30)
            VStack(alignment: .leading, spacing: 0) {
                Text(item.episode.title).font(.subheadline.weight(.medium)).lineLimit(1)
                #if os(iOS)
                if placement != .inline {
                    Text(item.podcast.title).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                #endif
            }
            Spacer(minLength: 0)
            Button { model.togglePlay(item) } label: {
                Image(systemName: model.isPlaying(item) ? "pause.fill" : "play.fill").font(.title3)
            }
            .accessibilityLabel(model.isPlaying(item) ? strings.pause : strings.play)
            Button { model.playback.seekBy(seconds: skipForward) } label: {
                Image(systemName: "goforward.30").font(.title3)
            }
            .accessibilityLabel(strings.skipForward(seconds: Int32(skipForward)))
            .disabled(model.nowPlaying == nil)
        }
        .buttonStyle(.plain)
        .padding(.horizontal, 14)
        .contentShape(Rectangle())
        .onTapGesture { model.playerOpen = true }
    }
}

/// Mac: a bar at the bottom of the window, with the position.
struct PlayerBar: View {
    @Environment(AppModel.self) private var model
    let item: EpisodeItem

    var body: some View {
        let strings = model.strings
        HStack(spacing: 16) {
            ArtworkView(podcast: item.podcast, size: 44)
            VStack(alignment: .leading, spacing: 2) {
                Text(item.episode.title).font(.headline).lineLimit(1)
                Text(item.podcast.title).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
            }
            .frame(maxWidth: 280, alignment: .leading)
            Button { model.playback.seekBy(seconds: -skipBack) } label: { Image(systemName: "gobackward.15") }
                .accessibilityLabel(strings.skipBack(seconds: Int32(skipBack)))
            Button { model.togglePlay(item) } label: {
                Image(systemName: model.isPlaying(item) ? "pause.fill" : "play.fill").frame(width: 24, height: 24)
            }
            .buttonStyle(.glassProminent)
            .buttonBorderShape(.circle)
            .accessibilityLabel(model.isPlaying(item) ? strings.pause : strings.play)
            Button { model.playback.seekBy(seconds: skipForward) } label: { Image(systemName: "goforward.30") }
                .accessibilityLabel(strings.skipForward(seconds: Int32(skipForward)))
            PositionSlider(item: item)
            Button { model.playerOpen = true } label: { Image(systemName: "list.bullet") }
                .accessibilityLabel(strings.queue)
        }
        .buttonStyle(.glass)
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .glassEffect(.regular, in: .rect(cornerRadius: 22))
        .padding(10)
    }
}

/// The position, with what remains; the player seeks once the thumb is released.
struct PositionSlider: View {
    @Environment(AppModel.self) private var model
    let item: EpisodeItem
    @State private var dragging: Double?

    var body: some View {
        let strings = model.strings
        let playing = model.nowPlaying?.item.episode.id == item.episode.id ? model.nowPlaying : nil
        let duration = max(playing?.durationSeconds ?? Double(item.episode.durationSeconds), 1)
        let position = min(dragging ?? playing?.state.positionSeconds ?? Double(item.episode.positionSeconds), duration)
        VStack(spacing: 2) {
            Slider(
                value: Binding(get: { position }, set: { dragging = $0 }),
                in: 0...duration,
                onEditingChanged: { editing in
                    if !editing, let value = dragging {
                        model.playback.seekTo(seconds: value)
                        dragging = nil
                    }
                }
            )
            .disabled(playing == nil)
            HStack {
                Text(strings.clock(seconds: Int32(position)))
                Spacer()
                Text("-" + strings.clock(seconds: Int32(duration - position)))
            }
            .font(.caption.monospacedDigit())
            .foregroundStyle(.secondary)
        }
    }
}

/// The player, full screen: the episode, its position, the controls in Liquid Glass, the queue.
struct PlayerView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let strings = model.strings
        NavigationStack {
            if let item = model.playerItem {
                ScrollView {
                    VStack(spacing: 22) {
                        ArtworkView(podcast: item.podcast, size: 260)
                            .shadow(color: .black.opacity(0.25), radius: 24, y: 12)
                            .padding(.top, 8)
                        VStack(spacing: 6) {
                            Text(item.episode.title).font(.title2.bold()).multilineTextAlignment(.center)
                            Text(item.podcast.title).foregroundStyle(.secondary)
                        }
                        PositionSlider(item: item)
                        GlassEffectContainer(spacing: 30) {
                            HStack(spacing: 30) {
                                Button { model.playback.seekBy(seconds: -skipBack) } label: {
                                    Image(systemName: "gobackward.15").font(.title).frame(width: 56, height: 56)
                                }
                                .buttonStyle(.glass)
                                .accessibilityLabel(strings.skipBack(seconds: Int32(skipBack)))
                                Button { model.togglePlay(item) } label: {
                                    Image(systemName: model.isPlaying(item) ? "pause.fill" : "play.fill").font(.largeTitle).frame(width: 76, height: 76)
                                }
                                .buttonStyle(.glassProminent)
                                .accessibilityLabel(model.isPlaying(item) ? strings.pause : strings.play)
                                Button { model.playback.seekBy(seconds: skipForward) } label: {
                                    Image(systemName: "goforward.30").font(.title).frame(width: 56, height: 56)
                                }
                                .buttonStyle(.glass)
                                .accessibilityLabel(strings.skipForward(seconds: Int32(skipForward)))
                            }
                            .buttonBorderShape(.circle)
                        }
                        HStack(spacing: 16) {
                            Button { model.playback.cycleSpeed() } label: {
                                Text(strings.speed(value: model.nowPlaying?.state.speed ?? 1)).monospacedDigit()
                            }
                            .accessibilityLabel(strings.changeSpeed)
                            Button { model.playback.skipToNext() } label: { Label(strings.next, systemImage: "forward.end.fill") }
                                .disabled(model.queue.isEmpty)
                        }
                        .buttonStyle(.glass)
                        if !model.queue.isEmpty {
                            VStack(alignment: .leading, spacing: 10) {
                                HStack {
                                    Text(strings.queue).font(.headline)
                                    Spacer()
                                    Button(strings.clearQueue) { model.playback.clearQueue() }.font(.callout)
                                }
                                ForEach(model.queue, id: \.episode.id) { next in
                                    HStack(spacing: 12) {
                                        ArtworkView(podcast: next.podcast, size: 44)
                                        VStack(alignment: .leading) {
                                            Text(next.episode.title).font(.subheadline).lineLimit(2)
                                            Text(next.podcast.title).font(.caption).foregroundStyle(.secondary)
                                        }
                                        Spacer(minLength: 0)
                                        Button { model.playback.dequeue(episodeId: next.episode.id) } label: { Image(systemName: "xmark") }
                                            .buttonStyle(.borderless)
                                            .accessibilityLabel(strings.removeFromQueue)
                                    }
                                }
                            }
                        }
                    }
                    .padding(20)
                    .frame(maxWidth: 560)
                    .frame(maxWidth: .infinity)
                }
                .navigationTitle(strings.nowPlaying)
                #if os(iOS)
                .navigationBarTitleDisplayMode(.inline)
                #endif
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button { dismiss() } label: { Image(systemName: "chevron.down") }
                            .accessibilityLabel(strings.close)
                    }
                }
            } else {
                EmptyState(text: strings.nothingToPlay, systemImage: "headphones")
            }
        }
    }
}

#if os(macOS)
/// The menu bar of the Mac: playback from the keyboard, ⌘[ and ⌘] as back and forward.
struct PlaybackCommands: Commands {
    let model: AppModel

    var body: some Commands {
        let strings = model.strings
        CommandMenu(strings.playbackMenu) {
            Button(strings.playPause) { model.playback.togglePlayPause() }
                .keyboardShortcut("p", modifiers: [.command, .shift])
            Button(strings.skipBack(seconds: Int32(skipBack))) { model.playback.seekBy(seconds: -skipBack) }
                .keyboardShortcut("[", modifiers: .command)
            Button(strings.skipForward(seconds: Int32(skipForward))) { model.playback.seekBy(seconds: skipForward) }
                .keyboardShortcut("]", modifiers: .command)
            Divider()
            Button(strings.next) { model.playback.skipToNext() }
                .keyboardShortcut("]", modifiers: [.command, .shift])
            Button(strings.changeSpeed) { model.playback.cycleSpeed() }
        }
    }
}
#endif
