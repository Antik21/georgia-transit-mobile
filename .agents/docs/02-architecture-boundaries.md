# Architecture boundaries

`shared` uses package layers rather than feature Gradle modules:

| layer | may depend on |
| --- | --- |
| `core` | no other shared layer |
| `domain` | `core` |
| `data` | `core`, `domain` |
| `presentation` | `core`, `domain` |
| `app` | `presentation` |
| `di` | all layers for composition only |

Domain owns transit entities/value objects, repository contracts, use cases, and typed errors. Data owns BFF DTOs/client/cache/repository implementations and explicit mapping. Presentation owns immutable UI state, actions/effects, mappers, ViewModels, screens, and automation IDs. App owns the single common navigation stack. DI owns construction. Native source sets and hosts own OS SDK objects and map/location/lifecycle/runtime-config adapters.

Native types, provider DTOs, DAO types, secrets, and SDK objects stop at their boundaries. Presentation never depends on data implementations. A route may carry a typed ID but not an entity or screen state.

Keep each Kotlin source file at no more than 500 physical lines. When an existing or proposed file exceeds 500 lines, explicitly propose a responsibility-based decomposition and plan to bring it below the limit; do not split files mechanically or move unrelated declarations together just to meet the count. Apply the limit to production and test sources. Existing oversized files are refactoring candidates, not a reason to expand their scope further.

Choose decomposition patterns by layer and ownership. In `domain`, put business decisions in focused interactors and pure policies behind domain repository contracts. In `data`, keep BFF transport, DTO mapping, cache, and repository implementations behind those contracts; use adapters only at external boundaries. In `presentation`, use the existing Orbit stack. For a screen with independent asynchronous workflows, apply the MVI ownership rules in [the Compose screen rule](../rules/compose.md); a simple screen does not need extra handlers merely to imitate Map. Reuse an existing component only after checking that its ownership and contract fit this design; splitting a large class by name alone is insufficient. Do not add a second state-management stack or move UI orchestration into domain/data. The concrete Map ownership and migration are documented in [ADR 0018](../../docs/adr/0018-map-presentation-mvi.md) and [the decomposition record](../../docs/development/map-viewmodel-decomposition.md).
