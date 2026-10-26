import CoreLocation
import MapLibre
import Shared
import SwiftUI
import UIKit

/// A plain background: what the line is drawn on when the style of the server cannot be loaded (offline). The
/// base map needs the network (offline tiles are MVP-3, TDD-0012); the track does not, it comes from the copy.
private let offlineStyle =
    ##"{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#eceff1"}}]}"##

/// The track of an activity on a map (TDD-0012): one line per recording interval, so a pause is a break, with the
/// camera fitted to it. The map is a picture, not a tool: its gestures are off, so that it does not trap the
/// scrolling of the screen it sits in.
///
/// `styleURL` is the style of the server (`mapStyleUrl`): whoever serves it sees the area looked at.
struct TrackMapView: UIViewRepresentable {
    let track: TrackMap
    let styleURL: URL
    /// Set when the style could not be read and the track is on the plain background.
    @Binding var offline: Bool

    func makeCoordinator() -> Coordinator {
        Coordinator(track: track, offline: $offline)
    }

    func makeUIView(context: Context) -> MLNMapView {
        let mapView = MLNMapView(frame: .zero, styleURL: styleURL)
        mapView.delegate = context.coordinator
        mapView.isScrollEnabled = false
        mapView.isZoomEnabled = false
        mapView.isRotateEnabled = false
        mapView.isPitchEnabled = false
        return mapView
    }

    func updateUIView(_ mapView: MLNMapView, context: Context) {}

    @MainActor
    final class Coordinator: NSObject, @preconcurrency MLNMapViewDelegate {
        private let track: TrackMap
        private let offline: Binding<Bool>
        private var drawn = false

        init(track: TrackMap, offline: Binding<Bool>) {
            self.track = track
            self.offline = offline
        }

        func mapView(_ mapView: MLNMapView, didFinishLoading style: MLNStyle) {
            draw(on: mapView, style: style)
        }

        /// The style of the server could not be read (no network): the track is still drawn, on a plain
        /// background, and the screen says so.
        func mapViewDidFailLoadingMap(_ mapView: MLNMapView, withError error: Error) {
            guard !drawn else { return }
            offline.wrappedValue = true
            mapView.styleJSON = offlineStyle
        }

        private func draw(on mapView: MLNMapView, style: MLNStyle) {
            guard !drawn else { return }
            guard let shape = try? MLNShape(data: Data(track.geoJson.utf8), encoding: String.Encoding.utf8.rawValue)
            else { return }
            drawn = true
            let source = MLNShapeSource(identifier: "track", shape: shape, options: nil)
            style.addSource(source)
            let layer = MLNLineStyleLayer(identifier: "track-line", source: source)
            layer.lineColor = NSExpression(
                forConstantValue: UIColor(red: 0.894, green: 0.341, blue: 0.180, alpha: 1)
            )
            layer.lineWidth = NSExpression(forConstantValue: 4)
            layer.lineCap = NSExpression(forConstantValue: "round")
            layer.lineJoin = NSExpression(forConstantValue: "round")
            style.addLayer(layer)

            let bounds = MLNCoordinateBounds(
                sw: CLLocationCoordinate2D(latitude: track.minLatitude, longitude: track.minLongitude),
                ne: CLLocationCoordinate2D(latitude: track.maxLatitude, longitude: track.maxLongitude)
            )
            mapView.setVisibleCoordinateBounds(
                bounds,
                edgePadding: UIEdgeInsets(top: 24, left: 24, bottom: 24, right: 24),
                animated: false,
                completionHandler: nil
            )
        }
    }
}

/// The map of an activity with the note that goes with it: the style comes from the server, and without it
/// (the app never reached the server) there is nothing to draw on.
struct ActivityMapSection: View {
    let track: TrackMap
    let styleURL: URL?
    @State private var offline = false

    var body: some View {
        if let styleURL {
            TrackMapView(track: track, styleURL: styleURL, offline: $offline)
                .frame(height: 240)
            if offline {
                Text("The base map is not available offline; the track is.")
            }
        } else {
            Text("The map needs the map style of the server: it is read when the app reaches the server.")
        }
    }
}
