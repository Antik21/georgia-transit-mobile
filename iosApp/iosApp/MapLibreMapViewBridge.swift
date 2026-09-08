import CoreLocation
import MapLibre
import Shared
import UIKit

/**
 * The Swift-owned end of the typed MapRenderState bridge. MapLibre/UIKit/CoreLocation stay here;
 * the bundled source-free style has no provider URL, key, remote style, or remote tile fallback.
 */
enum MapLibreMapViewBridge {
    static func makeView() -> UIView {
        guard let styleURL = Bundle.main.url(forResource: "MapLibrePrototypeStyle", withExtension: "json") else {
            return LocalMapUnavailableView()
        }
        return LocalMapLibreView(styleURL: styleURL)
    }

    static func update(view: UIView, renderState: MapRenderState) {
        (view as? LocalMapLibreView)?.update(renderState: renderState)
    }

    static func release(view: UIView) {
        (view as? LocalMapLibreView)?.releaseResources()
    }
}

private final class LocalMapLibreView: UIView, MLNMapViewDelegate {
    private let mapView: MLNMapView
    private var pendingRenderState: MapRenderState?
    private var layersInstalled = false
    private var lastAppliedCameraRevision: Int64?
    private var released = false

    init(styleURL: URL) {
        mapView = MLNMapView(frame: .zero, styleURL: styleURL)
        super.init(frame: .zero)

        mapView.delegate = self
        mapView.showsUserLocation = false
        mapView.shouldRequestAuthorizationToUseLocationServices = false
        mapView.disableLocationManager()
        // Compose supplies localized textual state and attribution. Avoid a hard-coded native claim.
        mapView.isAccessibilityElement = false
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
        releaseResources()
    }

    func update(renderState: MapRenderState) {
        guard !released else { return }
        pendingRenderState = renderState
        guard let style = mapView.style else { return }
        installSourcesAndLayersIfNeeded(style: style)
        render(renderState, style: style)
    }

    func mapView(_ mapView: MLNMapView, didFinishLoading style: MLNStyle) {
        guard !released else { return }
        layersInstalled = false
        lastAppliedCameraRevision = nil
        installSourcesAndLayersIfNeeded(style: style)
        pendingRenderState.map { render($0, style: style) }
    }

    func releaseResources() {
        guard !released else { return }
        released = true
        pendingRenderState = nil
        mapView.delegate = nil
        mapView.showsUserLocation = false
        mapView.disableLocationManager()
        mapView.removeFromSuperview()
    }

    private func installSourcesAndLayersIfNeeded(style: MLNStyle) {
        guard !layersInstalled else { return }
        let stopsSource = MLNShapeSource(identifier: Self.stopsSourceID, shape: nil, options: nil)
        let vehiclesSource = MLNShapeSource(identifier: Self.vehiclesSourceID, shape: nil, options: nil)
        let polylinesSource = MLNShapeSource(identifier: Self.polylinesSourceID, shape: nil, options: nil)
        let userLocationSource = MLNShapeSource(identifier: Self.userLocationSourceID, shape: nil, options: nil)
        let userAccuracySource = MLNShapeSource(identifier: Self.userAccuracySourceID, shape: nil, options: nil)
        [stopsSource, vehiclesSource, polylinesSource, userLocationSource, userAccuracySource].forEach(style.addSource)

        let polylinesLayer = MLNLineStyleLayer(identifier: Self.polylinesLayerID, source: polylinesSource)
        polylinesLayer.lineColor = NSExpression(forKeyPath: Self.routeColorProperty)
        polylinesLayer.lineWidth = NSExpression(forConstantValue: 5)
        polylinesLayer.lineOpacity = NSExpression(forConstantValue: 0.9)
        style.addLayer(polylinesLayer)

        let accuracyFillLayer = MLNFillStyleLayer(identifier: Self.userAccuracyFillLayerID, source: userAccuracySource)
        accuracyFillLayer.fillColor = NSExpression(forConstantValue: UIColor(red: 0.10, green: 0.46, blue: 0.82, alpha: 1))
        accuracyFillLayer.fillOpacity = NSExpression(forConstantValue: 0.18)
        style.addLayer(accuracyFillLayer)

        let accuracyStrokeLayer = MLNLineStyleLayer(identifier: Self.userAccuracyStrokeLayerID, source: userAccuracySource)
        accuracyStrokeLayer.lineColor = NSExpression(forConstantValue: UIColor(red: 0.05, green: 0.28, blue: 0.63, alpha: 1))
        accuracyStrokeLayer.lineWidth = NSExpression(forConstantValue: 2)
        accuracyStrokeLayer.lineOpacity = NSExpression(forConstantValue: 0.75)
        style.addLayer(accuracyStrokeLayer)

        let stopsLayer = MLNCircleStyleLayer(identifier: Self.stopsLayerID, source: stopsSource)
        stopsLayer.circleColor = NSExpression(forConstantValue: UIColor(red: 0.16, green: 0.62, blue: 0.56, alpha: 1))
        stopsLayer.circleRadius = NSExpression(forConstantValue: 5)
        stopsLayer.circleStrokeColor = NSExpression(forConstantValue: UIColor.white)
        stopsLayer.circleStrokeWidth = NSExpression(forConstantValue: 2)
        style.addLayer(stopsLayer)

        let vehiclesLayer = MLNCircleStyleLayer(identifier: Self.vehiclesLayerID, source: vehiclesSource)
        vehiclesLayer.circleColor = NSExpression(forKeyPath: Self.routeColorProperty)
        vehiclesLayer.circleRadius = NSExpression(forConstantValue: 7)
        vehiclesLayer.circleStrokeColor = NSExpression(forConstantValue: UIColor(red: 0.15, green: 0.20, blue: 0.22, alpha: 1))
        vehiclesLayer.circleStrokeWidth = NSExpression(forConstantValue: 2)
        style.addLayer(vehiclesLayer)

        let userLocationLayer = MLNCircleStyleLayer(identifier: Self.userLocationLayerID, source: userLocationSource)
        userLocationLayer.circleColor = NSExpression(forConstantValue: UIColor(red: 0.08, green: 0.40, blue: 0.75, alpha: 1))
        userLocationLayer.circleRadius = NSExpression(forConstantValue: 7)
        userLocationLayer.circleStrokeColor = NSExpression(forConstantValue: UIColor.white)
        userLocationLayer.circleStrokeWidth = NSExpression(forConstantValue: 3)
        style.addLayer(userLocationLayer)

        layersInstalled = true
    }

    private func render(_ state: MapRenderState, style: MLNStyle) {
        updateSource(style: style, identifier: Self.stopsSourceID, features: stopFeatures(state.stops))
        updateSource(style: style, identifier: Self.vehiclesSourceID, features: vehicleFeatures(state.vehicles))
        updateSource(style: style, identifier: Self.polylinesSourceID, features: polylineFeatures(state.polylines))
        updateSource(style: style, identifier: Self.userLocationSourceID, features: userLocationFeatures(state.userLocation))
        updateSource(style: style, identifier: Self.userAccuracySourceID, features: userAccuracyFeatures(state.userLocation))
        applyCameraIfNeeded(state.camera)
    }

    /** Each renderer layer keeps its source and style layer; only its GeoJSON shape is replaced. */
    private func updateSource(style: MLNStyle, identifier: String, features: [[String: Any]]) {
        guard
            let source = style.source(withIdentifier: identifier) as? MLNShapeSource,
            let data = try? JSONSerialization.data(withJSONObject: ["type": "FeatureCollection", "features": features]),
            let shape = try? MLNShape(data: data, encoding: String.Encoding.utf8.rawValue)
        else { return }
        source.shape = shape
    }

    private func applyCameraIfNeeded(_ command: MapCameraCommand) {
        guard lastAppliedCameraRevision != command.revision, isCoordinateValid(command.center), command.zoom.isFinite else { return }
        mapView.setCenter(
            CLLocationCoordinate2D(latitude: command.center.latitude, longitude: command.center.longitude),
            zoomLevel: min(max(command.zoom, Self.minimumZoom), Self.maximumZoom),
            animated: false
        )
        lastAppliedCameraRevision = command.revision
    }

    private func stopFeatures(_ markers: [MapStopMarker]) -> [[String: Any]] {
        markers
            .filter { !$0.stableId.isEmpty && isCoordinateValid($0.position) }
            .sorted(by: { (first: MapStopMarker, second: MapStopMarker) in first.stableId < second.stableId })
            .prefix(Self.maximumStopMarkers)
            .map { marker in
                feature(
                    id: marker.stableId,
                    coordinates: [marker.position.longitude, marker.position.latitude],
                    properties: [:]
                )
            }
    }

    private func vehicleFeatures(_ markers: [MapVehicleMarker]) -> [[String: Any]] {
        markers
            .filter { !$0.stableId.isEmpty && !$0.stableRouteId.isEmpty && isCoordinateValid($0.position) }
            .sorted(by: { (first: MapVehicleMarker, second: MapVehicleMarker) in first.stableId < second.stableId })
            .prefix(Self.maximumVehicleMarkers)
            .map { marker in
                var properties: [String: Any] = [
                    Self.routeColorProperty: mapColor(marker.routeColorArgb),
                    Self.positionKindProperty: marker.positionKind.name,
                ]
                if let bearing = marker.bearingDegrees?.doubleValue, bearing.isFinite {
                    properties[Self.bearingProperty] = normalizedBearing(bearing)
                }
                return feature(
                    id: marker.stableId,
                    coordinates: [marker.position.longitude, marker.position.latitude],
                    properties: properties
                )
            }
    }

    private func polylineFeatures(_ polylines: [MapPolyline]) -> [[String: Any]] {
        polylines
            .filter { !$0.stableRouteId.isEmpty }
            .sorted(by: { (first: MapPolyline, second: MapPolyline) in
                let firstDirection = first.stableDirectionId ?? ""
                let secondDirection = second.stableDirectionId ?? ""
                return first.stableRouteId == second.stableRouteId ? firstDirection < secondDirection : first.stableRouteId < second.stableRouteId
            })
            .prefix(Self.maximumPolylines)
            .compactMap { (polyline: MapPolyline) -> [String: Any]? in
                let points = polyline.points.filter(isCoordinateValid)
                guard points.count >= 2, zip(points, points.dropFirst()).contains(where: { $0 != $1 }) else { return nil }
                return [
                    "type": "Feature",
                    "id": polyline.stableRouteId,
                    "properties": [Self.routeColorProperty: mapColor(polyline.routeColorArgb)],
                    "geometry": [
                        "type": "LineString",
                        "coordinates": points.map { [$0.longitude, $0.latitude] },
                    ],
                ]
            }
    }

    private func userLocationFeatures(_ location: UserLocationFix?) -> [[String: Any]] {
        guard let location, location.precision == .precise, isCoordinateValid(location.point) else { return [] }
        return [feature(id: "user", coordinates: [location.point.longitude, location.point.latitude], properties: [:])]
    }

    private func userAccuracyFeatures(_ location: UserLocationFix?) -> [[String: Any]] {
        guard
            let location,
            isCoordinateValid(location.point),
            location.accuracyMeters.isFinite,
            location.accuracyMeters >= 0
        else { return [] }
        let ring = accuracyRing(center: location.point, accuracyMeters: location.accuracyMeters)
        guard ring.count >= Self.minimumPolygonPoints else { return [] }
        return [[
            "type": "Feature",
            "properties": [:],
            "geometry": ["type": "Polygon", "coordinates": [ring]],
        ]]
    }

    private func feature(id: String, coordinates: [Double], properties: [String: Any]) -> [String: Any] {
        [
            "type": "Feature",
            "id": id,
            "properties": properties,
            "geometry": ["type": "Point", "coordinates": coordinates],
        ]
    }

    private func accuracyRing(center: GeoPoint, accuracyMeters: Double) -> [[Double]] {
        let latitudeRadians = center.latitude * .pi / 180
        let longitudeRadians = center.longitude * .pi / 180
        let angularDistance = accuracyMeters / Self.earthRadiusMeters
        var ring = (0..<Self.accuracySegments).map { index -> [Double] in
            let bearing = 2 * .pi * Double(index) / Double(Self.accuracySegments)
            let destinationLatitude = asin(
                sin(latitudeRadians) * cos(angularDistance) +
                    cos(latitudeRadians) * sin(angularDistance) * cos(bearing)
            )
            let destinationLongitude = longitudeRadians + atan2(
                sin(bearing) * sin(angularDistance) * cos(latitudeRadians),
                cos(angularDistance) - sin(latitudeRadians) * sin(destinationLatitude)
            )
            let latitude = destinationLatitude * 180 / .pi
            let longitude = ((destinationLongitude * 180 / .pi + 540).truncatingRemainder(dividingBy: 360)) - 180
            return [longitude, latitude]
        }
        if let first = ring.first { ring.append(first) }
        return ring
    }

    private func isCoordinateValid(_ point: GeoPoint) -> Bool {
        point.latitude.isFinite && point.longitude.isFinite &&
            (-90...90).contains(point.latitude) && (-180...180).contains(point.longitude)
    }

    private func mapColor(_ color: Int64) -> String {
        String(format: "#%06llX", color & 0xFFFFFF)
    }

    private func normalizedBearing(_ bearing: Double) -> Double {
        (bearing.truncatingRemainder(dividingBy: 360) + 360).truncatingRemainder(dividingBy: 360)
    }

    private static let stopsSourceID = "gt-stops-source"
    private static let vehiclesSourceID = "gt-vehicles-source"
    private static let polylinesSourceID = "gt-polylines-source"
    private static let userLocationSourceID = "gt-user-location-source"
    private static let userAccuracySourceID = "gt-user-accuracy-source"
    private static let stopsLayerID = "gt-stops-layer"
    private static let vehiclesLayerID = "gt-vehicles-layer"
    private static let polylinesLayerID = "gt-polylines-layer"
    private static let userLocationLayerID = "gt-user-location-layer"
    private static let userAccuracyFillLayerID = "gt-user-accuracy-fill-layer"
    private static let userAccuracyStrokeLayerID = "gt-user-accuracy-stroke-layer"
    private static let routeColorProperty = "routeColor"
    private static let bearingProperty = "bearing"
    private static let positionKindProperty = "positionKind"
    private static let maximumStopMarkers = 1_000
    private static let maximumVehicleMarkers = 2_000
    private static let maximumPolylines = 256
    private static let minimumPolygonPoints = 4
    private static let minimumZoom = 0.0
    private static let maximumZoom = 22.0
    private static let accuracySegments = 64
    private static let earthRadiusMeters = 6_371_008.8
}

/** The common overlay supplies localized textual state if the bundled style cannot load. */
private final class LocalMapUnavailableView: UIView {
    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = UIColor(red: 0.91, green: 0.95, blue: 0.92, alpha: 1)
        isAccessibilityElement = false
    }

    required init?(coder: NSCoder) {
        nil
    }
}
