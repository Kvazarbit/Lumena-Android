# Lumena Android

Experimental Android co-pilot / computer-use client built with Kotlin + Jetpack Compose.

## v0.1 architecture

`Screen -> Accessibility UI tree -> ScreenSnapshot -> Planner -> ActionGate -> Executor -> Verification`

### Included
- Jetpack Compose shell
- AccessibilityService-based visible UI reader
- structured screen snapshot
- basic click / text-input executor
- explicit action gate
- user-facing accessibility setup

### Safety and control
Lumena is designed as a co-pilot, not a hidden automation layer. The app does not attempt to bypass Android security, banking protections, DRM, app authentication, or server-side model safeguards. Actions that can change another app should require user confirmation.

## Planned
- streaming AI chat transport
- screenshot capture with MediaProjection
- planner JSON schema
- action verification loop
- app launcher/router
- voice control
- notification agent
- local memory/presets
- GitHub Actions APK build

## Build
Open in Android Studio with JDK 17 and Android SDK 35.
