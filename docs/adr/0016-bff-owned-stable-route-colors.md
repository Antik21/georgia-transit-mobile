# ADR 0016: BFF-owned stable route colors

Status: Accepted (2026-09-22)

## Context

The normalized route contract already requires `color` and `textColor`, but mobile presentation
treated those values as provider suggestions. When selected routes had equal, similar, transparent,
or map-low-contrast colors, the client reassigned backgrounds from a local fallback palette. The
result depended on the other selected routes and catalogue order, so the same route could change
color between users or after another route was selected.

Routes are selected in a product-bounded set of at most ten, while a city can publish far more
routes than a human-distinguishable palette can represent. Therefore a globally unique color for
every route is not a sustainable identity contract. Stability is the required invariant; a later
design may add a second line discriminator when two selected routes share a color.

## Decision

The Transit BFF owns the final public `color` and `textColor` pair. After validating raw normalized
adapter output and before caching or returning it, the service maps the opaque public route ID with
32-bit FNV-1a into a fixed 18-entry palette. The mapping uses neither provider display values,
locale, catalogue order, process state, nor the user's selection, so list and detail endpoints and
all BFF instances produce the same style for the same route ID.

The palette moves the existing 13 accessible mobile fallback colors to the BFF and adds five
colors. Every entry uses white text, and the additional colors are darkened where necessary rather
than introducing foreground-color variation. Tests require at least 4.5:1 contrast between text
and background and at least 3:1 contrast between the route color and the established light map-land
reference color. Provider colors are still schema-validated before replacement so malformed
upstream data continues to trigger the existing safe schema-drift response.

Mobile presentation preserves the BFF background even when another selected route has the same or
a similar color. It retains only a defensive foreground contrast repair and an explicit selection
overflow state for malformed selections beyond the ten-route product limit.

## Consequences

- A route keeps one color across users, locales, list/detail requests, selection changes, app
  restarts, and independently running BFF instances as long as its public ID and palette remain
  unchanged.
- Every current canonical palette entry uses white route text with validated contrast.
- Provider branding colors no longer reach mobile clients. A future reviewed operator override
  must remain keyed by stable public route ID and satisfy the same contrast checks.
- More than 18 routes reuse palette entries. The BFF does not promise global or selected-set color
  uniqueness, and the client does not silently trade stability for uniqueness.
- Reordering or editing palette entries is a user-visible migration and requires an explicit
  product decision because it changes existing route identities.

## Alternatives

- **Keep client-side collision probing:** rejected because a route's color depends on the selected
  set and catalogue order.
- **Assign colors by catalogue index:** rejected because inserting or reordering routes changes
  existing colors.
- **Persist an unbounded server allocation table:** deferred because it adds storage and migration
  operations without eliminating the finite visual-distinction limit.
- **Allow black text on brighter palette entries:** rejected because a consistent white foreground
  is preferred; new backgrounds are darkened to retain accessible contrast.

## Verification

```text
./gradlew :transitBff:test
./gradlew :shared:testAndroidHostTest
./gradlew spotlessCheck
```

Tests cover a golden stable ID mapping, independence from provider display input, palette size and
contrast, list/detail consistency, selected-set independence, and overflow behavior.
