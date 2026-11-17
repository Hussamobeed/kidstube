# Release and signing notes

- Release tags use `vMAJOR.MINOR.PATCH`, such as `v1.0.0`.
- The workflow converts the version into a rising Android `versionCode` and signs each release APK using the same `kidstube` key alias and repository secrets.
- Keep a private backup of the JKS keystore and its password. If the key is lost or replaced, already-installed copies cannot accept later APK updates.
- Do not commit any keystore, password, or base64 keystore text. `.gitignore` excludes common signing files and local working secrets.
- In the app, the update checker accepts only the `kidstube-release.apk` asset from this repository, and it checks package name, version code, and signing certificate before opening Android's installer.
- A release is installed only after a person confirms it in Android's package installer. The app cannot silently replace itself.
