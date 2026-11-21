# ADR 0028 — Kutaisi journeys without a timetable

Status: Accepted for implementation (2026-11-22)

Extends [ADR 0026](0026-kutaisi-azry-transit-feed.md) for trip planning.

## Context

AZRY supplies directional stops, route shapes and per-stop timetable rows. The rows
available to the BFF do not identify which times belong to the same trip. Stop predictions
also cannot establish a future connection for a passenger still on the first bus.
The network can establish where a rider may change buses with the timing left unknown.

## Decision

- Build a city graph in the Kutaisi BFF adapter from the AZRY catalog, ordered direction stops
  and shapes. Share the graph, geometry matching, walking limits and response mapping with
  Batumi. The Kutaisi search uses stop order, walkable links between stops and up to two
  transfers. Rank by transfers, then walking distance, and keep one option per route sequence.
- Build the graph in the background only when the city's operator controls enable trip
  planning. Refresh every two hours using the existing AZRY client budget. A failed refresh
  retains the previous graph for up to 24 hours. The city reports planning as available
  while it has a usable graph.
- Reuse `POST /v1/cities/{cityId}/journeys/plan` and the existing privacy boundary. An
  untimed option carries ordered segments, localized boarding and alighting stops, a
  walking distance and map geometry. Its legacy legs are empty. There are no timestamps,
  waits, ride or total durations, realtime claims, or fastest labels for that option.
  A walk-only option can still carry estimated times.
- New clients send `untimedJourneys=true` on the city listing and plan requests. Earlier
  clients keep the timed wire contract and see the Kutaisi planning capability disabled.
  The mobile and web planners display unknown waiting and omit absent time values.

## Consequences

The results show possible connections between lines. Confirming service hours or a
specific transfer time requires consistent trips across stops or an approved provider
itinerary. The existing provider scope and operator controls govern production activation.
The BFF keeps coordinates request-scoped and queries AZRY for the shared catalog only.

## Verification

Cover direct and transfer options, directionality, walking-only timing, compatibility
with older clients, and display of absent timing in the app.
