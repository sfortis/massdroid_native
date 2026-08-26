# Configuration

## Server Connection

1. Open MassDroid and go to **Settings**.
2. Enter your Music Assistant server URL, for example `http://192.168.1.100:8095`.
3. Log in with your Music Assistant credentials.
4. Your players will appear on the Home screen.

For remote access with mTLS, install a client certificate on your device and select it in Settings. The app will use it for both WebSocket and image connections.

## Metadata Enrichment

Genres, artist biographies, album descriptions, and release years come from your Music Assistant providers first, and from [MusicBrainz](https://musicbrainz.org/) when a provider has nothing to offer. There is no API key to obtain and nothing to configure: enrichment runs in the background on its own and caches its results on the device.

Progress is shown in **Settings** for as long as there is work outstanding.
