# Transit data and BFF

The app never calls TTC, Transitous, Theta, AzRy, or another city provider directly. Shared data talks to the normalized Transit BFF. Provider IDs remain opaque namespaced IDs.

The initial shell supplies preview repositories only. Production work must replace them behind existing domain interfaces with Ktor DTOs/mappers and an offline last-known-good cache. Support loading, empty, retryable error, offline, and stale states. Cancellation is rethrown. GET retries are bounded and never retry arbitrary 4xx responses.

Capabilities from `/v1/cities` drive city and feature availability. Kutaisi remains disabled until the backend enables it. Provider secrets live only on the BFF.

The repository's `:transitBff` module owns the normalized `/v1` runtime and
the city-scoped provider adapter registry. It is a server boundary, never a
shared/mobile dependency. Its current `demo` adapter is an opt-in development
fixture only; production fails closed until a reviewed provider adapter and
operator-held credentials are installed. See [the OpenAPI contract](../../docs/openapi/transit-bff-v1.yaml) and [ADR 0003](../../docs/adr/0003-transit-bff-runtime-and-provider-boundary.md).
