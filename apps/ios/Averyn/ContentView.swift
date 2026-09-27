import Shared
import SwiftUI

struct ContentView: View {
    @StateObject private var viewModel = RecorderViewModel()

    var body: some View {
        VStack(spacing: 16) {
            liveMetrics
            controls
            if let url = viewModel.exportURL {
                ShareLink(item: url) { Text("Share GPX") }
            }
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
