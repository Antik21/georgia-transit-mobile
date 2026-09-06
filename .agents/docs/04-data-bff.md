# Transit data and BFF

The app never calls TTC, Transitous, Theta, AzRy, or another city provider directly. Shared data talks to the normalized Transit BFF. Provider IDs remain opaque namespaced IDs.

The initial shell supplies preview repositories only. Production work must replace them behind existing domain interfaces with Ktor DTOs/mappers and an offline last-known-good cache. Support loading, empty, retryable error, offline, and stale states. Cancellation is rethrown. GET retries are bounded and never retry arbitrary 4xx responses.

Capabilities from `/v1/cities` drive city and feature availability. Kutaisi remains disabled until the backend enables it. Provider secrets live only on the BFF.

