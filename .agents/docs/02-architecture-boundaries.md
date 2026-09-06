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

