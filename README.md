# <img src="assets/logo.png" width="40" alt="MassDroid" /> MassDroid

Native Android client for [Music Assistant](https://music-assistant.io/), the open-source music server that integrates all your music sources and players.

MassDroid is a full-featured Music Assistant companion app built around music exploration and discovery. It gives you complete remote control over all your MA players while also learning from your listening habits to surface personalized recommendations, generating Smart Mix playlists and genre radio stations entirely on-device, enriching your library with metadata from Last.fm, and helping you discover similar artists across all your music providers.

## What's New ![NEW](https://img.shields.io/badge/-NEW-brightgreen)

- Proximity Playback: BLE fingerprint room detection with automatic speaker transfer
- Per-room configuration: assign playlist, volume level, and shuffle per room
- Time schedule: set active days and hours for proximity detection
- Calibration wizard with quality assessment (Good/Weak indicators)
- Per-room calibrate button for single-room re-training
- Playlist management: create, delete, add/remove tracks with checkmark indicators
- Transfer queue from NowPlaying player options menu
- Smart Mix and Genre Radio exposed in MediaBrowser for external controllers
- Last.fm enricher syncs all library artists, periodic background enrichment with progress
- BLL scoring: session diminishing returns prevents album binges from dominating scores
- Fix audio focus steal on app startup (other media apps no longer pause)
- Fix startup flash of disconnected empty state

## Screenshots

<p align="center">
  <img src="screenshots/home.png" width="260" />&nbsp;&nbsp;
  <img src="screenshots/nowplaying.png" width="260" />&nbsp;&nbsp;
  <img src="screenshots/library_providers.png" width="260" />
</p>
<p align="center">
  <img src="screenshots/search_providers.png" width="260" />&nbsp;&nbsp;
  <img src="screenshots/players.png" width="260" />&nbsp;&nbsp;
  <img src="screenshots/artist_detail.png" width="260" />
</p>
<p align="center">
  <img src="screenshots/album_detail.png" width="260" />&nbsp;&nbsp;
  <img src="screenshots/proximity_settings.png" width="260" />&nbsp;&nbsp;
  <img src="screenshots/room_setup.png" width="260" />
</p>

## Exploration & Discovery

- **Similar Artists** : Open any artist and see related artists from Last.fm, resolved across all your music providers. Genre validation ensures name collisions are filtered out. Results are cached locally for fast repeat visits.
- **Last.fm Enrichment** : Artist bios, album descriptions, genres, and release years are pulled from Last.fm when your music provider lacks the data. Everything is cached and reused across the app.
- **Smart Mix** : One tap, instant playlist. The on-device recommendation engine scores artists by recent listening, weighs genre affinity and time-of-day patterns, then builds a queue that fits your current mood. Tracks are interleaved so the same artist never plays back-to-back.
- **Genre Radio** : Pick a genre chip on the Discover screen and get a curated playlist. Artist selection is weighted by your play history to keep the mix personal and fresh.
- **Smart Listening** : Runs silently in the background. Every play, skip, like, and unlike trains a per-artist preference model that decays over 60 days, so the engine adapts as your taste evolves.
- **Recommendation Insights** : View your top artists, albums, and genres, plus manage blocked artists from Settings.

## Proximity Playback

Walk between rooms and your music follows you. MassDroid uses BLE fingerprinting to detect which room you are in and automatically transfer playback or notify you to switch speakers.

- **BLE Room Detection** : Scans nearby Bluetooth devices (TVs, speakers, routers, IoT) and matches their signal pattern against calibrated room fingerprints using k-NN classification.
- **Per-Room Configuration** : Assign a Music Assistant player to each room, set a preferred playlist for auto-play, configure volume level, and toggle shuffle.
- **Calibration Wizard** : Walk through each room while the app collects 10 BLE scans, builds weighted fingerprints, and computes beacon profiles with quality assessment (Good/Weak).
- **Time Schedule** : Set active days and hours so proximity detection only runs when you want it.
- **Auto-Transfer** : Optionally transfer the queue automatically without notification when you change rooms.
- **Screen-Off Detection** : Works with screen off using PendingIntent BLE scans (OS-managed, no wake locks).
- **Motion Gating** : Sensor hub (significant motion + step detector) triggers scans only when you are actually moving, saving battery.

Requires Android 12+ with Bluetooth support.

## Recommendation Engine

MassDroid includes a local recommendation engine that learns your listening habits and generates personalized content.

- **BLL Temporal Decay** : Recent plays weigh dramatically more than older ones, even if the old track was played many times.
- **MMR Re-ranking** : Prevents genre clustering by penalizing items too similar to already-selected ones.
- **Genre Adjacency** : Built from co-occurrence in your play history to discover genres you might enjoy.
- **Exploration Budget** : 70% top matches, 20% adjacent genres, 10% wildcard for serendipitous discovery.
- **Last.fm Genre Fallback** : When your music provider has no genre data, the app queries Last.fm artist tags (optional, cached locally for 30 days). Enriched genres are used across the entire app: recommendations, Smart Mix, genre radio, and library search.

All recommendation data stays on-device in a local Room database. Nothing is sent to external services.

## Features

- **Discover Home** : Dynamic recommendation sections with recently played, top picks, genre radio, and Smart Mix
- **Library Browsing** : Artists, Albums, Tracks, Playlists, Radio, and Browse with search, sort, grid/list views, and provider filtering. Genre-based search finds artists, albums, and tracks by genre when your library has been enriched with Last.fm tags.
- **Artist & Album Detail** : Rich detail views with descriptions, genres, similar artists, and now-playing indicators
- **Player Controls** : Play, pause, skip, seek, volume, shuffle, repeat across all MA players
- **Now Playing** : Full-screen player with album art, seek bar, favorite toggle, lyrics, and artist blocking
- **Queue Management** : View, drag-to-reorder, transfer between players, and manage the playback queue with action sheets
- **Favorites** : Mark artists, albums, tracks, and playlists as favorites, filter library by favorites
- **Phone as Speaker** : Sendspin protocol turns your phone into a Music Assistant player. Audio streams as Opus frames over WebSocket, decoded and played through your phone speaker or headphones.
- **Proximity Playback** : BLE fingerprint-based room detection with auto-transfer, per-room playlists, volume, and scheduling
- **Artist Blocking** : Block any artist from all recommendations, radio stations, and Smart Mix results
- **Media Session** : Android media notification with playback controls
- **Player Settings** : Rename players, set icons, configure crossfade and volume normalization
- **Connection Diagnostics** : Live latency graph with roundtrip stats and server version info
- **mTLS Support** : Client certificate authentication for secure remote access
- **MiniPlayer** : Persistent mini player bar across all screens

## Tech Stack

- Kotlin, Jetpack Compose, Material 3
- MVVM, Hilt, Coroutines/Flow
- OkHttp WebSocket, kotlinx.serialization
- Media3 / MediaSession
- Room (local recommendation database)

## How It Works

MassDroid communicates with your Music Assistant server over a persistent WebSocket connection. All player state, library data, queue changes, and favorites are synced in real time through server-pushed events. The app never polls; updates appear instantly as they happen on the server or from other clients.

When Sendspin is enabled, the phone registers as a Music Assistant player. Audio is streamed as Opus frames over a second WebSocket, decoded on-device, and played through the phone speaker or headphones.

## Requirements

- Android 8.0+ (API 26)
- A running [Music Assistant](https://music-assistant.io/) server (v2.x)
- Android 12+ for Proximity Playback (BLE scanning)

## Configuration

### Server connection

1. Open MassDroid and go to **Settings**
2. Enter your Music Assistant server URL (e.g. `http://192.168.1.100:8095`)
3. Log in with your Music Assistant credentials
4. Your players will appear on the Home screen

For remote access with mTLS, install a client certificate on your device and select it in Settings. The app will use it for both WebSocket and image connections.

### Last.fm API key (strongly recommended)

Most of the discovery and enrichment features rely on the [Last.fm](https://www.last.fm/api) API: similar artists, artist bios, album descriptions, genre tags, and release years. Data is only fetched when your music provider lacks the information, and all results are cached locally.

To set it up:

1. Create a free [Last.fm API account](https://www.last.fm/api/account/create) and get your API key
2. Go to **Settings** in MassDroid and enter the key in the **Last.fm API Key** field

Without it the core player and library features work fine, but you will miss out on similar artists, bios, and genre enrichment for Smart Mix and Genre Radio.

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.
