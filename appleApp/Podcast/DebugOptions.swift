#if DEBUG
import SwiftUI
#if os(macOS)
import AppKit
import ScreenCaptureKit
#endif

/// Development only: launch arguments to check the app without clicking through it.
/// - `-tab library` opens a tab (home, library, playlists, settings, explore).
/// - Mac: `-appearance dark` forces an appearance, and `-snapshot <file.png>` saves the app's
///   windows once loaded. The app captures its own windows: no screen recording permission.
enum DebugOptions {
    /// `-player YES`: the player opens once Home is loaded.
    static var openPlayer: Bool { UserDefaults.standard.bool(forKey: "player") }

    /// `-openPodcast YES`: the library opens its first podcast.
    static var openFirstPodcast: Bool { UserDefaults.standard.bool(forKey: "openPodcast") }

    /// `-scrollBottom YES` (Mac): the lists scroll to their end before the snapshot, and their
    /// insets go to `<snapshot>.txt`, to check that the last row shows above the player bar.
    static var scrollToBottom: Bool { UserDefaults.standard.bool(forKey: "scrollBottom") }

    static var tab: AppTab? {
        switch UserDefaults.standard.string(forKey: "tab") {
        case "home": .home
        case "library": .library
        case "playlists": .playlists
        case "settings": .settings
        case "explore": .explore
        default: nil
        }
    }

    #if os(macOS)
    @MainActor
    static func run(_ model: AppModel) async {
        switch UserDefaults.standard.string(forKey: "appearance") {
        case "dark": NSApp.appearance = NSAppearance(named: .darkAqua)
        case "light": NSApp.appearance = NSAppearance(named: .aqua)
        default: break
        }
        guard let path = UserDefaults.standard.string(forKey: "snapshot") else { return }
        let delay = UserDefaults.standard.double(forKey: "snapshotDelay")
        try? await Task.sleep(for: .seconds(delay > 0 ? delay : 5))
        if scrollToBottom {
            var report = "player bar: \(model.playerItem?.episode.title ?? "none")\n"
            for window in NSApp.windows where window.isVisible {
                for scrollView in scrollViews(in: window.contentView) {
                    guard let document = scrollView.documentView, document.frame.height > scrollView.contentView.bounds.height else { continue }
                    let insets = scrollView.contentInsets
                    // The rows' heights are estimated until shown: scroll again until the end stays put.
                    for _ in 0..<20 {
                        let height = document.frame.height
                        let end = document.isFlipped
                            ? height - scrollView.contentView.bounds.height + insets.bottom
                            : -insets.bottom
                        scrollView.contentView.scroll(to: NSPoint(x: scrollView.contentView.bounds.origin.x, y: end))
                        scrollView.reflectScrolledClipView(scrollView.contentView)
                        try? await Task.sleep(for: .milliseconds(300))
                        if document.frame.height == height { break }
                    }
                    report += "scroll view: content \(Int(document.frame.height)), visible \(Int(scrollView.contentView.bounds.height)), insets top \(Int(insets.top)) bottom \(Int(insets.bottom))\n"
                }
            }
            try? report.write(toFile: path + ".txt", atomically: true, encoding: .utf8)
            try? await Task.sleep(for: .seconds(1))
        }
        do {
            let content = try await SCShareableContent.currentProcess
            // The app's own windows only (not the desktop behind them): the main one first, then sheets.
            let ids = Set(NSApp.windows.filter(\.isVisible).map { CGWindowID($0.windowNumber) })
            let windows = content.windows
                .filter { ids.contains($0.windowID) && $0.frame.width > 100 && $0.frame.height > 100 }
                .sorted { $0.frame.width * $0.frame.height > $1.frame.width * $1.frame.height }
            for (index, window) in windows.enumerated() {
                let filter = SCContentFilter(desktopIndependentWindow: window)
                let configuration = SCStreamConfiguration()
                configuration.width = Int(filter.contentRect.width * CGFloat(filter.pointPixelScale))
                configuration.height = Int(filter.contentRect.height * CGFloat(filter.pointPixelScale))
                configuration.showsCursor = false
                let image = try await SCScreenshotManager.captureImage(contentFilter: filter, configuration: configuration)
                let base = URL(fileURLWithPath: path)
                let url = index == 0 ? base : base.deletingPathExtension().appendingPathExtension("\(index).png")
                try NSBitmapImageRep(cgImage: image).representation(using: .png, properties: [:])?.write(to: url)
            }
            print("snapshot: \(windows.count) window(s) to \(path)")
        } catch {
            print("snapshot failed: \(error)")
        }
    }

    @MainActor
    private static func scrollViews(in view: NSView?) -> [NSScrollView] {
        guard let view else { return [] }
        return (view as? NSScrollView).map { [$0] } ?? view.subviews.flatMap(scrollViews(in:))
    }
    #endif
}
#endif
