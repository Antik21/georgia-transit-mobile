# Android host

`androidApp` owns the Activity, manifest, Android SDK config, application ID, and future permission/location/map adapters. Shared Compose owns product UI/navigation/state.

Android framework types stay in `androidMain` or `androidApp`. Map/location adapters implement narrow common contracts. Automation enables Compose test tags as Android resource IDs. Test only on an explicit emulator ID; never silently target a connected physical device.

