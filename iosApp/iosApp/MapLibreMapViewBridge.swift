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

    static func update(
        view: UIView,
        latitude: Double,
        longitude: Double,
        zoom: Double,
        contentLatitude: Double,
        contentLongitude: Double,
        userLatitude: Double?,
        userLongitude: Double?,
        accuracy: Double?,
        isPrecise: Bool
    ) {
        (view as? LocalMapLibreView)?.update(
            latitude: latitude,
            longitude: longitude,
            zoom: zoom,
            contentLatitude: contentLatitude,
            contentLongitude: contentLongitude,
            userLatitude: userLatitude,
            userLongitude: userLongitude,
            accuracy: accuracy,
            isPrecise: isPrecise
        )
    }
}

/**
 * Swift owns every MapLibre Native and Core Location type. The embedded style, GeoJSON, and
 * geometry are synthetic and local; no provider URL or key is configured here. An already
 * validated one-shot user fix is injected through the narrow update boundary.
 */
private final class LocalMapLibreView: UIView, MLNMapViewDelegate {
    private let mapView: MLNMapView
    private var pendingState: MapState?
    private var renderedState: MapState?

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

    func update(
        latitude: Double,
        longitude: Double,
        zoom: Double,
        contentLatitude: Double,
        contentLongitude: Double,
        userLatitude: Double?,
        userLongitude: Double?,
        accuracy: Double?,
        isPrecise: Bool
    ) {
        let state = MapState(
            viewport: MapViewport(
                latitude: latitude,
                longitude: longitude,
                zoom: zoom,
                contentLatitude: contentLatitude,
                contentLongitude: contentLongitude
            ),
            userLocation: userLatitude.flatMap { latitude in
                userLongitude.flatMap { longitude in
                    accuracy.map {
                        UserLocation(
                            latitude: latitude,
                            longitude: longitude,
                            accuracy: $0,
                            isPrecise: isPrecise
                        )
                    }
                }
            }
        )
        pendingState = state
        renderIfReady(state)
    }

    func mapView(_ mapView: MLNMapView, didFinishLoading style: MLNStyle) {
        if let state = pendingState, state != renderedState {
            renderIfReady(state)
        }
    }

    private func renderIfReady(_ state: MapState) {
        let viewport = state.viewport
        let center = CLLocationCoordinate2D(latitude: viewport.latitude, longitude: viewport.longitude)
        mapView.setCenter(center, zoomLevel: viewport.zoom, animated: false)

        guard mapView.style != nil, state != renderedState else { return }
        renderedState = state
        let contentCenter = CLLocationCoordinate2D(
            latitude: viewport.contentLatitude,
            longitude: viewport.contentLongitude
        )
        mapView.styleJSON = Self.localStyleJSON(center: contentCenter, userLocation: state.userLocation)
    }

    private static func localStyleJSON(center: CLLocationCoordinate2D, userLocation: UserLocation?) -> String {
        let start = CLLocationCoordinate2D(latitude: center.latitude - 0.010, longitude: center.longitude - 0.015)
        let end = CLLocationCoordinate2D(latitude: center.latitude + 0.008, longitude: center.longitude + 0.020)
        let userSources = userLocation.map {
            let ring = accuracyRing(for: $0)
                .map { "[\($0.longitude), \($0.latitude)]" }
                .joined(separator: ",")
            let preciseSource = $0.isPrecise ?
                """
                ,
                "user-location": {
                  "type": "geojson",
                  "data": {
                    "type": "Feature",
                    "geometry": { "type": "Point", "coordinates": [\($0.longitude), \($0.latitude)] }
                  }
                }
                """ : ""
            return """
            ,
            "user-location-accuracy": {
              "type": "geojson",
              "data": {
                "type": "Feature",
                "geometry": { "type": "Polygon", "coordinates": [[\(ring)]] }
              }
            }
            \(preciseSource)
            """
        } ?? ""
        let userLayers = userLocation.map {
            let preciseLayer = $0.isPrecise ?
                """
                ,
                {
                  "id": "user-location-layer",
                  "type": "circle",
                  "source": "user-location",
                  "paint": {
                    "circle-color": "#1565C0",
                    "circle-radius": 7,
                    "circle-stroke-color": "#FFFFFF",
                    "circle-stroke-width": 3
                  }
                }
                """ : ""
            return """
            ,
            {
              "id": "user-location-accuracy-fill-layer",
              "type": "fill",
              "source": "user-location-accuracy",
              "paint": {
                "fill-color": "#1976D2",
                "fill-opacity": 0.18
              }
            },
            {
              "id": "user-location-accuracy-stroke-layer",
              "type": "line",
              "source": "user-location-accuracy",
              "paint": {
                "line-color": "#0D47A1",
                "line-opacity": 0.75,
                "line-width": 2
              }
            }
            \(preciseLayer)
            """
        } ?? ""
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
            }\(userSources)
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
            }\(userLayers)
          ]
        }
        """
    }

    private static func accuracyRing(for location: UserLocation) -> [CLLocationCoordinate2D] {
        let latitude = location.latitude * .pi / 180.0
        let longitude = location.longitude * .pi / 180.0
        let angularDistance = location.accuracy / 6_371_008.8
        var ring = (0..<64).map { index in
            let bearing = 2.0 * .pi * Double(index) / 64.0
            let destinationLatitude = asin(
                sin(latitude) * cos(angularDistance) +
                    cos(latitude) * sin(angularDistance) * cos(bearing)
            )
            let destinationLongitude = longitude + atan2(
                sin(bearing) * sin(angularDistance) * cos(latitude),
                cos(angularDistance) - sin(latitude) * sin(destinationLatitude)
            )
            let longitudeDegrees = destinationLongitude * 180.0 / .pi
            return CLLocationCoordinate2D(
                latitude: destinationLatitude * 180.0 / .pi,
                longitude: ((longitudeDegrees + 540.0).truncatingRemainder(dividingBy: 360.0)) - 180.0
            )
        }
        ring.append(ring[0])
        return ring
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
    let contentLatitude: Double
    let contentLongitude: Double
}

private struct UserLocation: Equatable {
    let latitude: Double
    let longitude: Double
    let accuracy: Double
    let isPrecise: Bool
}

private struct MapState: Equatable {
    let viewport: MapViewport
    let userLocation: UserLocation?
}
