// Renders a page in WebKit and writes a PNG at the screen's density.
// usage: webshot <url> <out.png> <width> <height> [dark|light] [delay seconds] [transparent]
// A height of 1 makes the image as tall as the page. Build: swiftc -O -o webshot tools/readme/webshot.swift
import AppKit
import WebKit

let args = CommandLine.arguments
guard args.count >= 5, let url = URL(string: args[1]), let width = Double(args[3]), let height = Double(args[4]) else {
    FileHandle.standardError.write("usage: webshot <url> <out.png> <width> <height> [dark|light] [delay] [transparent]\n".data(using: .utf8)!)
    exit(2)
}
struct Options {
    let url: URL
    let output: URL
    let width: Double
    let height: Double
    let dark: Bool
    let delay: Double
    let transparent: Bool
}
let options = Options(
    url: url, output: URL(fileURLWithPath: args[2]), width: width, height: height,
    dark: args.count > 5 ? args[5] == "dark" : false,
    delay: args.count > 6 ? Double(args[6]) ?? 2 : 2,
    transparent: args.count > 7 && args[7] == "transparent"
)

final class Shooter: NSObject, WKNavigationDelegate {
    let window: NSWindow
    let webView: WKWebView
    var shot = false
    let options: Options

    init(_ options: Options) {
        self.options = options
        let pageURL = options.url, width = options.width, height = options.height, dark = options.dark, transparent = options.transparent
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        let frame = NSRect(x: 0, y: 0, width: width, height: max(height, 100))
        webView = WKWebView(frame: frame, configuration: configuration)
        webView.appearance = NSAppearance(named: dark ? .darkAqua : .aqua)
        if transparent { webView.setValue(false, forKey: "drawsBackground") }
        // On screen but fully transparent: WebKit renders as for a visible window.
        window = NSWindow(contentRect: frame, styleMask: [.borderless], backing: .buffered, defer: false)
        window.isOpaque = false
        window.backgroundColor = .clear
        window.alphaValue = 0.0
        window.ignoresMouseEvents = true
        window.appearance = webView.appearance
        window.contentView = webView
        super.init()
        webView.navigationDelegate = self
        window.orderFrontRegardless()
        let url = pageURL
        if url.isFileURL {
            webView.loadFileURL(url, allowingReadAccessTo: URL(fileURLWithPath: "/"))
        } else {
            webView.load(URLRequest(url: url))
        }
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        DispatchQueue.main.asyncAfter(deadline: .now() + options.delay) {
            // Height 1: as tall as the page.
            guard self.options.height <= 1 else { return self.shoot() }
            webView.evaluateJavaScript("document.documentElement.scrollHeight") { value, _ in
                let height = (value as? Double) ?? Double((value as? Int) ?? 800)
                let frame = NSRect(x: 0, y: 0, width: self.options.width, height: height)
                self.window.setContentSize(frame.size)
                webView.frame = frame
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { self.shoot() }
            }
        }
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        print("failed: \(error)"); exit(1)
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        print("failed: \(error)"); exit(1)
    }

    func shoot() {
        guard !shot else { return }
        shot = true
        let configuration = WKSnapshotConfiguration()
        configuration.afterScreenUpdates = true
        webView.takeSnapshot(with: configuration) { image, error in
            guard let image, let cg = image.cgImage(forProposedRect: nil, context: nil, hints: nil) else {
                print("snapshot failed: \(String(describing: error))"); exit(1)
            }
            let rep = NSBitmapImageRep(cgImage: cg)
            try! rep.representation(using: .png, properties: [:])!.write(to: self.options.output)
            print("\(self.options.output.path): \(cg.width)x\(cg.height)")
            exit(0)
        }
    }
}

let app = NSApplication.shared
app.setActivationPolicy(.accessory)
let shooter = Shooter(options)
DispatchQueue.main.asyncAfter(deadline: .now() + 60) { print("timeout"); exit(1) }
app.run()
