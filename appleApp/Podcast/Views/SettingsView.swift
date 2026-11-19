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

    var body: some View {
        let strings = model.strings
        Form {
            Section(strings.accounts) {
                if model.demo {
                    LabeledContent(strings.demoAccount) { Image(systemName: "checkmark").foregroundStyle(Color.brand) }
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
                                Image(systemName: "checkmark").foregroundStyle(Color.brand)
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
        .refreshable { await load() }
        .task(id: model.changes) { await load() }
    }

    private func load() async {
        await model.attempt {
            let user = try await model.client.me()
            me = user
            name = user.displayName
            stats = try? await model.client.listeningStats()
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
                .foregroundStyle(Color.brand.gradient)
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
                                        .strokeBorder(avatar == current ? Color.brand : .clear, lineWidth: 3)
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
