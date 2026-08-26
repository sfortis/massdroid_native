# Recommendation Engine

MassDroid includes a local recommendation engine that learns your listening habits and generates personalized content.

## Exploration & Discovery

- **Similar Artists**: open any artist and see related artists, resolved across all your music providers.
- **Metadata Enrichment**: artist bios, album descriptions, genres, and release years are pulled from MusicBrainz when your music provider lacks the data. No API key is needed.
- **Smart Mix**: one tap builds a queue based on recent listening, genre affinity, and time-of-day patterns.
- **Genre Radio**: pick a genre chip on the Discover screen and get a curated playlist weighted by your play history.
- **Smart Listening**: every play, skip, like, and unlike trains a per-artist preference model that adapts as your taste evolves.
- **Recommendation Insights**: view your top artists, albums, and genres, plus manage blocked artists from Settings.

## How It Scores Music

- **BLL Temporal Decay**: recent plays weigh more than older ones.
- **MMR Re-ranking**: prevents genre clustering by penalizing items too similar to already-selected ones.
- **Genre Adjacency**: discovers genres you might enjoy from co-occurrence in your play history.
- **Exploration Budget**: balances top matches, adjacent genres, and wildcard discovery.
- **Genre Fallback**: when your provider has no genre data, MusicBrainz tags enrich recommendations, Smart Mix, genre radio, and library search.

All recommendation data stays on-device in a local Room database. Nothing is sent to external services.
