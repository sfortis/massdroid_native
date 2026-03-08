# <img src="assets/logo.png" width="40" alt="MassDroid" /> MassDroid

Native Android client for [Music Assistant](https://music-assistant.io/), the open-source music server that integrates all your music sources and players.

MassDroid lets you control your Music Assistant players, browse your library, manage queues, and even turn your phone into a speaker.

## Screenshots

<p align="center">
  <img src="screenshots/home.png" width="180" />&nbsp;&nbsp;
  <img src="screenshots/nowplaying.png" width="180" />&nbsp;&nbsp;
  <img src="screenshots/library.png" width="180" />
  <br/><br/>
  <img src="screenshots/artist_detail.png" width="180" />&nbsp;&nbsp;
  <img src="screenshots/player_settings.png" width="180" />
</p>

## Smart Mix & Recommendation Engine

MassDroid includes a local recommendation engine that learns your listening habits and generates personalized content.

- **Smart Mix** : One-tap playlist generation. Combines artist scoring, genre affinity, and time-of-day patterns to build a queue tailored to your current mood. Interleaves tracks to avoid artist repetition.
- **Genre Radio** : Pick a genre and get a radio-style stream. Uses BLL-weighted artist selection with batch-based pool strategy and decade constraints for variety.
- **Smart Listening** : Tracks play, skip, like, and unlike signals per artist. Preferences decay naturally over 60 days so the engine adapts as your taste evolves.
- **Artist Blocking** : Suppress specific artists from all recommendations and radio stations.
- **Recommendation Insights** : View your top artists, albums, and genres, plus manage blocked artists from Settings.

All recommendation data stays on-device in a local Room database. Nothing is sent to external services.

## Features

- **Player Controls** : Play, pause, skip, seek, volume, shuffle, repeat across all MA players
- **Discover Home** : Dynamic recommendation sections with recently played, top picks, genre radio, and Smart Mix
- **Library Browsing** : Artists, Albums, Tracks, Playlists with search, sort, and grid/list views
- **Now Playing** : Full-screen player with album art, seek bar, favorite toggle, and artist blocking
- **Queue Management** : View, reorder, transfer, and manage the playback queue with action sheets
- **Phone as Speaker** : Turn your phone into a Music Assistant speaker using the Sendspin protocol (Opus audio streaming)
- **Favorites** : Mark artists, albums, tracks, and playlists as favorites, filter library by favorites
- **Media Session** : Android media notification with playback controls, Android Auto and car display support
- **Player Settings** : Rename players, set icons, configure crossfade and volume normalization
- **Connection Diagnostics** : Real-time latency probing and connection health monitoring
- **mTLS Support** : Client certificate authentication for secure remote access
- **MiniPlayer** : Persistent mini player bar across all screens

## Tech Stack

- Kotlin, Jetpack Compose, Material 3
- MVVM, Hilt, Coroutines/Flow
- OkHttp WebSocket, kotlinx.serialization
- Media3 / MediaSession

## How It Works

MassDroid communicates with your Music Assistant server over a persistent WebSocket connection. All player state, library data, queue changes, and favorites are synced in real time through server-pushed events. The app never polls; updates appear instantly as they happen on the server or from other clients.

When Sendspin is enabled, the phone registers as a Music Assistant player. Audio is streamed as Opus frames over a second WebSocket, decoded on-device, and played through the phone speaker or headphones.

## Requirements

- Android 8.0+ (API 26)
- A running [Music Assistant](https://music-assistant.io/) server (v2.x)

## Building

```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease

# Run static analysis
./gradlew detekt
```

## Connecting

1. Open MassDroid and go to **Settings**
2. Enter your Music Assistant server URL (e.g. `http://192.168.1.100:8095`)
3. Log in with your Music Assistant credentials
4. Your players will appear on the Home screen

For remote access, you can configure mTLS with a client certificate in Settings.

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.
