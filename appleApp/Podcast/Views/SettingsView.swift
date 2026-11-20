import SwiftUI
import Charts
import PodcastKit

/// Settings: the accounts on this device, the profile with its avatar, and the listening.
struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @State private var me: User?
    @State private var stats: ListeningStats?
    @State private var name = ""
    @State private var pickingAvatar = false
    @State private var addingAccount = false
    // The server's accounts, for an administrator (ADR 0009).
    @State private var users: [User] = []
    @State private var addingUser = false
    @State private var resetting: User?
    @State private var deleting: User?

    var body: some View {
        let strings = model.strings
        Form {
            Section(strings.accounts) {
                if model.demo {
                    LabeledContent(strings.demoAccount) { Image(systemName: "checkmark").foregroundStyle(.tint) }
                    Button(strings.signOut) { model.leaveDemo() }
                }
                ForEach(model.accounts.accounts, id: \.id) { account in
                    Button { model.select(account) } label: {
                        HStack(spacing: 12) {
                            AvatarView(avatar: account.picture, size: 40)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(account.displayName).foregroundStyle(.primary)
                                Text("\(account.username) · \(account.serverName)").font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            if model.activeAccount?.id == account.id {
                                Image(systemName: "checkmark").foregroundStyle(.tint)
                            }
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .swipeActions {
                        Button(strings.signOut, role: .destructive) { Task { await model.signOut(account) } }
                    }
                    .contextMenu {
                        Button(strings.signOut, role: .destructive) { Task { await model.signOut(account) } }
                    }
                }
                Button { addingAccount = true } label: { Label(strings.addAccount, systemImage: "person.badge.plus") }
            }

            if let me {
                Section(strings.profile) {
                    HStack(spacing: 16) {
                        Button { pickingAvatar = true } label: { AvatarView(avatar: me.picture, size: 64) }
                            .buttonStyle(.plain)
                            .accessibilityLabel("\(strings.chooseAvatar) · \(strings.avatarName(avatar: me.picture))")
                        VStack(alignment: .leading, spacing: 4) {
                            Text(me.displayName).font(.headline)
                            Text("\(me.username) · \(strings.role(role: me.role))").font(.caption).foregroundStyle(.secondary)
                            Button(strings.chooseAvatar) { pickingAvatar = true }.font(.callout)
                        }
                    }
                    TextField(strings.displayName, text: $name)
                    Button(strings.save) { Task { await save(name: name) } }
                        .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty || name == me.displayName)
                }
            }

            if let stats {
                Section(strings.myListening) { ListeningView(stats: stats) }
            }

            if let me, me.role == .admin {
                Section(strings.users) {
                    ForEach(users, id: \.id) { user in
                        UserRow(user: user, isMe: user.id == me.id)
                            .swipeActions {
                                if user.id != me.id {
                                    Button(strings.deleteUser, role: .destructive) { deleting = user }
                                }
                            }
                            .contextMenu {
                                if user.id != me.id {
                                    Button { Task { await setRole(user, user.role == .admin ? .user : .admin) } } label: {
                                        Label(user.role == .admin ? strings.removeAdmin : strings.makeAdmin, systemImage: "person.badge.key")
                                    }
                                    Button { resetting = user } label: { Label(strings.resetPassword, systemImage: "key") }
                                    Button(role: .destructive) { deleting = user } label: { Label(strings.deleteUser, systemImage: "trash") }
                                }
                            }
                    }
                    Button { addingUser = true } label: { Label(strings.addUser, systemImage: "person.badge.plus") }
                }
            }
        }
        .formStyle(.grouped)
        .navigationTitle(strings.destination(destination: .settings))
        .sheet(isPresented: $pickingAvatar) {
            if let me {
                AvatarPicker(current: me.picture) { avatar in Task { await save(avatar: avatar) } }
            }
        }
        .sheet(isPresented: $addingAccount) {
            NavigationStack { SignInView(onDemo: nil) }
        }
        .sheet(isPresented: $addingUser) {
            NewUserView { Task { await load() } }
        }
        .sheet(isPresented: Binding(get: { resetting != nil }, set: { if !$0 { resetting = nil } })) {
            if let resetting { ResetPasswordView(user: resetting) }
        }
        .confirmationDialog(
            deleting.map { strings.deleteUserConfirm(name: $0.displayName) } ?? "",
            isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }),
            titleVisibility: .visible
        ) {
            Button(strings.deleteUser, role: .destructive) {
                if let user = deleting { Task { await delete(user) } }
            }
        }
        .refreshable { await load() }
        .task(id: model.changes) { await load() }
    }

    private func load() async {
        await model.attempt {
            let user = try await model.client.me()
            me = user
            name = user.displayName
            stats = try? await model.client.listeningStats()
            users = user.role == .admin ? (try? await model.client.users()) ?? [] : []
        }
    }

    private func setRole(_ user: User, _ role: UserRole) async {
        await model.attempt {
            _ = try await model.client.updateUser(userId: user.id, displayName: nil, role: role)
            await load()
        }
    }

    private func delete(_ user: User) async {
        await model.attempt {
            try await model.client.deleteUser(userId: user.id)
            model.notice = model.strings.userDeleted(name: user.displayName)
            await load()
        }
    }

    private func save(name: String? = nil, avatar: Avatar? = nil) async {
        await model.attempt {
            let user = try await model.client.updateProfile(displayName: name?.trimmingCharacters(in: .whitespaces), avatar: avatar?.id)
            me = user
            model.profileChanged(user)
            model.notice = model.strings.profileSaved
        }
    }
}

/// The listening (ADR 0012): the totals, the last twelve months in a chart, the most listened.
struct ListeningView: View {
    @Environment(AppModel.self) private var model
    let stats: ListeningStats

    var body: some View {
        let strings = model.strings
        if stats.totalSeconds == 0 && stats.episodesFinished == 0 {
            Text(strings.noListeningYet).foregroundStyle(.secondary)
        } else {
            HStack(alignment: .firstTextBaseline, spacing: 20) {
                figure(stats.totalSeconds, strings.listeningTime)
                figure(stats.last7DaysSeconds, strings.last7Days)
                figure(stats.last30DaysSeconds, strings.last30Days)
            }
            Text([strings.episodesFinished(count: stats.episodesFinished), stats.since.map { strings.listeningSince(date: $0) }]
                .compactMap { $0 }.joined(separator: " · "))
                .font(.caption)
                .foregroundStyle(.secondary)
            Chart(stats.byMonth, id: \.month) { month in
                BarMark(
                    x: .value(strings.byMonth, monthLabel(month)),
                    y: .value(strings.listeningTime, Double(month.seconds) / 3600)
                )
                .foregroundStyle(Color.accentColor.gradient)
                .cornerRadius(4)
            }
            .chartYAxis(.hidden)
            .frame(height: 140)
            .accessibilityLabel(strings.byMonth)
            ForEach(Array(stats.byPodcast.prefix(5)), id: \.feedUrl) { podcast in
                LabeledContent(podcast.podcastTitle, value: strings.duration(seconds: Int32(podcast.seconds)))
            }
        }
    }

    private func figure(_ seconds: Int64, _ label: String) -> some View {
        VStack(alignment: .leading) {
            Text(model.strings.duration(seconds: Int32(seconds))).font(.title3.bold())
            Text(label).font(.caption).foregroundStyle(.secondary)
        }
    }

    private func monthLabel(_ month: MonthListening) -> String {
        model.strings.shortMonth(month: Int32(month.month.split(separator: "-").last.flatMap { Int($0) } ?? 1))
    }
}

/// The avatars to choose from, as on streaming services; a tap saves.
struct AvatarPicker: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let current: Avatar
    let onChoose: (Avatar) -> Void

    var body: some View {
        let strings = model.strings
        NavigationStack {
            ScrollView {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 88), spacing: 18)], spacing: 18) {
                    ForEach(Avatar.allCases, id: \.self) { avatar in
                        Button {
                            onChoose(avatar)
                            dismiss()
                        } label: {
                            AvatarView(avatar: avatar, size: 80)
                                .padding(5)
                                .overlay {
                                    RoundedRectangle(cornerRadius: 24, style: .continuous)
                                        .strokeBorder(avatar == current ? Color.accentColor : .clear, lineWidth: 3)
                                }
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(strings.avatarName(avatar: avatar))
                        .accessibilityAddTraits(avatar == current ? .isSelected : [])
                    }
                }
                .padding()
            }
            .navigationTitle(strings.chooseAvatar)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(strings.cancel) { dismiss() } }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

/// An account of the server, as an administrator sees it.
private struct UserRow: View {
    @Environment(AppModel.self) private var model
    let user: User
    let isMe: Bool

    var body: some View {
        let strings = model.strings
        HStack(spacing: 12) {
            AvatarView(avatar: user.picture, size: 36)
            VStack(alignment: .leading, spacing: 2) {
                Text(isMe ? "\(user.displayName) (\(strings.you))" : user.displayName)
                Text("\(user.username) · \(strings.role(role: user.role))").font(.caption).foregroundStyle(.secondary)
            }
        }
    }
}

/// A new account, created by an administrator: there is no sign-up of one's own (ADR 0009).
struct NewUserView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let onCreated: () -> Void
    @State private var username = ""
    @State private var displayName = ""
    @State private var password = ""
    @State private var admin = false
    @State private var busy = false

    var body: some View {
        let strings = model.strings
        let rules = AccountRules.shared
        let valid = rules.validUsername(username: username) && rules.validDisplayName(displayName: displayName) && rules.validPassword(password: password)
        NavigationStack {
            Form {
                Section {
                    TextField(strings.username, text: $username)
                        .textContentType(.username)
                        .autocorrectionDisabled()
                        #if os(iOS)
                        .textInputAutocapitalization(.never)
                        #endif
                    TextField(strings.displayName, text: $displayName)
                    SecureField(strings.password, text: $password)
                        .textContentType(.newPassword)
                } footer: {
                    Text(strings.passwordHint(minimum: rules.MIN_PASSWORD_LENGTH))
                }
                Section {
                    Toggle(strings.role(role: .admin), isOn: $admin)
                }
            }
            .formStyle(.grouped)
            .navigationTitle(strings.addUser)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(strings.cancel) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(strings.create) { Task { await create() } }.disabled(!valid || busy)
                }
            }
        }
    }

    private func create() async {
        busy = true
        defer { busy = false }
        await model.attempt {
            let user = try await model.client.createUser(username: username, displayName: displayName.trimmingCharacters(in: .whitespaces),
                                                         password: password, role: admin ? .admin : .user)
            model.notice = model.strings.userCreated(name: user.displayName)
            onCreated()
            dismiss()
        }
    }
}

/// A new password for someone who lost theirs; their devices sign in again.
struct ResetPasswordView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let user: User
    @State private var password = ""

    var body: some View {
        let strings = model.strings
        NavigationStack {
            Form {
                Section {
                    SecureField(strings.newPassword, text: $password)
                        .textContentType(.newPassword)
                } footer: {
                    Text(strings.resetPasswordNotice)
                }
            }
            .formStyle(.grouped)
            .navigationTitle(strings.resetPasswordOf(name: user.displayName))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(strings.cancel) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(strings.save) { Task { await reset() } }.disabled(!AccountRules.shared.validPassword(password: password))
                }
            }
        }
    }

    private func reset() async {
        await model.attempt {
            try await model.client.resetPassword(userId: user.id, password: password)
            model.notice = model.strings.passwordReset(name: user.displayName)
            dismiss()
        }
    }
}
