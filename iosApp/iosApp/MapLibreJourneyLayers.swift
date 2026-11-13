import MapLibre
import Shared
import UIKit

/** Planner lines, the long-press pin, and A/B markers; mirrors the Android and web planner layers. */
final class MapLibreJourneyLayers {
    private var renderedOverlay: MapJourneyOverlay?
    private var hasRendered = false
    private var fittedRevision: Int64?
    private var fitGeneration = 0

    func install(style: MLNStyle, below anchor: MLNStyleLayer) {
        let linesSource = MLNShapeSource(identifier: Self.linesSourceID, shape: nil, options: nil)
        let pointsSource = MLNShapeSource(identifier: Self.pointsSourceID, shape: nil, options: nil)
        style.addSource(linesSource)
        style.addSource(pointsSource)
        style.setImage(Self.endpointImage(letter: "A", color: Self.originColor), forName: Self.originImageName)
        style.setImage(Self.endpointImage(letter: "B", color: Self.destinationColor), forName: Self.destinationImageName)

        let walk = MLNLineStyleLayer(identifier: Self.walkLayerID, source: linesSource)
        walk.predicate = NSPredicate(format: "%K == %@", Self.kindProperty, Self.kindWalk)
        walk.lineColor = NSExpression(forConstantValue: UIColor(red: 0x6B / 255, green: 0x72 / 255, blue: 0x80 / 255, alpha: 1))
        walk.lineWidth = NSExpression(forConstantValue: 4)
        walk.lineDashPattern = NSExpression(forConstantValue: [0.6, 1.6])
        Self.roundLine(walk)
        style.insertLayer(walk, below: anchor)

        let casing = MLNLineStyleLayer(identifier: Self.casingLayerID, source: linesSource)
        casing.predicate = NSPredicate(format: "%K == %@", Self.kindProperty, Self.kindTransit)
        casing.lineColor = NSExpression(forConstantValue: UIColor.white)
        casing.lineWidth = NSExpression(forConstantValue: 10)
        Self.roundLine(casing)
        style.insertLayer(casing, below: anchor)

        let transit = MLNLineStyleLayer(identifier: Self.transitLayerID, source: linesSource)
        transit.predicate = NSPredicate(format: "%K == %@", Self.kindProperty, Self.kindTransit)
        transit.lineColor = NSExpression(forKeyPath: Self.colorProperty)
        transit.lineWidth = NSExpression(forConstantValue: 6)
        Self.roundLine(transit)
        style.insertLayer(transit, below: anchor)

        let pin = MLNCircleStyleLayer(identifier: Self.pinLayerID, source: pointsSource)
        pin.predicate = NSPredicate(format: "%K == %@", Self.kindProperty, Self.kindPin)
        pin.circleColor = NSExpression(forConstantValue: UIColor(red: 0x26 / 255, green: 0x32 / 255, blue: 0x38 / 255, alpha: 1))
        pin.circleRadius = NSExpression(forConstantValue: 7)
        pin.circleStrokeColor = NSExpression(forConstantValue: UIColor.white)
        pin.circleStrokeWidth = NSExpression(forConstantValue: 3)
        style.addLayer(pin)

        let endpoints = MLNSymbolStyleLayer(identifier: Self.endpointsLayerID, source: pointsSource)
        endpoints.predicate = NSPredicate(format: "%K != %@", Self.kindProperty, Self.kindPin)
        endpoints.iconImageName = NSExpression(forKeyPath: Self.imageProperty)
        endpoints.iconAllowsOverlap = NSExpression(forConstantValue: true)
        endpoints.iconIgnoresPlacement = NSExpression(forConstantValue: true)
        style.addLayer(endpoints)
        hasRendered = false
    }

    func render(_ overlay: MapJourneyOverlay?, style: MLNStyle) {
        if overlay?.lines.isEmpty ?? true { fitGeneration += 1 }
        if hasRendered && Self.sameContent(overlay, renderedOverlay) { return }
        Self.updateSource(style: style, identifier: Self.linesSourceID, features: lineFeatures(overlay))
        Self.updateSource(style: style, identifier: Self.pointsSourceID, features: pointFeatures(overlay))
        renderedOverlay = overlay
        hasRendered = true
    }

    /** Frames the plan once per user intent; a re-created view frames the visible plan again. */
    func fitIfNeeded(mapView: MLNMapView, overlay: MapJourneyOverlay?) {
        guard let overlay, !overlay.lines.isEmpty, fittedRevision != overlay.fitRevision else { return }
        fittedRevision = overlay.fitRevision
        fitGeneration += 1
        let generation = fitGeneration
        // Results resize the planner panel in the same frame, so frame after the map is re-laid out.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) { [weak self, weak mapView] in
            guard let self, let mapView, self.fitGeneration == generation, !mapView.bounds.isEmpty else { return }
            self.fit(mapView: mapView, overlay: overlay)
        }
    }

    private func fit(mapView: MLNMapView, overlay: MapJourneyOverlay) {
        let points = (overlay.lines.flatMap { $0.points } + [overlay.origin, overlay.destination].compactMap { $0 })
            .filter(Self.isCoordinateValid)
        guard let first = points.first, points.contains(where: { $0 != first }) else { return }
        var bounds = MLNCoordinateBounds(
            sw: CLLocationCoordinate2D(latitude: first.latitude, longitude: first.longitude),
            ne: CLLocationCoordinate2D(latitude: first.latitude, longitude: first.longitude)
        )
        for point in points {
            bounds.sw.latitude = min(bounds.sw.latitude, point.latitude)
            bounds.sw.longitude = min(bounds.sw.longitude, point.longitude)
            bounds.ne.latitude = max(bounds.ne.latitude, point.latitude)
            bounds.ne.longitude = max(bounds.ne.longitude, point.longitude)
        }
        let padding = UIEdgeInsets(top: 64, left: 64, bottom: 64, right: 64)
        let animated = !UIAccessibility.isReduceMotionEnabled
        mapView.setVisibleCoordinateBounds(bounds, edgePadding: padding, animated: animated) { [weak mapView] in
            guard let mapView, mapView.zoomLevel > Self.maximumFitZoom else { return }
            mapView.setZoomLevel(Self.maximumFitZoom, animated: animated)
        }
    }

    func clearRendered() {
        renderedOverlay = nil
        hasRendered = false
    }

    private func lineFeatures(_ overlay: MapJourneyOverlay?) -> [[String: Any]] {
        (overlay?.lines ?? []).compactMap { line in
            let points = line.points.filter(Self.isCoordinateValid)
            guard points.count >= 2 else { return nil }
            return [
                "type": "Feature",
                "properties": [
                    Self.kindProperty: line.kind == .walk ? Self.kindWalk : Self.kindTransit,
                    Self.colorProperty: String(format: "#%06llX", line.colorArgb & 0xFFFFFF),
                ],
                "geometry": ["type": "LineString", "coordinates": points.map { [$0.longitude, $0.latitude] }],
            ]
        }
    }

    private func pointFeatures(_ overlay: MapJourneyOverlay?) -> [[String: Any]] {
        let points: [(GeoPoint?, String?)] = [
            (overlay?.origin, Self.originImageName),
            (overlay?.destination, Self.destinationImageName),
            (overlay?.pin, nil),
        ]
        return points.compactMap { point, image in
            guard let point, Self.isCoordinateValid(point) else { return nil }
            var properties: [String: Any] = [Self.kindProperty: image == nil ? Self.kindPin : Self.kindEndpoint]
            if let image { properties[Self.imageProperty] = image }
            return [
                "type": "Feature",
                "properties": properties,
                "geometry": ["type": "Point", "coordinates": [point.longitude, point.latitude]],
            ]
        }
    }

    private static func updateSource(style: MLNStyle, identifier: String, features: [[String: Any]]) {
        guard
            let source = style.source(withIdentifier: identifier) as? MLNShapeSource,
            let data = try? JSONSerialization.data(withJSONObject: ["type": "FeatureCollection", "features": features]),
            let shape = try? MLNShape(data: data, encoding: String.Encoding.utf8.rawValue)
        else { return }
        source.shape = shape
    }

    /** A new fit revision alone must not rebuild the sources. */
    private static func sameContent(_ first: MapJourneyOverlay?, _ second: MapJourneyOverlay?) -> Bool {
        guard let first, let second else { return first == nil && second == nil }
        return first.lines == second.lines && first.origin == second.origin &&
            first.destination == second.destination && first.pin == second.pin
    }

    private static func roundLine(_ layer: MLNLineStyleLayer) {
        layer.lineCap = NSExpression(forConstantValue: "round")
        layer.lineJoin = NSExpression(forConstantValue: "round")
    }

    private static func isCoordinateValid(_ point: GeoPoint) -> Bool {
        point.latitude.isFinite && point.longitude.isFinite &&
            (-90...90).contains(point.latitude) && (-180...180).contains(point.longitude)
    }

    private static func endpointImage(letter: String, color: UIColor) -> UIImage {
        let size = CGSize(width: endpointDiameter, height: endpointDiameter)
        return UIGraphicsImageRenderer(size: size).image { _ in
            UIColor.white.setFill()
            UIBezierPath(ovalIn: CGRect(origin: .zero, size: size)).fill()
            color.setFill()
            UIBezierPath(ovalIn: CGRect(origin: .zero, size: size).insetBy(dx: 3, dy: 3)).fill()
            let attributes: [NSAttributedString.Key: Any] = [
                .font: UIFont.boldSystemFont(ofSize: endpointDiameter * 0.5),
                .foregroundColor: UIColor.white,
            ]
            let text = letter as NSString
            let textSize = text.size(withAttributes: attributes)
            text.draw(
                at: CGPoint(x: (size.width - textSize.width) / 2, y: (size.height - textSize.height) / 2),
                withAttributes: attributes
            )
        }
    }

    private static let linesSourceID = "gt-journey-lines-source"
    private static let pointsSourceID = "gt-journey-points-source"
    private static let walkLayerID = "gt-journey-walk-layer"
    private static let casingLayerID = "gt-journey-transit-casing-layer"
    private static let transitLayerID = "gt-journey-transit-layer"
    private static let pinLayerID = "gt-journey-pin-layer"
    private static let endpointsLayerID = "gt-journey-endpoints-layer"
    private static let originImageName = "gt-journey-origin"
    private static let destinationImageName = "gt-journey-destination"
    private static let kindProperty = "kind"
    private static let colorProperty = "color"
    private static let imageProperty = "image"
    private static let kindWalk = "walk"
    private static let kindTransit = "transit"
    private static let kindPin = "pin"
    private static let kindEndpoint = "endpoint"
    private static let originColor = UIColor(red: 0x2E / 255, green: 0x7D / 255, blue: 0x32 / 255, alpha: 1)
    private static let destinationColor = UIColor(red: 0x8E / 255, green: 0x1B / 255, blue: 0x2D / 255, alpha: 1)
    private static let endpointDiameter: CGFloat = 28
    private static let maximumFitZoom = 17.0
}
