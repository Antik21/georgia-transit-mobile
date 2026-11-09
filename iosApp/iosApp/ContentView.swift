import SwiftUI
import Shared

struct ContentView: View {
    var body: some View {
#if DEBUG
        DebugNetworkInspectorOverlay()
#else
        ComposeViewController()
            // The native map is the visual background of the main screen. Let the shared
            // Compose hierarchy receive the full UIKit bounds, including the status-bar area;
            // Compose applies both system and keyboard insets.
            .ignoresSafeArea(edges: .all)
#endif
    }
}

struct ComposeViewController: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(
            mapViewFactory: { onViewportSettled, onMapEvent in
                MapLibreMapViewBridge.makeView(
                    onViewportSettled: onViewportSettled,
                    onMapEvent: onMapEvent
                )
            },
            updateMapView: { view, renderState in
                MapLibreMapViewBridge.update(view: view, renderState: renderState)
            },
            releaseMapView: { view in
                MapLibreMapViewBridge.release(view: view)
            }
        )
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
