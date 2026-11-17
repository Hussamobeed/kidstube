# KidsTube — Build Plan

## Goal
A simple Kotlin Android TV app for children that looks and feels like a familiar video home screen, but only lists and plays videos already stored on the TV or attached local storage. Videos never stream. Internet access is used only when a parent chooses to check or download an app update from GitHub Releases. The interface supports Arabic (RTL), English, and a TV remote.

## MVP decisions
- Native Android app, Kotlin, landscape-first, with Android TV launcher support.
- Local video discovery through Android MediaStore and the existing Android media permission flow.
- Playback with AndroidX Media3/ExoPlayer; playback controls remain available through the TV remote.
- English default strings plus Arabic translations selected by the device language; layout uses start/end so RTL mirrors naturally.
- Network access is limited to the parent-triggered GitHub release updater; video browsing and playback remain local. No accounts, analytics, or cloud video services.
- Keep the current simple XML/ViewBinding implementation rather than introducing Compose or extra architecture/dependencies.

## Build phases
1. **Complete the project foundation (done):** preserve the existing starter, add the missing themes/colors/drawables/strings, add Arabic resources, and make the app launch cleanly on Android TV.
2. **Polish local library browsing:** verify MediaStore behavior on current Android versions, show useful video thumbnails/titles/durations, and add an empty/loading/error state plus a refresh action.
3. **Polish playback and remote control:** verify play/pause, seek, next/previous, back navigation, and lifecycle/resource release on a TV device or emulator.
4. **Build and validate:** use GitHub Actions with Java 17, Android SDK 34, and Gradle 8.11.1; test and build a debug APK on `main`, then sign and publish APKs for `vMAJOR.MINOR.PATCH` tags with the same private key.
5. **Optional after MVP:** parent-controlled folder selection, favorites, and a simple playback timer/child lock—only after the core offline app works.

## Acceptance checks
- Internet access is used only for the GitHub release updater after a parent selects it; videos are played from local content URIs only.
- The app is discoverable in the Android TV launcher and usable without a touchscreen.
- Arabic text is translated and the UI direction mirrors appropriately; English titles remain readable.
- Empty library and denied-permission states explain what the parent can do.
- GitHub Actions builds the debug APK and a tagged signed release APK with the same stable signing key.

## Current status / next step
The workspace has Kotlin activities, a MediaStore-backed video query, a four-column video grid, and Media3 playback. The missing resources are now present, and the app has a bilingual GitHub update checker that asks before downloading and verifies package identity and signing certificate. The repository was confirmed empty, so a new RSA signing key was created locally and excluded from Git. The Android workflow, release signing, and updater are prepared locally, but the GitHub Actions secrets still need to be entered and the source still needs to be committed to the repository. This computer has Java 11 and no configured Android SDK or Gradle command, so the first APK build should run in GitHub Actions.
