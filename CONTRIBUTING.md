# Contributing to MassDroid

Thanks for helping. This page explains how to get a change into MassDroid with as little back and forth as possible.

## Branches

All development happens on the `dev` branch. Fork the repository, create your branch from `dev`, and open your pull request against `dev`.

The `main` branch only receives releases. GitHub opens a pull request against `main` by default, so change the base branch to `dev` before you create it.

## Building

The project needs Java 17 or newer (CI uses 21), the Android SDK and NDK 27.3, because the `:core` module builds native code. The phone app is the `:app` module and the Android TV app is the `:atv` module. Both depend on `:core`.

## Before Opening a Pull Request

Run these from the repository root and make sure they pass:

```bash
./gradlew detekt
./gradlew :core:testDebugUnitTest
```

Then build the app you changed:

```bash
./gradlew :app:assembleGithubDebug   # phone
./gradlew :atv:assembleGithubDebug   # Android TV
```

CI runs the same checks, plus Android Lint on every module. The full list of tasks is in `.github/workflows/build.yml`.

## Pull Request Description

Say what the change does and why. Then say what you tested it on: the device or emulator, its Android version, and the Music Assistant server version.

## Release Notes

Add one short bullet to `WHATSNEW.md` under `### Added`, `### Improved` or `### Fixed`. The app shows this file to users after an update, and it is also the release page text, so describe what the user gets, not how it works. For example, write "Android TV: smoother, more reliable mini player." and not a list of the classes you changed.

## Dependencies, Permissions and Network Hosts

Open an issue and ask first before you add a library, an Android permission, or a connection to a new network host. The F-Droid build must stay free of non-free libraries, such as Google Play services, Firebase or any analytics or crash reporting SDK, and a new permission or network host has to be listed in the app's F-Droid description (`app/fastlane/metadata/android/en-US/full_description.txt` for the phone, `atv/fastlane/metadata/android/en-US/full_description.txt` for Android TV).

## Scope

Keep one feature or one fix per pull request. Smaller pull requests are reviewed and merged sooner.
