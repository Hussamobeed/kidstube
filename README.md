# KidsTube

KidsTube is a simple Kotlin app for Android TV. It finds videos already stored on the TV and plays them locally. It does not stream videos.

## Watching videos

Use the TV remote to select a video. While it plays, open the on-screen controls with the directional pad to pause, seek, select the next or previous video, toggle **Repeat one**, or change **Autoplay**. Repeat one continuously plays the current video until turned off.

At the end of a video, KidsTube shows local-video cards and a Replay option. If Autoplay is on and there is another video in the library order, the next video starts after a 10-second countdown. Select **Cancel autoplay** to stay on the end screen. Autoplay stops at the end of the list; choose a card or Replay to continue. Autoplay settings are remembered on the device.

## App updates

From the home screen, choose **Check updates**. The app checks the latest public GitHub Release and downloads the APK only after you choose **Download and install**. Android opens its installer and may ask you to allow KidsTube to install updates. Confirm the install there. Video playback stays local; internet is used only for checking and downloading app updates.

## Releases

The GitHub Actions workflow builds a debug APK on pushes to `main`. To create a signed public release, push a version tag such as `v1.0.0`. The workflow builds the release APK and attaches `kidstube-release.apk` to that GitHub Release.

Before the first release, add these repository secrets under **Settings → Secrets and variables → Actions**:

- `ANDROID_KEYSTORE_BASE64`: Base64 text of the private `.jks` file.
- `ANDROID_KEYSTORE_PASSWORD`: Password for the key store and the `kidstube` key alias.

The signing keystore must stay private and be backed up securely. Never commit it to this repository. Every future release must use the same keystore and password or Android will refuse to update the installed app.

## Build

GitHub Actions uses Java 17, Android SDK 34, and Gradle 8.11.1. The project uses a system-installed Gradle in CI because no Gradle Wrapper was present in the starter project.
