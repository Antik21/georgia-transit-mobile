"""Make the BFF's ODbL stop snapshot from a Geofabrik Georgia .osm.pbf."""
import argparse
import hashlib
import json
import re
from pathlib import Path

import osmium


KUTAISI_BOUNDARY = 2024547
# Reviewed OSM nodes marked as boarding points for non-city services only.
NON_CITY_ONLY = {
    2544595695,  # Tskaltubo
    4297875592,  # Gelati
    5063546921,  # Zestaphoni
    5520614422,  # Geguti
    6899513792,  # Airport and westbound intercity minibuses
    6902113006,  # Gelati
    10220205385,  # Batumi
    11843259176,  # Dedicated airport shuttle boarding point
    13136688801,  # Mestia
}
# Same coordinates within 3 cm and identical tags as retained node 10992459705.
DUPLICATE_PLATFORMS = {10994721005}
# These source labels list bus numbers instead of a stop name.
ROUTE_NUMBER_LABELS = {
    4269725591, 4269975701, 4325642512, 4505989390, 4506054189,
    5520092997, 5520093001, 6902072852, 6902073828, 6902091648,
    6902096266, 6902104736, 6902109332,
}


def boundary_polygons(source):
    areas = (
        osmium.FileProcessor(source)
        .with_areas(osmium.filter.IdFilter([KUTAISI_BOUNDARY]))
        .with_filter(osmium.filter.EntityFilter(osmium.osm.AREA))
    )
    for area in areas:
        if not area.from_way() and area.orig_id() == KUTAISI_BOUNDARY:
            return json.loads(osmium.geom.GeoJSONFactory().create_multipolygon(area))["coordinates"]
    raise ValueError("Kutaisi municipal boundary is missing")


def in_ring(lon, lat, ring):
    intersections = 0
    for (x1, y1), (x2, y2) in zip(ring, ring[1:]):
        if (y1 > lat) != (y2 > lat) and lon < x1 + (x2 - x1) * (lat - y1) / (y2 - y1):
            intersections += 1
    return intersections % 2 == 1


def extract_stops(source, polygons):
    stops = []
    candidates = osmium.FileProcessor(source, osmium.osm.NODE).with_filter(
        osmium.filter.TagFilter(("highway", "bus_stop")),
    )
    for node in candidates:
        if node.id in NON_CITY_ONLY or node.id in DUPLICATE_PLATFORMS:
            continue
        tags = node.tags
        if tags.get("bus") == "no" or tags.get("public_transport") == "stop_position":
            continue
        if any(tags.get(key) == "yes" for key in ("disused", "abandoned", "construction")):
            continue
        lon, lat = node.location.lon, node.location.lat
        if not any(in_ring(lon, lat, outer) and not any(in_ring(lon, lat, hole) for hole in holes)
                   for outer, *holes in polygons):
            continue
        label_tags = {} if node.id in ROUTE_NUMBER_LABELS else {
            key: tags.get(key, "").strip() for key in ("name", "name:ka", "name:en", "name:ru")
        }
        stops.append({
            "nodeId": node.id,
            "latitude": lat,
            "longitude": lon,
            "ref": tags.get("ref:official", tags.get("ref", "")).strip() or None,
            "name": label_tags.get("name") or None,
            "nameKa": label_tags.get("name:ka") or None,
            "nameEn": label_tags.get("name:en") or None,
            "nameRu": label_tags.get("name:ru") or None,
        })
    if not stops:
        raise ValueError("Kutaisi stop extract is empty")
    return sorted(stops, key=lambda stop: stop["nodeId"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    with osmium.io.Reader(args.source, osmium.osm.NOTHING) as reader:
        timestamp = reader.header().get("osmosis_replication_timestamp")
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z", timestamp):
        raise ValueError("Expected an OSM extract with a replication timestamp")
    sha = hashlib.sha256()
    with args.source.open("rb") as source_file:
        for chunk in iter(lambda: source_file.read(1024 * 1024), b""):
            sha.update(chunk)
    stops = extract_stops(args.source, boundary_polygons(args.source))
    snapshot = {
        "attribution": "© OpenStreetMap contributors",
        "license": "https://opendatacommons.org/licenses/odbl/1-0/",
        "sourceTimestamp": timestamp,
        "sourcePbfSha256": sha.hexdigest(),
        "boundaryRelation": KUTAISI_BOUNDARY,
        "stops": stops,
    }
    with args.output.open("w", encoding="utf-8") as output:
        json.dump(snapshot, output, ensure_ascii=False, indent=2)
        output.write("\n")
    print(f"Wrote {len(stops)} bus stops from the {timestamp} snapshot")


if __name__ == "__main__":
    main()
