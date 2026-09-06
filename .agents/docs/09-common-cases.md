# Common implementation recipes

For a screen: define State/Action/Effect, domain contract, optional mapper, ViewModel, stateless Content, preview, route entry, stable IDs, tests, then both platform checks.

For a platform adapter: define a narrow common value/interface, implement actual/native code at the platform boundary, inject it at bootstrap, and test mapping/lifecycle behavior.

For a dependency: update the version catalog, record why it is needed and the license/cost decision, compile Android and iOS, and add an ADR when introducing or replacing an architectural stack.

