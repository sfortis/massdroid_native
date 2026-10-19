### Added
- Android TV: rename the TV's player from Settings.
- Android TV: the app tells you when a new version is out and installs it, like the phone app.

### Improved
- Android TV: a slimmer mini player, and the full player opens in one smooth step.
- Android TV: the output delay in the full player opens in its own window, with a slider you can see and a Reset you can reach.

### Fixed
- Android TV: the remote moves between the login fields, and a mouse click works on every button. (#79)
- Android TV: Home Assistant and other apps no longer see the TV as paused when nothing is playing.
- Android TV: pressing right at the end of a row on Home no longer jumps to the top of the screen.
- The in-app updater installed the F-Droid build instead of the regular one, and the F-Droid build never offers updates. If your app stopped offering updates, install the regular APK from the release page once; your settings are kept. (#78)

### F-Droid
- The F-Droid build has no in-app updates and is meant for the F-Droid store. The F-Droid submission is in progress, so MassDroid is not there yet.
- The F-Droid and Android TV files on the release page now end in _fdroid.apk and _tv.apk. If Obtainium follows one of them with a filter, check that the filter still matches.
