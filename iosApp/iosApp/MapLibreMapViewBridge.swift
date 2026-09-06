import CoreLocation
import MapLibre
import UIKit

enum MapLibreMapViewBridge {
    static func makeView() -> UIView {
        guard let styleURL = Bundle.main.url(forResource: "MapLibrePrototypeStyle", withExtension: "json") else {
            return LocalMapUnavailableView()
        }
        return LocalMapLibreView(styleURL: styleURL)
    }

    static func update(view: UIView, latitude: Double, longitude: Double, zoom: Double) {
        (view as? LocalMapLibreView)?.update(latitude: latitude, longitude: longitude, zoom: zoom)
    }
}

/**
 * Swift owns every MapLibre Native and Core Location type. The embedded style, GeoJSON, and
 * geometry are synthetic and local; no provider URL, key, or user location is configured here.
 */
private final class LocalMapLibreView: UIView, MLNMapViewDelegate {
    private let mapView: MLNMapView
    private var pendingViewport: MapViewport?
    private var renderedViewport: MapViewport?

    init(styleURL: URL) {
        mapView = MLNMapView(frame: .zero, styleURL: styleURL)
        super.init(frame: .zero)

        mapView.delegate = self
        mapView.showsUserLocation = false
        mapView.shouldRequestAuthorizationToUseLocationServices = false
        mapView.disableLocationManager()
        mapView.isAccessibilityElement = true
        mapView.accessibilityLabel = "Local map prototype. Transit data is unavailable."
        mapView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        addSubview(mapView)
    }

    required init?(coder: NSCoder) {
        nil
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        mapView.frame = bounds
    }

    deinit {
        mapView.delegate = nil
        mapView.disableLocationManager()
    }

    func update(latitude: Double, longitude: Double, zoom: Double) {
        let viewport = MapViewport(latitude: latitude, longitude: longitude, zoom: zoom)
        pendingViewport = viewport
        renderIfReady(viewport)
    }

    func mapView(_ mapView: MLNMapView, didFinishLoading style: MLNStyle) {
        if let viewport = pendingViewport, viewport != renderedViewport {
            renderIfReady(viewport)
        }
    }

    private func renderIfReady(_ viewport: MapViewport) {
        let center = CLLocationCoordinate2D(latitude: viewport.latitude, longitude: viewport.longitude)
        mapView.setCenter(center, zoomLevel: viewport.zoom, animated: false)

        guard mapView.style != nil, viewport != renderedViewport else { return }
        renderedViewport = viewport
        mapView.styleJSON = Self.localStyleJSON(center: center)
    }

    private static func localStyleJSON(center: CLLocationCoordinate2D) -> String {
        let start = CLLocationCoordinate2D(latitude: center.latitude - 0.010, longitude: center.longitude - 0.015)
        let end = CLLocationCoordinate2D(latitude: center.latitude + 0.008, longitude: center.longitude + 0.020)
        return """
        {
          "version": 8,
          "name": "Georgia Transit local prototype",
          "sources": {
            "local-center-marker": {
              "type": "geojson",
              "data": {
                "type": "Feature",
                "geometry": { "type": "Point", "coordinates": [\(center.longitude), \(center.latitude)] }
              }
            },
            "local-preview-line": {
              "type": "geojson",
              "data": {
                "type": "Feature",
                "geometry": {
                  "type": "LineString",
                  "coordinates": [
                    [\(start.longitude), \(start.latitude)],
                    [\(center.longitude), \(center.latitude)],
                    [\(end.longitude), \(end.latitude)]
                  ]
                }
              }
            }
          },
          "layers": [
            {
              "id": "local-background",
              "type": "background",
              "paint": { "background-color": "#E7F1EB" }
            },
            {
              "id": "local-preview-line-layer",
              "type": "line",
              "source": "local-preview-line",
              "paint": { "line-color": "#2A9D8F", "line-width": 5, "line-opacity": 0.9 }
            },
            {
              "id": "local-center-marker-layer",
              "type": "circle",
              "source": "local-center-marker",
              "paint": {
                "circle-color": "#E76F51",
                "circle-radius": 8,
                "circle-stroke-color": "#264653",
                "circle-stroke-width": 2
              }
            }
          ]
        }
        """
    }
}

private final class LocalMapUnavailableView: UIView {
    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = UIColor(red: 0.91, green: 0.95, blue: 0.92, alpha: 1)
        isAccessibilityElement = true
        accessibilityLabel = "Local map prototype unavailable."
    }

    required init?(coder: NSCoder) {
        nil
    }
}

private struct MapViewport: Equatable {
    let latitude: Double
    let longitude: Double
    let zoom: Double
}
