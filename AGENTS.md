# Georgia Transit agent router

Keep shared product behavior in `shared/src/commonMain`; native projects are adapter boundaries.

## Hard rules

- Preserve user and staged changes. Do not broaden scope or rewrite Git history without an explicit request.
- Routes carry typed identifiers/arguments only—never domain entities or `ViewState`.
- ViewModels receive constructor dependencies and never resolve Koin directly.
- Do not add parallel navigation, DI, networking, serialization, state-management, or persistence stacks.
- Mobile clients talk only to the Transit BFF; provider secrets and provider-specific contracts never enter the app.
- All production SDKs must be free for production use. Map SDK, tiles, APIs, attribution, quotas, and licenses require an explicit review.
- Architecture-changing decisions require an ADR under `docs/adr`.

## Request routes

| request | read |
| --- | --- |
| Screen/UI | [.agents/rules/compose.md](.agents/rules/compose.md), [presentation/navigation](.agents/docs/03-presentation-navigation.md) |
| Domain/data/network | [boundaries](.agents/docs/02-architecture-boundaries.md), [data/BFF](.agents/docs/04-data-bff.md) |
| Android adapter | [boundaries](.agents/docs/02-architecture-boundaries.md), [Android](.agents/docs/05-platform-android.md) |
| iOS adapter | [boundaries](.agents/docs/02-architecture-boundaries.md), [iOS](.agents/docs/06-platform-ios.md) |
| Dependency/toolchain | [toolchain](.agents/docs/01-stack-toolchain.md), [libraries/licenses](.agents/docs/08-libraries-licenses.md) |
| Tests/verification | [testing](.agents/docs/07-testing-verification.md) |

