import SwiftUI
import PodcastKit

/// Sign-in on a server (or one more account, ADR 0006); the password goes to the server only,
/// the device keeps its token.
struct SignInView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    var onDemo: (() -> Void)?
    @State private var server = ""
    @State private var username = ""
    @State private var password = ""
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        let strings = model.strings
        Form {
            Section {
                TextField(strings.serverUrl, text: $server)
                    .textContentType(.URL)
                    .autocorrectionDisabled()
                    #if os(iOS)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    #endif
                TextField(strings.username, text: $username)
                    .textContentType(.username)
                    .autocorrectionDisabled()
                    #if os(iOS)
                    .textInputAutocapitalization(.never)
                    #endif
                SecureField(strings.password, text: $password)
                    .textContentType(.password)
                    .onSubmit { Task { await signIn() } }
            } header: {
                Text(strings.signInTitle)
            } footer: {
                if let error { Text(error).foregroundStyle(.red) }
            }
            Section {
                Button { Task { await signIn() } } label: {
                    Group {
                        if busy { ProgressView() } else { Text(strings.signIn) }
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.glassProminent)
                .disabled(busy || username.isEmpty || password.isEmpty)
                if let onDemo {
                    Button(strings.demoMode, action: onDemo).frame(maxWidth: .infinity)
                }
            }
        }
        .formStyle(.grouped)
        .navigationTitle(strings.appName)
        .toolbar {
            if onDemo == nil {
                ToolbarItem(placement: .cancellationAction) { Button(strings.cancel) { dismiss() } }
            }
        }
        .onAppear { if server.isEmpty { server = model.sdk.defaultServerUrl } }
    }

    private func signIn() async {
        guard !busy else { return }
        busy = true
        defer { busy = false }
        do {
            try await model.signIn(server: server, username: username, password: password)
            error = nil
            password = ""
            if onDemo == nil { dismiss() }
        } catch {
            self.error = model.message(error)
        }
    }
}
