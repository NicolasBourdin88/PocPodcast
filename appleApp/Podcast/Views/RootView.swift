import SwiftUI
import PodcastKit

/// Signed in (or in the demo): the app; otherwise the sign-in.
struct RootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        Group {
            if model.signedIn {
                MainView()
            } else {
                NavigationStack { SignInView(onDemo: model.startDemo) }
            }
        }
        .alert(model.notice ?? "", isPresented: Binding(get: { model.notice != nil }, set: { if !$0 { model.notice = nil } })) {
            Button("OK", role: .cancel) {}
        }
    }
}

enum AppTab: Hashable {
    case home, library, playlists, settings, explore

    static var initial: AppTab {
        #if DEBUG
        DebugOptions.tab ?? .home
        #else
        .home
        #endif
    }
}

/// The tabs (ADR 0013): on iPhone a tab bar in Liquid Glass, search apart and the mini player
/// above it; a sidebar on iPad and Mac.
struct MainView: View {
    @Environment(AppModel.self) private var model
    @State private var tab: AppTab = .initial
    @State private var libraryPath = NavigationPath()

    var body: some View {
        @Bindable var model = model
        let strings = model.strings
        TabView(selection: $tab) {
            Tab(strings.destination(destination: .home), systemImage: "house", value: AppTab.home) {
                NavigationStack { HomeView().playerBar() }
            }
            Tab(strings.destination(destination: .library), systemImage: "square.stack", value: AppTab.library) {
                NavigationStack(path: $libraryPath) { LibraryView().playerBar() }
            }
            Tab(strings.destination(destination: .playlists), systemImage: "music.note.list", value: AppTab.playlists) {
                NavigationStack { PlaylistsView().playerBar() }
            }
            Tab(strings.destination(destination: .settings), systemImage: "gearshape", value: AppTab.settings) {
                NavigationStack { SettingsView().playerBar() }
            }
            Tab(strings.destination(destination: .explore), systemImage: "magnifyingglass", value: AppTab.explore, role: .search) {
                NavigationStack { ExploreView().playerBar() }
            }
        }
        .tabViewStyle(.sidebarAdaptable)
        #if DEBUG
        .task {
            if DebugOptions.openFirstPodcast, let first = try? await model.client.subscriptionItems().first {
                // Home loads first (the player bar gets its episode); the library declares its
                // destinations once shown.
                try? await Task.sleep(for: .seconds(3))
                tab = .library
                try? await Task.sleep(for: .seconds(1))
                libraryPath.append(first)
            }
            guard DebugOptions.openPlayer else { return }
            try? await Task.sleep(for: .seconds(2))
            model.playerOpen = true
        }
        #endif
        #if os(iOS)
        .tabBarMinimizeBehavior(.onScrollDown)
        .tabViewBottomAccessory(isEnabled: model.playerItem != nil) {
            if let item = model.playerItem { MiniPlayer(item: item) }
        }
        .sheet(isPresented: $model.playerOpen) { PlayerView() }
        #else
        // The player scrolls: a sheet no taller than the window it hangs from.
        .sheet(isPresented: $model.playerOpen) { PlayerView().frame(minWidth: 460, idealWidth: 520, minHeight: 520, idealHeight: 600) }
        #endif
    }
}

extension View {
    /// Mac: the player bar at the foot of a screen. Each screen carries it, pages pushed in a tab
    /// too: AppKit hosts each column and each pushed page apart, and an inset from around them
    /// does not reach their lists, which then could not scroll their last rows above the bar.
    /// iPhone: the tab bar's accessory does it.
    @ViewBuilder func playerBar() -> some View {
        #if os(macOS)
        modifier(PlayerBarInset())
        #else
        self
        #endif
    }
}

#if os(macOS)
private struct PlayerBarInset: ViewModifier {
    @Environment(AppModel.self) private var model

    func body(content: Content) -> some View {
        content.safeAreaInset(edge: .bottom, spacing: 0) {
            if let item = model.playerItem { PlayerBar(item: item) }
        }
    }
}
#endif
