import SwiftUI
import PodcastKit

/// The native client for iPhone, iPad and Mac (ADR 0013): SwiftUI and Liquid Glass on top of the
/// Kotlin SDK (PodcastKit), which keeps the contract — models, API client, rules and texts.
@main
struct PodcastApp: App {
    @State private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                #if DEBUG && os(macOS)
                .task { await DebugOptions.run(model) }
                #endif
        }
        #if os(macOS)
        .defaultSize(width: 1100, height: 760)
        .commands { PlaybackCommands(model: model) }
        #endif
    }
}
