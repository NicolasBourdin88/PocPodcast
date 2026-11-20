import SwiftUI
import PodcastKit
#if os(iOS)
import UIKit
#endif

/// What every screen shares: the accounts saved on this device, the client of the active one (or
/// the demo's), and playback. The Kotlin flows are followed here and become observable state.
@MainActor @Observable
final class AppModel {
    let sdk: PodcastSdk
    let strings: any Strings
    private(set) var accounts: AccountsState
    private(set) var demo = false
    private(set) var client: any PodcastClient
    private(set) var playback: PlaybackController
    private(set) var nowPlaying: NowPlaying?
    private(set) var queue: [EpisodeItem] = []
    /// The episode the mini player offers while nothing plays: the first one to resume.
    var resumeItem: EpisodeItem?
    var playerOpen = false
    /// Bumped by every change the other screens show (account, subscription, playlist…).
    private(set) var changes = 0
    /// A sentence to show: a failure, or what an action did.
    var notice: String?

    @ObservationIgnored private var playbackTasks: [Task<Void, Never>] = []

    init() {
        #if os(iOS)
        let device = UIDevice.current.name
        #else
        let device = Host.current().localizedName ?? "Mac"
        #endif
        let sdk = PodcastSdk(deviceName: device)
        self.sdk = sdk
        strings = sdk.strings
        let state = sdk.accounts.state.value
        accounts = state
        let client = sdk.client(account: state.active)
        self.client = client
        playback = sdk.usePlayback(demo: false, client: client)
        followPlayback()
        Task { [weak self] in
            for await state in sdk.accounts.state {
                self?.accountsChanged(state)
            }
        }
    }

    var signedIn: Bool { demo || accounts.active != nil }
    var activeAccount: Account? { demo ? nil : accounts.active }

    /// The episode playing, or else the one to resume.
    var playerItem: EpisodeItem? { nowPlaying?.item ?? resumeItem }

    func isPlaying(_ item: EpisodeItem) -> Bool {
        nowPlaying?.item.episode.id == item.episode.id && nowPlaying?.state.playing == true
    }

    // MARK: Accounts

    private func accountsChanged(_ state: AccountsState) {
        let before = accounts.active
        accounts = state
        let after = state.active
        if !demo && (before?.id != after?.id || before?.token != after?.token) { useClient() }
    }

    func signIn(server: String, username: String, password: String) async throws {
        let wasDemo = demo
        demo = false
        _ = try await sdk.signIn(serverUrl: server, username: username, password: password)
        if wasDemo { useClient() }
    }

    func select(_ account: Account) {
        let switching = demo || accounts.activeId == account.id
        demo = false
        sdk.accounts.select(id: account.id)
        if switching { useClient() }
    }

    func signOut(_ account: Account) async {
        try? await sdk.signOut(account: account)
    }

    func startDemo() {
        demo = true
        useClient()
    }

    func leaveDemo() {
        demo = false
        useClient()
    }

    /// Keeps the saved account in step with its profile: the name and avatar the lists show.
    func profileChanged(_ user: User) {
        guard let account = activeAccount else { return }
        sdk.accounts.update(account: Account(
            serverUrl: account.serverUrl, serverName: account.serverName, username: account.username,
            displayName: user.displayName, token: account.token, avatar: user.avatar
        ))
    }

    private func useClient() {
        client = sdk.client(account: activeAccount)
        playback = sdk.usePlayback(demo: demo, client: client)
        resumeItem = nil
        changed()
        followPlayback()
    }

    // MARK: Playback

    private func followPlayback() {
        playbackTasks.forEach { $0.cancel() }
        let controller = playback
        nowPlaying = controller.nowPlaying.value
        queue = controller.queue.value
        playbackTasks = [
            Task { [weak self] in for await value in controller.nowPlaying { self?.nowPlaying = value } },
            Task { [weak self] in for await value in controller.queue { self?.queue = value } },
            Task { [weak self] in
                for await failure in controller.failures {
                    guard let self else { return }
                    self.notice = failure is PlaybackException ? self.strings.playbackFailed : self.sdk.message(error: failure)
                }
            },
        ]
    }

    func play(_ item: EpisodeItem) {
        playback.play(episodeId: item.episode.id)
    }

    /// Plays [item], or pauses it if it is the one playing.
    func togglePlay(_ item: EpisodeItem) {
        if nowPlaying?.item.episode.id == item.episode.id {
            playback.togglePlayPause()
        } else {
            play(item)
        }
    }

    func enqueue(_ item: EpisodeItem, next: Bool = false) {
        playback.enqueue(episodeId: item.episode.id, next: next)
    }

    // MARK: Changes

    func changed() {
        changes += 1
    }

    func markPlayed(_ item: EpisodeItem) async {
        await attempt { try await self.client.markPlayed(episodeId: item.episode.id) }
        changed()
    }

    /// Runs [action]; a failure becomes the sentence to show, a cancellation nothing.
    func attempt(_ action: () async throws -> Void) async {
        do {
            try await action()
        } catch where !Self.isCancellation(error) {
            notice = message(error)
        } catch {}
    }

    /// A request cancelled because its screen went away or loads again: nothing to tell.
    static func isCancellation(_ error: Error) -> Bool {
        if error is CancellationError { return true }
        return (error as NSError).userInfo["KotlinException"] is KotlinCancellationException
    }

    /// The sentence for a failure, from the shared texts when it comes from Kotlin.
    func message(_ error: Error) -> String {
        if let throwable = (error as NSError).userInfo["KotlinException"] as? KotlinThrowable {
            return sdk.message(error: throwable)
        }
        return error.localizedDescription
    }
}
