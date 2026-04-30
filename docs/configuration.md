# Configuration

## Server Connection

1. Open MassDroid and go to **Settings**.
2. Enter your Music Assistant server URL, for example `http://192.168.1.100:8095`.
3. Log in with your Music Assistant credentials.
4. Your players will appear on the Home screen.

For remote access with mTLS, install a client certificate on your device and select it in Settings. The app will use it for both WebSocket and image connections.

## Last.fm API Key

Most discovery and enrichment features rely on the [Last.fm](https://www.last.fm/api) API: similar artists, artist bios, album descriptions, genre tags, and release years.

Data is fetched only when your music provider lacks the information, and results are cached locally.

1. Create a free [Last.fm API account](https://www.last.fm/api/account/create) and get your API key.
2. Go to **Settings** in MassDroid.
3. Enter the key in the **Last.fm API Key** field.

Without it, core player and library features still work, but similar artists, bios, and genre enrichment for Smart Mix and Genre Radio are limited.
