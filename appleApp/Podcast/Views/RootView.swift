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

    var body: some View {
        @Bindable var model = model
        let strings = model.strings
        TabView(selection: $tab) {
            Tab(strings.destination(destination: .home), systemImage: "house", value: AppTab.home) {
                NavigationStack { HomeView() }
            }
            Tab(strings.destination(destination: .library), systemImage: "square.stack", value: AppTab.library) {
                NavigationStack { LibraryView() }
            }
            Tab(strings.destination(destination: .playlists), systemImage: "music.note.list", value: AppTab.playlists) {
                NavigationStack { PlaylistsView() }
            }
            Tab(strings.destination(destination: .settings), systemImage: "gearshape", value: AppTab.settings) {
                NavigationStack { SettingsView() }
            }
            Tab(strings.destination(destination: .explore), systemImage: "magnifyingglass", value: AppTab.explore, role: .search) {
                NavigationStack { ExploreView() }
            }
        }
        .tabViewStyle(.sidebarAdaptable)
        #if DEBUG
        .task {
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
        .safeAreaInset(edge: .bottom) {
            if let item = model.playerItem { PlayerBar(item: item) }
        }
        // The player scrolls: a sheet no taller than the window it hangs from.
        .sheet(isPresented: $model.playerOpen) { PlayerView().frame(minWidth: 460, idealWidth: 520, minHeight: 520, idealHeight: 600) }
        #endif
    }
}
