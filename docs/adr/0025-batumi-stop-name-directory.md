# 0025. Localized stop names for Batumi

## Decision

The Transit BFF owns a versioned directory of passenger-facing names in
`transitBff/src/main/resources/transit/batumi/stop-names.json`. Each row records
the Batumi public stop ID, the observed stop code and WGS84 position, the source,
and the reviewed names in `ru`, `en`, `ka`, `uk`, `de`, `fr`, `ar`, `hi`, and
`zh` (Simplified Chinese).

The Batumi catalog parser applies a row when the ID and stop code match and the
current stop is within 75 metres of the reviewed coordinates. Unmatched rows
use the provider name. A name update affects all reads from the normalized
catalog, including stops near the map and stops in a route direction. It
preserves stop identity, position, route membership, and arrival calculations.

Clients send the effective app language using the existing `locale` field.
`LocalizedText` retains its three required fields and gains six optional fields.
Clients pick the requested translation when present and use `en` for absent
translations in the new locales. The BFF accepts the new locale codes for both
query parameters and journey requests. Deploy the BFF before a mobile release
that sends these codes.

## Maintenance

Review provider records by public ID, with the stop code and location as
cross-checks. For ambiguous records, confirm the name against the operator or a
reliable local source before adding a row. A changed provider code or a move
beyond the matching threshold calls for a fresh check. Separate boarding
locations keep separate rows even when they share the same display name.
