import Shared
import SwiftUI

struct ContentView: View {
    @StateObject private var viewModel = RecorderViewModel()

    /// How the numbers of the history are written (TDD-0012).
    private var format: UnitFormat { UnitFormat(units: viewModel.units) }

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                if let notice = viewModel.notice {
                    Text(notice)
                }
                if let warning = viewModel.recoveryWarning {
                    Text(warning)
                }
                liveMetrics
                controls
                if let url = viewModel.exportURL {
                    ShareLink(item: url) { Text("Share GPX") }
                }
                if let diagnostics = viewModel.diagnostics {
                    diagnosticsSummary(diagnostics)
                    if let url = viewModel.diagnosticsURL {
                        ShareLink(item: url) { Text("Share diagnostics") }
                    }
                }
                historySection
                AccountSection(auth: AppModel.shared.auth, sync: AppModel.shared.sync, viewModel: viewModel)
            }
            .padding()
        }
        .alert(
            "Interrupted activity found",
            isPresented: .constant(viewModel.pendingRecovery != nil),
            presenting: viewModel.pendingRecovery
        ) { _ in
            Button("Resume") { viewModel.resolveRecoveryResume() }
            Button("Finish now", role: .destructive) { viewModel.resolveRecoveryFinish() }
        } message: { metadata in
            Text(tr("A %@ activity was interrupted. Resume it or finish it now?", "\(metadata.sport)"))
        }
    }

    private var liveMetrics: some View {
        VStack {
            Text(tr("State: %@", "\(viewModel.snapshot.state)"))
            Text(tr("Distance: %@ m", "\(Int(viewModel.snapshot.metrics.distanceM))"))
            Text(tr("Elapsed: %@ s", "\(Int(viewModel.snapshot.metrics.elapsedMs / 1000))"))
            Text(tr("GPS quality: %@", "\(viewModel.snapshot.quality.grade)"))
        }
    }

    /// TDD-0001 F8: the aggregate report, for filing a docs/testing/device-matrix.md row after a test run.
    private func diagnosticsSummary(_ diagnostics: DiagnosticsReport) -> some View {
        VStack {
            Text("Quality: \(diagnostics.quality.grade) (\(diagnostics.quality.algorithmVersion))")
            Text("Samples: \(diagnostics.recordingSamples)/\(diagnostics.totalSamples), losses: \(diagnostics.recordLosses)")
            Text("Longest gap: \(diagnostics.quality.longestGapMs / 1000) s")
            if let accuracy = diagnostics.quality.accuracyWithin20mPercent {
                Text(String(format: "Accuracy ≤ 20 m: %.0f%%", accuracy.doubleValue))
            } else {
                Text("Accuracy ≤ 20 m: not measured")
            }
            if let start = diagnostics.batteryStartPercent, let end = diagnostics.batteryEndPercent {
                let drainText = diagnostics.batteryDrainPercentPerHour.map { String(format: " (%.1f%%/h)", $0.doubleValue) } ?? ""
                Text("Battery: \(start.intValue)% → \(end.intValue)%\(drainText)")
            }
            ForEach(Array(diagnostics.recoveryGaps.enumerated()), id: \.offset) { _, gap in
                let length = gap.gapMs.map { "\($0.int64Value / 1000) s" } ?? "open"
                Text("Recovered: gap \(length)" + (gap.rebootDetected ? " (reboot)" : ""))
            }
        }
    }

    /// The history of the account (TDD-0011): what the server has, what this device has, and what can be read
    /// without a connection.
    private var historySection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("History")
            if let snapshot = viewModel.historySnapshot {
                if snapshot.fromCache {
                    Text("Offline copy — the server could not be reached.")
                }
                if snapshot.needsLogin {
                    Text("Sign in to read the activities of the account.")
                }
            }
            ForEach(viewModel.historySnapshot?.items ?? [], id: \.clientActivityId) { item in
                Button {
                    Task { await viewModel.openDetail(item) }
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(historyTitle(item))
                        Text(historyNumbers(item, format))
                        historyBadgesView(item)
                    }
                }
                .buttonStyle(.plain)
            }
            if let detail = viewModel.activityDetail {
                detailView(detail)
            }
            if let message = viewModel.healthMessage { Text(message) }
            if viewModel.historySnapshot?.nextCursor != nil {
                Button("Load more") { Task { await viewModel.loadMoreHistory() } }
            }
            if !viewModel.deviceActivities.isEmpty {
                Text("On this device")
                ForEach(viewModel.deviceActivities, id: \.activityId) { item in
                    deviceRow(item)
                }
            }
        }
        .task { await viewModel.refreshAccountHistory() }
    }

    /// The numbers and the analysis of one activity.
    private func detailView(_ detail: DetailSnapshot) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Activity")
            if detail.deletedOnServer {
                Text("The server does not have this activity any more. The file on this device is untouched.")
            } else {
                if let summary = detail.summary {
                    Text("\(format.distance(meters: summary.distanceM)) — \(formatDuration(summary.elapsedMs))")
                    Text(tr("Moving %@ · Paused %@", formatDuration(summary.movingMs), formatDuration(summary.pausedMs)))
                    // A nullable number crosses the Kotlin boundary as an NSNumber.
                    if let pace = summary.paceSecPerKm?.doubleValue {
                        Text(tr("Pace %@", format.pace(secondsPerKm: pace)))
                    }
                    if summary.avgHrBpm > 0 {
                        Text(tr("HR %@/%@ bpm (%@)", "\(summary.avgHrBpm)", "\(summary.maxHrBpm)", "\(summary.hrSource)"))
                    }
                    Text(tr("Quality %@ · %@", "\(summary.qualityGrade)", "\(summary.metricsAlgorithmVersion)"))
                }
                // The track on a map (TDD-0012): from the copy of the server, so it is there offline too.
                if let track = detail.track {
                    ActivityMapSection(track: track, styleURL: AppModel.shared.auth.mapStyleURL)
                }
                switch detail.analysisState {
                case .ready:
                    if let analysis = detail.analysis {
                        analysisView(analysis)
                    }
                case .computing:
                    Text("The analysis is being computed…")
                case .notCached:
                    Text("The analysis is not on this device.")
                case .notSynced:
                    Text("The analysis comes with the upload of the activity.")
                default:
                    Text("There is no analysis for this activity.")
                }
            }
            if detail.fromCache {
                Text("Offline copy")
            }
        }
    }

    /// The splits and the two curves of the analysis (TDD-0009): the numbers, not a map.
    private func analysisView(_ analysis: ActivityAnalysis) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            if !analysis.splits.isEmpty {
                Text("Splits")
                ForEach(analysis.splits, id: \.index) { split in
                    Text(splitLine(split))
                }
            }
            if let elevation = analysis.elevation {
                Text(
                    tr(
                        "Elevation: %@ / -%@",
                        format.elevation(meters: elevation.gainM, signed: true),
                        format.elevation(meters: elevation.lossM, signed: false)
                    )
                )
            }
            elevationChart(analysis.series)
            heartRateChart(analysis.series)
            if analysis.series.heartRate.bpm.count > 1 {
                zonesView(analysis.heartRateZones)
            }
        }
    }

    /// One split: its distance, its moving time, its heart rate when there is one.
    private func splitLine(_ split: AnalysisSplit) -> String {
        let distance = format.distance(meters: split.endDistanceM - split.startDistanceM)
        var line = "#\(split.index) \(distance) — \(formatDuration(split.movingMs))"
        if let bpm = split.avgHrBpm {
            line += " — \(bpm.intValue) bpm"
        }
        if split.partial {
            line += " (" + tr("partial") + ")"
        }
        return line
    }

    /// The time in each heart-rate zone (`hr-zones-v1`, TDD-0012), or the reason there is none: the zones are cut
    /// from the maximum heart rate of the profile.
    @ViewBuilder
    private func zonesView(_ zones: HeartRateZones?) -> some View {
        if let zones {
            Text(tr("Time in each zone (maximum %@ bpm)", "\(zones.maxHrBpm)"))
            // The zones are always the five of `hr-zones-v1`, in order: the position is the number of the zone.
            ForEach(Array(zones.zones.enumerated()), id: \.offset) { index, zone in
                let range = zone.toBpm.map { "\(zone.fromBpm)–\($0.intValue)" } ?? "\(zone.fromBpm)+"
                Text("Z\(index + 1) \(range) bpm — \(formatDuration(zone.timeMs))")
            }
        } else {
            Text("Set your maximum heart rate in the account to see the time in each zone.")
        }
    }

    /// The profile of the altitude of the activity.
    @ViewBuilder
    private func elevationChart(_ series: AnalysisSeries) -> some View {
        // A nullable number crosses the Kotlin boundary as an NSNumber.
        let elevations = series.elevationM.compactMap { ($0 as? NSNumber)?.doubleValue }
        if elevations.count > 1 {
            Text(
                tr(
                    "Elevation profile (%@–%@)",
                    format.elevation(meters: elevations.min() ?? 0, signed: false),
                    format.elevation(meters: elevations.max() ?? 0, signed: false)
                )
            )
            seriesChart(elevations.map { Float($0) }, segments: series.segment.map { $0.int32Value })
        }
    }

    /// The curve of the heart rate of the activity.
    @ViewBuilder
    private func heartRateChart(_ series: AnalysisSeries) -> some View {
        let bpm = series.heartRate.bpm.map { $0.intValue }
        if bpm.count > 1 {
            Text(tr("Heart rate (%@–%@ bpm)", "\(bpm.min() ?? 0)", "\(bpm.max() ?? 0)"))
            seriesChart(bpm.map { Float($0) }, segments: series.heartRate.segment.map { $0.int32Value })
        }
    }

    /// One curve of the analysis: the points of a series, with the pauses as breaks.
    private func seriesChart(_ values: [Float], segments: [Int32]) -> some View {
        Canvas { context, size in
            guard values.count > 1 else { return }
            let minValue = values.min() ?? 0
            let maxValue = values.max() ?? 1
            let span = maxValue - minValue == 0 ? 1 : maxValue - minValue
            let stepX = size.width / CGFloat(values.count - 1)
            var path = Path()
            var started = false
            for (index, value) in values.enumerated() {
                let segment = index < segments.count ? segments[index] : (segments.last ?? 0)
                let previous = index > 0 && index - 1 < segments.count ? segments[index - 1] : segment
                let x = CGFloat(index) * stepX
                let y = size.height - CGFloat((value - minValue) / span) * size.height
                if !started || segment != previous {
                    path.move(to: CGPoint(x: x, y: y))
                    started = true
                } else {
                    path.addLine(to: CGPoint(x: x, y: y))
                }
            }
            context.stroke(path, with: .color(.green), lineWidth: 2)
        }
        .frame(height: 80)
    }

    /// One activity stored on this device: its files and the way to get rid of them.
    private func deviceRow(_ item: DeviceActivityItem) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("\(item.sport) \(formatDate(item.startedAtMs)) — \(syncLabel(item.syncState))")
            if let summary = item.summary {
                Text("\(format.distance(meters: summary.distanceM)) — \(formatDuration(summary.elapsedMs))")
            }
            if item.deletedOnServer {
                Text("The server does not have it any more.")
            }
            Text(tr("%@ kB on this device", "\(item.localBytes / 1024)"))
            HStack {
                if let files = viewModel.preparedExports[item.activityId] {
                    ShareLink(item: files.gpx) { Text("GPX") }
                    ShareLink(item: files.diagnostics) { Text("Diagnostics") }
                    ShareLink(item: files.raw) { Text("Raw file") }
                } else {
                    Button("Export") { viewModel.prepareExport(activityId: item.activityId) }
                }
                Button("Delete") { Task { await viewModel.deleteLocal(item) } }
            }
            // A finished activity can be saved to Health, at the user's request.
            if item.summary != nil && HealthKitExporter.isAvailable {
                Button("Save to Health") { Task { await viewModel.saveToHealth(item) } }
            }
        }
    }

    @ViewBuilder
    private var controls: some View {
        switch viewModel.snapshot.state {
        case .idle, .failed, .completed:
            HStack {
                ForEach([Sport.run, .ride, .walk, .hike], id: \.self) { sport in
                    Button(sportName(sport)) { viewModel.start(sport: sport) }
                }
            }
        case .preparing:
            VStack {
                Text("Waiting for GPS…")
                Button("Cancel") { viewModel.cancel() }
            }
        case .recording:
            HStack {
                Button("Pause") { viewModel.pause() }
                Button("Stop") { viewModel.stop() }
            }
        case .paused:
            HStack {
                Button("Resume") { viewModel.resume() }
                Button("Stop") { viewModel.stop() }
            }
        case .stopping:
            Text("Finishing…")
        default:
            EmptyView()
        }
    }
}

/// TDD-0002: which server to sync to, sign-in with its identity provider, and where the latest activity stands.
private struct AccountSection: View {
    @ObservedObject var auth: AuthManager
    @ObservedObject var sync: SyncService
    @ObservedObject var viewModel: RecorderViewModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var message: String?
    @State private var compatibility: ClientCompatibility = .ok
    @State private var cacheSize: String = ""
    @State private var confirmingDelete = false
    @State private var deleteWord = ""

    var body: some View {
        VStack(spacing: 8) {
            TextField("Server URL", text: $auth.serverURL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.URL)
                .textFieldStyle(.roundedBorder)
            HStack {
                if auth.isSignedIn {
                    Button("Sign out") { auth.signOut() }
                } else {
                    Button("Sign in") {
                        Task {
                            do {
                                try await auth.signIn()
                                message = nil
                                await sync.syncNow()
                            } catch {
                                message = tr("Sign-in failed")
                            }
                        }
                    }
                }
                Button("Sync now") { Task { await sync.syncNow() } }
            }
            if let warning = compatibilityText(compatibility) {
                Text(warning)
            }
            if !cacheSize.isEmpty {
                Text(cacheSize)
                Button("Delete the offline copy") {
                    Task {
                        let repository = AppModel.shared.history()
                        try? await repository.clearCache()
                        cacheSize = await cacheSizeText()
                    }
                }
            }
            if auth.isSignedIn {
                Button("Delete my account", role: .destructive) {
                    deleteWord = ""
                    confirmingDelete = true
                }
            }
            // The operator's own documents (the controller of the data of this instance), when it published them.
            HStack {
                if let url = auth.privacyPolicyURL { Link("Privacy policy", destination: url) }
                if let url = auth.termsURL { Link("Terms of service", destination: url) }
            }
            if let message { Text(message) }
            Text(sync.statusText)
            ProfileSection(viewModel: viewModel, isSignedIn: auth.isSignedIn)
        }
        // The last confirmation (ADR-0017): the user types the word, so a stray tap cannot delete an account.
        .alert("Delete your account?", isPresented: $confirmingDelete) {
            TextField("Type delete to confirm", text: $deleteWord)
            Button("Delete my account", role: .destructive) {
                guard deleteWord.trimmingCharacters(in: .whitespaces).lowercased() == "delete" else { return }
                Task {
                    if await viewModel.deleteAccount() {
                        cacheSize = ""
                        message = tr(
                            "Your account and its data were deleted from the server. The recordings on this device stay. "
                                + "Your sign-in at the identity provider is not removed."
                        )
                    } else {
                        message = tr("Could not delete your account. Try again.")
                    }
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(
                "This removes all your activities and their recorded data from the server for good. The recordings on this device stay. Your sign-in at the identity provider is not removed: close it there if you want it gone. Export your data first if you want a copy."
            )
        }
        // Only while the screen is in front: nothing here is worth CPU or battery while a recording runs with the
        // screen locked. The task restarts by itself when the scene becomes active again.
        .task(id: scenePhase) {
            guard scenePhase == .active else { return }
            await auth.refreshServerConfig()
            compatibility = auth.compatibility
            cacheSize = await cacheSizeText()
            while !Task.isCancelled {
                sync.refreshStatus()
                try? await Task.sleep(for: .seconds(3))
            }
        }
    }

    /// The warning of a version that does not fit (TDD-0011): it never stops the app from working.
    private func compatibilityText(_ compatibility: ClientCompatibility) -> String? {
        switch compatibility {
        case .ok:
            return nil
        case .appTooOld:
            return tr("This server is newer than the app: update the app to use everything it offers.")
        case .appBelowMinimum:
            return tr("This server needs a newer app: update the app.")
        case .serverTooOld:
            return tr("This server is older than the app: some features may be missing.")
        default:
            return nil
        }
    }

    /// What the copy of the server occupies on this device.
    private func cacheSizeText() async -> String {
        let repository = AppModel.shared.history()
        guard let stats = try? await repository.cacheStats(), stats.detailCount > 0 else { return "" }
        return tr("Offline copy: %@ activities, %@ kB", "\(stats.detailCount)", "\(stats.bytes / 1024)")
    }
}

/// The settings of the account (TDD-0012): the unit system and the maximum heart rate. They live on the server, so
/// the section needs a session; the unit system is also remembered on the device and holds offline.
private struct ProfileSection: View {
    @ObservedObject var viewModel: RecorderViewModel
    let isSignedIn: Bool
    @State private var units: UnitSystem = .metric
    @State private var maxHr = ""
    @State private var loaded = false

    var body: some View {
        let input = UserProfileKt.parseMaxHr(text: maxHr)
        if isSignedIn {
            VStack(alignment: .leading, spacing: 8) {
                Text("Profile")
                Picker("Units", selection: $units) {
                    Text("Metric (km, m)").tag(UnitSystem.metric)
                    Text("Imperial (mi, ft)").tag(UnitSystem.imperial)
                }
                .pickerStyle(.segmented)
                .disabled(!loaded)
                TextField("Maximum heart rate (bpm)", text: $maxHr)
                    .keyboardType(.numberPad)
                    .textFieldStyle(.roundedBorder)
                    .disabled(!loaded)
                if !input.valid {
                    Text(
                        tr(
                            "A whole number from %@ to %@, or empty.",
                            "\(UserProfile.companion.MIN_MAX_HR_BPM)",
                            "\(UserProfile.companion.MAX_MAX_HR_BPM)"
                        )
                    )
                }
                Button("Save profile") {
                    Task { await viewModel.saveProfile(units: units, maxHr: input) }
                }
                .disabled(!loaded || !input.valid)
                if let message = viewModel.profileMessage { Text(message) }
            }
            .task(id: isSignedIn) {
                await viewModel.loadProfile()
                if let profile = viewModel.profile {
                    units = profile.units
                    maxHr = profile.maxHrBpm.map { String($0.intValue) } ?? ""
                    loaded = true
                }
            }
        }
    }
}

/// One row of the list: the numbers of the activity and where they come from.
private func historyTitle(_ item: HistoryItem) -> String {
    "\(item.sport) \(formatDate(item.startedAtMs))"
}
private func historyNumbers(_ item: HistoryItem, _ format: UnitFormat) -> String {
    var parts = [format.distance(meters: item.distanceM), formatDuration(item.elapsedMs)]
    // A nullable number crosses the Kotlin boundary as an NSNumber.
    if let pace = item.paceSecPerKm?.doubleValue {
        parts.append(format.pace(secondsPerKm: pace))
    }
    if item.avgHrBpm > 0 {
        parts.append(tr("HR %@/%@ bpm", "\(item.avgHrBpm)", "\(item.maxHrBpm)"))
    }
    return parts.joined(separator: " · ")
}

private func historyBadges(_ item: HistoryItem) -> String {
    var badges: [String] = []
    switch item.source {
    case .gpx:
        badges.append(tr("imported"))
    case .local:
        badges.append(tr("on this device"))
    default:
        break
    }
    if let syncState = item.syncState {
        badges.append(syncLabel(syncState))
    }
    if item.localEstimate {
        badges.append(tr("local estimate"))
    }
    return badges.joined(separator: " · ")
}

/// The badges of a row, when there are any.
@ViewBuilder
private func historyBadgesView(_ item: HistoryItem) -> some View {
    let badges = historyBadges(item)
    if !badges.isEmpty {
        Text(badges)
    }
}

private func syncLabel(_ state: HistorySyncState) -> String {
    switch state {
    case .localOnly:
        return tr("recording")
    case .queued:
        return tr("waiting to upload")
    case .uploading:
        return tr("uploading")
    case .synced:
        return tr("on the server")
    case .failedRetryable:
        return tr("upload failed, will retry")
    case .failedPermanent:
        return tr("the server refused it")
    case .deletedOnServer:
        return tr("deleted on the server")
    default:
        return tr("unknown")
    }
}

/// The name of a sport in the language of the phone.
private func sportName(_ sport: Sport) -> String {
    switch sport {
    case .run: return tr("Run")
    case .ride: return tr("Ride")
    case .walk: return tr("Walk")
    case .hike: return tr("Hike")
    default: return sport.name
    }
}

private func formatDate(_ epochMs: Int64) -> String {
    let formatter = DateFormatter()
    formatter.dateStyle = .medium
    formatter.timeStyle = .short
    return formatter.string(from: Date(timeIntervalSince1970: Double(epochMs) / 1000))
}

private func formatDuration(_ ms: Int64) -> String {
    let total = Int(ms / 1000)
    let hours = total / 3600
    let minutes = (total % 3600) / 60
    let seconds = total % 60
    return hours > 0
        ? String(format: "%d:%02d:%02d", hours, minutes, seconds)
        : String(format: "%d:%02d", minutes, seconds)
}
