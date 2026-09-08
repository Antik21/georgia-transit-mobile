import CoreLocation
import MapLibre
import Shared
import UIKit

/**
 * The Swift-owned end of the typed MapRenderState bridge. MapLibre/UIKit/CoreLocation stay here;
 * the bundled source-free style has no provider URL, key, remote style, or remote tile fallback.
 */
enum MapLibreMapViewBridge {
    static func makeView(
        onViewportSettled: @escaping (MapViewport) -> KotlinUnit,
        onStopTapped: @escaping (Any) -> KotlinUnit
    ) -> UIView {
        guard let styleURL = Bundle.main.url(forResource: "MapLibrePrototypeStyle", withExtension: "json") else {
            return LocalMapUnavailableView()
        }
        return LocalMapLibreView(
            styleURL: styleURL,
            onViewportSettled: onViewportSettled,
            onStopTapped: onStopTapped
        )
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
    private var lastStops: [StopRenderInput]?
    private var lastPolylines: [PolylineRenderInput]?
    private var lastUserLocation: UserLocationRenderInput?
    private var lastVehicleSourceRevision: Int64?
    private var lastVehicleBadgeRevision: Int64?
    private var badgeImageNames: [VehicleBadgeStyle: String] = [:]
    private var nextBadgeImageIndex = 0
    private var released = false
    private let onViewportSettled: (MapViewport) -> KotlinUnit
    private let onStopTapped: (Any) -> KotlinUnit

    init(
        styleURL: URL,
        onViewportSettled: @escaping (MapViewport) -> KotlinUnit,
        onStopTapped: @escaping (Any) -> KotlinUnit
    ) {
        mapView = MLNMapView(frame: .zero, styleURL: styleURL)
        self.onViewportSettled = onViewportSettled
        self.onStopTapped = onStopTapped
        super.init(frame: .zero)

        mapView.delegate = self
        mapView.showsUserLocation = false
        mapView.shouldRequestAuthorizationToUseLocationServices = false
        mapView.disableLocationManager()
        // Compose supplies localized textual state and attribution. Avoid a hard-coded native claim.
        mapView.isAccessibilityElement = false
        mapView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        addSubview(mapView)
        let tapRecognizer = UITapGestureRecognizer(target: self, action: #selector(handleMapTap(_:)))
        tapRecognizer.cancelsTouchesInView = false
        mapView.addGestureRecognizer(tapRecognizer)
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
        clearRenderedLayerState()
        installSourcesAndLayersIfNeeded(style: style)
        pendingRenderState.map { render($0, style: style) }
    }

    func mapView(_ mapView: MLNMapView, regionDidChangeAnimated animated: Bool) {
        reportSettledViewport()
    }

    func releaseResources() {
        guard !released else { return }
        released = true
        pendingRenderState = nil
        mapView.delegate = nil
        mapView.showsUserLocation = false
        mapView.disableLocationManager()
        mapView.removeFromSuperview()
        clearRenderedLayerState()
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
        stopsLayer.circleColor = NSExpression(forKeyPath: Self.markerColorProperty)
        stopsLayer.circleRadius = NSExpression(forKeyPath: Self.markerRadiusProperty)
        stopsLayer.circleStrokeColor = NSExpression(forConstantValue: UIColor.white)
        stopsLayer.circleStrokeWidth = NSExpression(forConstantValue: 2)
        style.addLayer(stopsLayer)

        let vehiclesLayer = MLNSymbolStyleLayer(identifier: Self.vehiclesLayerID, source: vehiclesSource)
        vehiclesLayer.iconImageName = NSExpression(forKeyPath: Self.vehicleBadgeImageProperty)
        vehiclesLayer.iconOpacity = NSExpression(forKeyPath: Self.vehicleOpacityProperty)
        vehiclesLayer.iconAllowsOverlap = NSExpression(forConstantValue: true)
        vehiclesLayer.iconIgnoresPlacement = NSExpression(forConstantValue: true)
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
        let stops = stopRenderInputs(state.stops, state.stopClusters)
        if lastStops != stops {
            updateSource(
                style: style,
                identifier: Self.stopsSourceID,
                features: stopFeatures(state.stops) + clusterFeatures(state.stopClusters)
            )
            lastStops = stops
        }
        if lastVehicleSourceRevision != state.vehicleSourceRevision {
            if lastVehicleBadgeRevision != state.vehicleBadgeRevision {
                updateBadgeImages(style: style, renderInput: vehicleBadgeRenderInput(state.vehicles))
                lastVehicleBadgeRevision = state.vehicleBadgeRevision
            }
            updateSource(style: style, identifier: Self.vehiclesSourceID, features: vehicleFeatures(state.vehicles))
            lastVehicleSourceRevision = state.vehicleSourceRevision
        }
        let polylines = polylineRenderInputs(state.polylines)
        if lastPolylines != polylines {
            updateSource(style: style, identifier: Self.polylinesSourceID, features: polylineFeatures(state.polylines))
            lastPolylines = polylines
        }
        let userLocation = userLocationRenderInput(state.userLocation)
        if lastUserLocation != userLocation {
            updateSource(style: style, identifier: Self.userLocationSourceID, features: userLocationFeatures(state.userLocation))
            updateSource(style: style, identifier: Self.userAccuracySourceID, features: userAccuracyFeatures(state.userLocation))
            lastUserLocation = userLocation
        }
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
        DispatchQueue.main.async { [weak self] in self?.reportSettledViewport() }
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
                    properties: [
                        Self.featureIDProperty: marker.stableId,
                        Self.featureKindProperty: Self.stopFeatureKind,
                        Self.accessibilityLabelProperty: marker.accessibilityLabel,
                        Self.markerColorProperty: marker.isSelected ? Self.selectedStopColor : Self.stopColor,
                        Self.markerRadiusProperty: marker.isSelected ? Self.selectedStopRadius : Self.stopRadius,
                    ]
                )
            }
    }

    private func clusterFeatures(_ clusters: [MapStopCluster]) -> [[String: Any]] {
        clusters
            .filter { !$0.stableId.isEmpty && $0.stopCount > 1 && isCoordinateValid($0.position) }
            .sorted(by: { $0.stableId < $1.stableId })
            .prefix(Self.maximumStopMarkers)
            .map { cluster in
                feature(
                    id: cluster.stableId,
                    coordinates: [cluster.position.longitude, cluster.position.latitude],
                    properties: [
                        Self.featureIDProperty: cluster.stableId,
                        Self.featureKindProperty: Self.clusterFeatureKind,
                        Self.accessibilityLabelProperty: cluster.accessibilityLabel,
                        Self.markerColorProperty: Self.clusterColor,
                        Self.markerRadiusProperty: Self.clusterRadius,
                        Self.clusterCountProperty: cluster.stopCount,
                    ]
                )
            }
    }

    @objc private func handleMapTap(_ recognizer: UITapGestureRecognizer) {
        guard recognizer.state == .ended else { return }
        let point = recognizer.location(in: mapView)
        let halfTarget = Self.minimumStopTargetPoints / 2
        let hitRect = CGRect(
            x: point.x - halfTarget,
            y: point.y - halfTarget,
            width: Self.minimumStopTargetPoints,
            height: Self.minimumStopTargetPoints
        )
        let features = mapView.visibleFeatures(in: hitRect, styleLayerIdentifiers: [Self.stopsLayerID])
        guard let id = features.lazy.compactMap({ feature -> String? in
            guard feature.attribute(forKey: Self.featureKindProperty) as? String == Self.stopFeatureKind else { return nil }
            return feature.attribute(forKey: Self.featureIDProperty) as? String
        }).first else { return }
        _ = onStopTapped(id)
    }

    private func reportSettledViewport() {
        guard !released, !mapView.bounds.isEmpty else { return }
        let centerCoordinate = mapView.centerCoordinate
        let center = GeoPoint(latitude: centerCoordinate.latitude, longitude: centerCoordinate.longitude)
        guard isCoordinateValid(center), mapView.zoomLevel.isFinite else { return }
        let corners = [
            CGPoint(x: mapView.bounds.minX, y: mapView.bounds.minY),
            CGPoint(x: mapView.bounds.maxX, y: mapView.bounds.minY),
            CGPoint(x: mapView.bounds.minX, y: mapView.bounds.maxY),
            CGPoint(x: mapView.bounds.maxX, y: mapView.bounds.maxY),
        ]
        let radius = corners.map { corner -> Double in
            let coordinate = mapView.convert(corner, toCoordinateFrom: mapView)
            return distanceMeters(
                from: center,
                to: GeoPoint(latitude: coordinate.latitude, longitude: coordinate.longitude)
            )
        }.max() ?? 0
        guard radius.isFinite, radius > 0 else { return }
        _ = onViewportSettled(
            MapViewport(
                center: center,
                radiusMeters: Int32(min(50_000, max(1, Int(ceil(radius))))),
                zoom: mapView.zoomLevel
            )
        )
    }

    private func distanceMeters(from: GeoPoint, to: GeoPoint) -> Double {
        let latitudeDelta = (to.latitude - from.latitude) * .pi / 180
        let longitudeDelta = (to.longitude - from.longitude) * .pi / 180
        let firstLatitude = from.latitude * .pi / 180
        let secondLatitude = to.latitude * .pi / 180
        let a = pow(sin(latitudeDelta / 2), 2) +
            cos(firstLatitude) * cos(secondLatitude) * pow(sin(longitudeDelta / 2), 2)
        return Self.earthRadiusMeters * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private func updateBadgeImages(style: MLNStyle, renderInput: VehicleBadgeRenderInput) {
        let retainedStyles = Set(renderInput.styles)
        badgeImageNames.keys.filter { !retainedStyles.contains($0) }.forEach { badgeStyle in
            if let imageName = badgeImageNames.removeValue(forKey: badgeStyle) {
                style.removeImage(forName: imageName)
            }
        }
        renderInput.styles.forEach { badgeStyle in
            guard badgeImageNames[badgeStyle] == nil else { return }
            nextBadgeImageIndex += 1
            let imageName = "gt-vehicle-badge-\(nextBadgeImageIndex)"
            style.setImage(badgeStyle.image(), forName: imageName)
            badgeImageNames[badgeStyle] = imageName
        }
        if renderInput.needsOverflow && style.image(forName: Self.overflowBadgeImageName) == nil {
            style.setImage(VehicleBadgeStyle(label: "?", backgroundArgb: 0xFF455A64, textArgb: 0xFFFFFFFF, stale: false).image(), forName: Self.overflowBadgeImageName)
        }
    }

    private func vehicleFeatures(_ markers: [MapVehicleMarker]) -> [[String: Any]] {
        markers
            .filter { !$0.stableId.isEmpty && !$0.stableRouteId.isEmpty && isCoordinateValid($0.position) }
            .sorted(by: { (first: MapVehicleMarker, second: MapVehicleMarker) in first.stableId < second.stableId })
            .prefix(Self.maximumVehicleMarkers)
            .map { marker in
                var properties: [String: Any] = [
                    Self.vehicleBadgeImageProperty: badgeImageNames[badgeStyle(marker)] ?? Self.overflowBadgeImageName,
                    Self.vehicleOpacityProperty: marker.isStale ? Self.staleVehicleOpacity : 1.0,
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
                let points = Array(polyline.points.filter(isCoordinateValid).prefix(Self.maximumPolylinePoints))
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

    private func badgeStyle(_ marker: MapVehicleMarker) -> VehicleBadgeStyle {
        VehicleBadgeStyle(
            label: nativeBadgeLabel(marker.routeLabel),
            backgroundArgb: marker.routeColorArgb | 0xFF000000,
            textArgb: marker.routeTextColorArgb | 0xFF000000,
            stale: marker.isStale
        )
    }

    /** Exact badge inputs are separate from geometry, so interpolation never recreates bitmaps. */
    private func vehicleBadgeRenderInput(_ markers: [MapVehicleMarker]) -> VehicleBadgeRenderInput {
        let allStyles = Array(
            Set(
                markers
                    .filter { !$0.stableId.isEmpty && !$0.stableRouteId.isEmpty && isCoordinateValid($0.position) }
                    .map(badgeStyle)
            )
        )
        .sorted { $0.sortKey < $1.sortKey }
        return VehicleBadgeRenderInput(
            styles: Array(allStyles.prefix(Self.maximumVehicleBadgeImages)),
            needsOverflow: allStyles.count > Self.maximumVehicleBadgeImages
        )
    }

    /** Exact, bounded values consumed by the stop source, including a11y labels. */
    private func stopRenderInputs(
        _ markers: [MapStopMarker],
        _ clusters: [MapStopCluster]
    ) -> [StopRenderInput] {
        let stops = markers
            .filter { !$0.stableId.isEmpty && isCoordinateValid($0.position) }
            .sorted { $0.stableId < $1.stableId }
            .prefix(Self.maximumStopMarkers)
            .map {
                StopRenderInput(
                    kind: Self.stopFeatureKind,
                    id: $0.stableId,
                    position: RenderCoordinate($0.position),
                    accessibilityLabel: $0.accessibilityLabel,
                    isSelected: $0.isSelected,
                    count: nil
                )
            }
        let clusterItems = clusters
            .filter { !$0.stableId.isEmpty && $0.stopCount > 1 && isCoordinateValid($0.position) }
            .sorted { $0.stableId < $1.stableId }
            .prefix(Self.maximumStopMarkers)
            .map {
                StopRenderInput(
                    kind: Self.clusterFeatureKind,
                    id: $0.stableId,
                    position: RenderCoordinate($0.position),
                    accessibilityLabel: $0.accessibilityLabel,
                    isSelected: false,
                    count: $0.stopCount
                )
            }
        return stops + clusterItems
    }

    /** Exact geometry is retained only for the polylines actually emitted by the native source. */
    private func polylineRenderInputs(_ polylines: [MapPolyline]) -> [PolylineRenderInput] {
        polylines
            .filter { !$0.stableRouteId.isEmpty }
            .sorted {
                let firstDirection = $0.stableDirectionId ?? ""
                let secondDirection = $1.stableDirectionId ?? ""
                return $0.stableRouteId == $1.stableRouteId ? firstDirection < secondDirection : $0.stableRouteId < $1.stableRouteId
            }
            .prefix(Self.maximumPolylines)
            .compactMap { polyline in
                let points = Array(polyline.points.filter(isCoordinateValid).prefix(Self.maximumPolylinePoints))
                guard points.count >= 2, zip(points, points.dropFirst()).contains(where: { $0 != $1 }) else { return nil }
                return PolylineRenderInput(
                    routeId: polyline.stableRouteId,
                    directionId: polyline.stableDirectionId,
                    points: points.map(RenderCoordinate.init),
                    colorArgb: polyline.routeColorArgb,
                    freshness: polyline.freshness.name
                )
            }
    }

    private func userLocationRenderInput(_ location: UserLocationFix?) -> UserLocationRenderInput? {
        guard
            let location,
            isCoordinateValid(location.point),
            location.accuracyMeters.isFinite,
            location.accuracyMeters >= 0
        else { return nil }
        return UserLocationRenderInput(
            position: RenderCoordinate(location.point),
            accuracyMeters: location.accuracyMeters,
            precision: location.precision.name
        )
    }

    private func clearRenderedLayerState() {
        lastStops = nil
        lastPolylines = nil
        lastUserLocation = nil
        lastVehicleSourceRevision = nil
        lastVehicleBadgeRevision = nil
        badgeImageNames.removeAll()
        nextBadgeImageIndex = 0
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
    private static let vehicleBadgeImageProperty = "vehicleBadgeImage"
    private static let vehicleOpacityProperty = "vehicleOpacity"
    private static let featureIDProperty = "featureId"
    private static let featureKindProperty = "featureKind"
    private static let accessibilityLabelProperty = "accessibilityLabel"
    private static let markerColorProperty = "markerColor"
    private static let markerRadiusProperty = "markerRadius"
    private static let clusterCountProperty = "clusterCount"
    private static let stopFeatureKind = "stop"
    private static let clusterFeatureKind = "cluster"
    private static let stopColor = "#2A9D8F"
    private static let selectedStopColor = "#E76F51"
    private static let clusterColor = "#264653"
    private static let stopRadius = 5
    private static let selectedStopRadius = 9
    private static let clusterRadius = 12
    private static let maximumStopMarkers = 1_000
    private static let maximumVehicleMarkers = 2_000
    private static let maximumVehicleBadgeImages = 256
    private static let maximumPolylines = 256
    private static let maximumPolylinePoints = 20_000
    private static let minimumPolygonPoints = 4
    private static let minimumZoom = 0.0
    private static let maximumZoom = 22.0
    private static let accuracySegments = 64
    private static let earthRadiusMeters = 6_371_008.8
    private static let minimumStopTargetPoints: CGFloat = 44
    private static let overflowBadgeImageName = "gt-vehicle-badge-overflow"
    private static let staleVehicleOpacity: Double = 0.62
}

private struct RenderCoordinate: Equatable {
    let latitude: Double
    let longitude: Double

    init(_ point: GeoPoint) {
        latitude = point.latitude
        longitude = point.longitude
    }
}

private struct StopRenderInput: Equatable {
    let kind: String
    let id: String
    let position: RenderCoordinate
    let accessibilityLabel: String
    let isSelected: Bool
    let count: Int32?
}

private struct PolylineRenderInput: Equatable {
    let routeId: String
    let directionId: String?
    let points: [RenderCoordinate]
    let colorArgb: Int64
    let freshness: String
}

private struct UserLocationRenderInput: Equatable {
    let position: RenderCoordinate
    let accuracyMeters: Double
    let precision: String
}

private struct VehicleBadgeStyle: Hashable {
    let label: String
    let backgroundArgb: Int64
    let textArgb: Int64
    let stale: Bool

    var sortKey: String {
        "\(label)|\(backgroundArgb)|\(textArgb)|\(stale)"
    }

    func image() -> UIImage {
        let font = UIFont.boldSystemFont(ofSize: 14)
        let textAttributes: [NSAttributedString.Key: Any] = [
            .font: font,
            .foregroundColor: UIColor(argb: textArgb),
        ]
        let textSize = (label as NSString).size(withAttributes: textAttributes)
        let width = min(max(textSize.width + 24, 36), 104)
        let size = CGSize(width: width, height: 32)
        return UIGraphicsImageRenderer(size: size).image { _ in
            UIColor(argb: backgroundArgb).setFill()
            UIBezierPath(roundedRect: CGRect(origin: .zero, size: size), cornerRadius: size.height / 2).fill()
            let textRect = CGRect(
                x: 0,
                y: (size.height - textSize.height) / 2,
                width: size.width,
                height: textSize.height
            )
            var centeredTextAttributes = textAttributes
            centeredTextAttributes[.paragraphStyle] = centeredParagraphStyle
            (label as NSString).draw(in: textRect, withAttributes: centeredTextAttributes)
            if stale {
                // Two diagonal strokes remain recognisable even when colour and opacity are unavailable.
                let cue = UIBezierPath()
                cue.move(to: CGPoint(x: 5, y: size.height - 5))
                cue.addLine(to: CGPoint(x: size.height - 5, y: 5))
                cue.move(to: CGPoint(x: size.height / 2, y: size.height - 5))
                cue.addLine(to: CGPoint(x: size.height + size.height / 2 - 5, y: 5))
                UIColor(argb: textArgb).withAlphaComponent(0.7).setStroke()
                cue.lineWidth = 2
                cue.stroke()
            }
        }
    }
}

private struct VehicleBadgeRenderInput: Equatable {
    let styles: [VehicleBadgeStyle]
    let needsOverflow: Bool
}

private let centeredParagraphStyle: NSParagraphStyle = {
    let style = NSMutableParagraphStyle()
    style.alignment = .center
    return style
}()

private func nativeBadgeLabel(_ value: String) -> String {
    // Common presentation already removes control/provider punctuation before this native boundary.
    let label = value.trimmingCharacters(in: .whitespacesAndNewlines)
    return String(label.prefix(8)).isEmpty ? "?" : String(label.prefix(8))
}

private extension UIColor {
    convenience init(argb: Int64) {
        self.init(
            red: CGFloat((argb >> 16) & 0xFF) / 255,
            green: CGFloat((argb >> 8) & 0xFF) / 255,
            blue: CGFloat(argb & 0xFF) / 255,
            alpha: CGFloat((argb >> 24) & 0xFF) / 255
        )
    }
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
