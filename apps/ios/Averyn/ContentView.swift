import Shared
import SwiftUI

struct ContentView: View {
    @StateObject private var viewModel = RecorderViewModel()

    var body: some View {
        VStack(spacing: 16) {
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
            AccountSection(auth: AppModel.shared.auth, sync: AppModel.shared.sync)
        }
        .padding()
        .alert(
            "Interrupted activity found",
            isPresented: .constant(viewModel.pendingRecovery != nil),
            presenting: viewModel.pendingRecovery
        ) { _ in
            Button("Resume") { viewModel.resolveRecoveryResume() }
            Button("Finish now", role: .destructive) { viewModel.resolveRecoveryFinish() }
        } message: { metadata in
            Text("A \(metadata.sport) activity was interrupted. Resume it or finish it now?")
        }
    }

    private var liveMetrics: some View {
        VStack {
            Text("State: \(viewModel.snapshot.state)")
            Text("Distance: \(Int(viewModel.snapshot.metrics.distanceM)) m")
            Text("Elapsed: \(Int(viewModel.snapshot.metrics.elapsedMs / 1000)) s")
            Text("GPS quality: \(viewModel.snapshot.quality.grade)")
        }
    }

    /// TDD-0001 F8: the aggregate report, for filing a docs/testing/device-matrix.md row after a test run.
    private func diagnosticsSummary(_ diagnostics: DiagnosticsReport) -> some View {
        VStack {
            Text("Quality: \(diagnostics.quality.grade) (\(diagnostics.quality.algorithmVersion))")
            Text("Samples: \(diagnostics.recordingSamples)/\(diagnostics.totalSamples)")
            Text("Longest gap: \(diagnostics.quality.longestGapMs / 1000) s")
            Text(String(format: "Accuracy ≤ 20 m: %.0f%%", diagnostics.quality.accuracyWithin20mPercent))
            if let start = diagnostics.batteryStartPercent, let end = diagnostics.batteryEndPercent {
                let drainText = diagnostics.batteryDrainPercentPerHour.map { String(format: " (%.1f%%/h)", $0.doubleValue) } ?? ""
                Text("Battery: \(start.intValue)% → \(end.intValue)%\(drainText)")
            }
        }
    }

    @ViewBuilder
    private var controls: some View {
        switch viewModel.snapshot.state {
        case .idle, .failed, .completed:
            HStack {
                ForEach([Sport.run, .ride, .walk, .hike], id: \.self) { sport in
                    Button(sport.name) { viewModel.start(sport: sport) }
                }
            }
        case .preparing:
            Text("Waiting for GPS…")
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
    @State private var message: String?

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
                                message = "Sign-in failed"
                            }
                        }
                    }
                }
                Button("Sync now") { Task { await sync.syncNow() } }
            }
            if let message { Text(message) }
            Text(sync.statusText)
        }
        .task {
            while !Task.isCancelled {
                sync.refreshStatus()
                try? await Task.sleep(for: .seconds(3))
            }
        }
    }
}
