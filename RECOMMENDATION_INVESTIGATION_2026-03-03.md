# Recommendation Investigation (Recent Plays)

Date: 2026-03-03  
Project: MassDroid (`/home/sfortis/work/massdroid-native`)

## Scope

Investigation on what recommendations can be generated from the local play-history database, using recent listening behavior.

Data source analyzed:
- `/tmp/massdroid-db-dump/massdroid.db` (dumped from app DB on device)

Main tables used:
- `play_history`
- `tracks`
- `albums`
- `artists`
- `track_artists`
- `track_genres`

## Data Snapshot

- Plays: **196**
- Time range (Europe/Athens): **2026-03-02 08:19** -> **2026-03-03 07:20**
- Unique played tracks: **189**
- Unique artists in plays: **125**
- Unique albums in plays: **167**
- Unique genres in plays: **13**
- Active queue IDs observed: **4**

Per-queue play counts:
- `ma_yxdjt16w8j`: 77
- `d0b155f3-417b-44ce-9e79-c921254bcb59`: 61
- `RINCON_F0F6C15D8BA601400`: 45
- `c39e5072-adfa-c148-6f7f-80e12af79fe2`: 13

## Queries Used

Core SQL queries used for the investigation.

1. Global counts and time range:

```sql
SELECT COUNT(*) AS plays,
       MIN(played_at) AS min_played_at,
       MAX(played_at) AS max_played_at
FROM play_history;
```

2. Uniques (tracks/artists/albums/genres):

```sql
SELECT
  (SELECT COUNT(DISTINCT ph.track_uri) FROM play_history ph) AS unique_tracks,
  (SELECT COUNT(DISTINCT ta.artist_uri)
   FROM play_history ph
   JOIN track_artists ta ON ta.track_uri = ph.track_uri) AS unique_artists,
  (SELECT COUNT(DISTINCT t.album_uri)
   FROM play_history ph
   JOIN tracks t ON t.uri = ph.track_uri
   WHERE t.album_uri IS NOT NULL) AS unique_albums,
  (SELECT COUNT(DISTINCT tg.genre_name)
   FROM play_history ph
   JOIN track_genres tg ON tg.track_uri = ph.track_uri) AS unique_genres;
```

3. Per-queue play distribution:

```sql
SELECT queue_id, COUNT(*) AS plays
FROM play_history
GROUP BY queue_id
ORDER BY plays DESC;
```

4. Top artists by play count + listened minutes:

```sql
SELECT a.name,
       COUNT(*) AS plays,
       ROUND(SUM(COALESCE(ph.listened_ms, 0)) / 60000.0, 1) AS listened_min,
       MAX(ph.played_at) AS last_played
FROM play_history ph
JOIN track_artists ta ON ta.track_uri = ph.track_uri
JOIN artists a ON a.uri = ta.artist_uri
GROUP BY a.uri, a.name
ORDER BY plays DESC, listened_min DESC
LIMIT 12;
```

5. Top genres by play count + listened minutes:

```sql
SELECT tg.genre_name,
       COUNT(*) AS plays,
       ROUND(SUM(COALESCE(ph.listened_ms, 0)) / 60000.0, 1) AS listened_min,
       MAX(ph.played_at) AS last_played
FROM play_history ph
JOIN track_genres tg ON tg.track_uri = ph.track_uri
GROUP BY tg.genre_name
ORDER BY plays DESC, listened_min DESC
LIMIT 12;
```

6. Top albums by play count:

```sql
SELECT al.name,
       COUNT(*) AS plays,
       ROUND(SUM(COALESCE(ph.listened_ms, 0)) / 60000.0, 1) AS listened_min,
       MAX(ph.played_at) AS last_played
FROM play_history ph
JOIN tracks t ON t.uri = ph.track_uri
JOIN albums al ON al.uri = t.album_uri
GROUP BY al.uri, al.name
ORDER BY plays DESC, listened_min DESC
LIMIT 12;
```

7. Repeat behavior (tracks played more than once):

```sql
WITH track_counts AS (
  SELECT track_uri, COUNT(*) AS c
  FROM play_history
  GROUP BY track_uri
)
SELECT
  SUM(CASE WHEN c > 1 THEN 1 ELSE 0 END) AS repeated_tracks,
  ROUND(SUM(CASE WHEN c > 1 THEN c ELSE 0 END) * 1.0 /
        (SELECT COUNT(*) FROM play_history), 3) AS repeat_play_share
FROM track_counts;
```

8. Recent-window genres (example: last 3h from latest play):

```sql
WITH bounds AS (
  SELECT MAX(played_at) AS max_ts FROM play_history
)
SELECT tg.genre_name, COUNT(*) AS plays
FROM play_history ph
JOIN track_genres tg ON tg.track_uri = ph.track_uri
JOIN bounds b
WHERE ph.played_at >= b.max_ts - (3 * 60 * 60 * 1000)
GROUP BY tg.genre_name
ORDER BY plays DESC
LIMIT 10;
```

9. Data-quality outliers (`listened_ms` much higher than duration):

```sql
SELECT COUNT(*) AS plays_over_1_5x_duration
FROM play_history ph
JOIN tracks t ON t.uri = ph.track_uri
WHERE t.duration IS NOT NULL
  AND t.duration > 0
  AND ph.listened_ms > t.duration * 1000 * 1.5;
```

10. DB integrity sanity checks:

```sql
SELECT COUNT(*) AS unplayed_tracks_in_db
FROM tracks t
LEFT JOIN play_history ph ON ph.track_uri = t.uri
WHERE ph.track_uri IS NULL;

SELECT COUNT(*) AS albums_without_tracks
FROM albums a
LEFT JOIN tracks t ON t.album_uri = a.uri
WHERE t.uri IS NULL;
```

MA WebSocket probe used for BPM/tempo validation:
- Login: `auth/login`, then `auth`
- Track metadata call: `music/tracks/get { item_id, provider_instance_id_or_domain, lazy=false }`
- Checked keys:
  - top-level: `bpm`, `tempo`
  - `metadata`: `bpm`, `tempo`

## Behavioral Findings

Top artists by recent history:
- Cullen Omori
- Zagar
- Is Tropical
- Cage The Elephant

Top genres by recent history:
- Synthpop
- Alternative Rock
- Electronic
- Ambient

Short-window listening mode:
- Last 3h/6h window is dominated by **Ambient + Electronic**.

Repeat profile:
- Low repeat tendency: only **7 tracks** repeated (out of 189 unique).
- This supports diversity-aware ranking (avoid over-repeating same artist/track).

Daypart signal (from available sample):
- Morning: Alternative Rock / Ambient / Electronic
- Day: Indie Pop / Alternative Rock
- Evening: Synthpop / Indie Rock / Rock

## Recommendation Families We Can Generate From This DB

1. Continue Current Vibe (session-aware)
- Weight last 3h/6h heavily.
- Good for immediate next-track/next-album suggestions.

2. Albums You Might Like (history-driven)
- BLL recency scoring (artists + genres), then MMR rerank.
- Diversity cap already supported in app logic (now max 1 album per artist).

3. Rediscovery
- High-affinity items excluded from very recent window.
- Example rule: score high but not played in last 12h or last N tracks.

4. Per-Player Recommendations
- Build separate taste vectors by `queue_id`.
- Useful because queue IDs show different listening distributions.

5. Time-of-Day Recommendations
- Different priors by morning/day/evening patterns.

6. Exploration Layer
- Adjacent-genre suggestions from transition/co-occurrence patterns.
- Keep small exploration budget (for novelty without losing relevance).

## Example Candidate Outputs (from investigation scoring run)

Track candidates (BLL affinity, excluding very recent set):
- Sunshine (Submotion Orchestra)
- Ao (Submotion Orchestra)
- Lonely Dancer (Tricky)
- The Sorrow Tree (Moby)
- Pay My Debts (Beacon)

Album candidates (affinity + recency filters):
- Sunshine
- 20,20
- The Sorrow Tree (Remixes)
- Forever Young
- Junior

## Data Quality Notes

Observed outliers in `listened_ms` where listen duration is much larger than track duration.

Recommendation:
- Clamp listened contribution for scoring, e.g.:
  - `effective_listened_ms = min(listened_ms, duration_ms * 1.2)`
- Keep raw value for telemetry/debug if needed.

## BPM / Tempo Availability Check

Live read-only MA WebSocket checks were executed against sampled recent tracks (`music/tracks/get`).

Result:
- No `bpm` or `tempo` fields found (neither top-level nor inside `metadata`) in sampled provider responses.
- Sampled providers:
  - `deezer--GWnPbDSt` (39 tracks): 0 with bpm/tempo
  - `library` (1 track): 0 with bpm/tempo

Conclusion:
- Current provider data in this setup does **not** provide BPM directly.
- If BPM-based recommendations are needed, use hybrid approach:
  - metadata-first when available
  - audio-analysis fallback + local cache otherwise

## Practical Implementation Plan

1. Keep current BLL + MMR pipeline for `Albums You Might Like`.
2. Add session-aware mode (`last_3h`, `last_6h`) for Home recommendations.
3. Add rediscovery rule (`not_in_last_12h` + affinity threshold).
4. Add per-queue scoring profile keyed by `queue_id`.
5. Clamp `listened_ms` in scoring features to handle outliers.
6. Optionally add BPM cache table for future audio-analysis fallback.
