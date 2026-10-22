# Privacy Policy

Effective date: October 21, 2026

MassDroid is an open-source Android app for controlling a Music Assistant server. It is available
for phones, Android TV and Android Automotive OS. This policy describes what the app does with
your data. In short, the developer does not collect any data, and the app has no analytics, no
advertising and no crash reporting.

## Who runs the app

MassDroid is developed by Dionysis Fortis as a personal open-source project. The source code is
public at https://github.com/sfortis/massdroid_native.

## Data the developer collects

None. The app does not send any data to the developer or to any server run by the developer.

## Where the app connects

The app connects to the following services, and to nothing else.

- **Your Music Assistant server.** The app connects to the server address you enter, signs in with
  the credentials you provide, and exchanges with it everything it needs to browse your library
  and control playback. This server belongs to you or to whoever runs it, and its own privacy
  terms apply to it.
- **MusicBrainz** (musicbrainz.org). To fill in missing genres and to find artist biographies, the
  app sends the names and MusicBrainz identifiers of artists: those in your library, and the
  artists of tracks you start playing, so MusicBrainz can see which artists you listen to and when.
  No account and no identifier of you or of your device is sent.
- **Wikipedia and Wikidata** (wikipedia.org, wikidata.org). On the phone's artist page, when Music
  Assistant has no biography for the artist, the app looks up the artist's Wikipedia summary. Only
  the artist reference is sent.
- **GitHub** (github.com), only in the build downloaded from GitHub. That build checks GitHub for a
  newer release and downloads it when you ask. The Google Play and F-Droid builds do not.
- **Image hosts.** Cover art is loaded through your Music Assistant server. When the server
  provides a direct image address from one of your music providers, the app loads the image from
  that address.

Each of these services receives your device's IP address as part of any internet request, under
its own privacy policy.

## Data stored on your device

The app keeps the following on your device: your server address, your sign-in token, and, when
you sign in with a username and password, both of them, so it can sign in again by itself; the key
that identifies this device as a Music Assistant player; your settings; your play history and the
preference scores it uses for recommendations; cached genres, biographies and artwork; when Follow
Me is on, the names and identifiers of the Wi-Fi networks and Bluetooth devices it uses to
recognize each room; and, when you turn on "Keep a log" in Settings, a diagnostic log of the
last day, which turning the setting off deletes.

The sign-in details and the player key are sent only to your own Music Assistant server, to sign
in and to register the device as a player. The diagnostic log leaves the device only when you
share it yourself from Settings. Android's own backup can copy the app's settings and its
listening history to your Google account or to a new device, depending on your device's backup
settings. Your Music Assistant password and access token, and the identity this device uses as a
speaker, are never included, so a restored device asks you to sign in again. Nothing else is sent
anywhere. Uninstalling the app or clearing its data removes it from the device.

## Permissions

- **Microphone** (optional). Used only by the sync measurements: the output calibration and Auto
  Sync. The sound is analyzed on the device and is never stored or uploaded.
- **Location, Bluetooth scanning and physical activity** (optional). Used only by
  Follow Me, which detects the room you are in from nearby Bluetooth and Wi-Fi signals and uses the
  step detector to know when you move. Everything is processed on the device. Follow Me is off
  until you turn it on. The room fingerprints described above stay on the device, and no location
  is sent anywhere.
- **NFC** (optional). Used to read and write NFC tags that start playback.
- **Notifications, foreground service and battery optimization.** Used to keep playback running
  and to show the playback controls.

Links you open from the app, such as this policy or the support page in the GitHub and F-Droid
builds, open in your browser.

## Children

The app is not directed at children and does not knowingly collect any data from anyone.

## Changes

If this policy changes, the new version will be published at this address with a new effective
date.

## Contact

Questions about this policy can be asked in the project's issue tracker:
https://github.com/sfortis/massdroid_native/issues
