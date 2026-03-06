# MA API Deep Dive (Local api-docs)

Last updated: 2026-03-05
Source of truth:
- https://mass.asksakis.net/api-docs
- https://mass.asksakis.net/api-docs/commands
- https://mass.asksakis.net/api-docs/schemas

## What we verified from official docs

- `music/artists/get` args: `item_id`, `provider_instance_id_or_domain`
- `music/tracks/get` args: `item_id`, `provider_instance_id_or_domain`, `recursive`, `album_uri`
- `music/favorites/add_item` args: `item` (uri/media object)
- `music/favorites/remove_item` args: `media_type`, `library_item_id`
- `player_queues/play_media` args: `queue_id`, `media`, `option`, `radio_mode`, `start_item`, `username`
- `player_queues/items` returns `QueueItem[]`, where `media_item` is `Track`

Schema notes:
- `Track.metadata.genres` is optional (`nullable`).
- `Artist.metadata.genres` is optional (`nullable`).
- `QueueItem.media_item` is `Track`, so genres may exist there when provider/metadata has them.

## Why some plays are recorded with 0 genres

Observed behavior (example: "Disco Balls"):
- Play history logged `0 genres`.
- MA can return tracks/artists with missing `metadata.genres` depending on provider/metadata availability.

Root cause in app flow (fixed now):
- In `PlayerRepositoryImpl.trackPlayHistory`, new tracked `Track` used mostly artist-cache genres.
- It did **not** reliably keep `currentItem.mediaItem.metadata.genres` at track start.
- If artist genre fetch also returns empty, play is recorded with 0 genres.

## Fix applied

File:
- `app/src/main/java/net/asksakis/massdroidv2/data/repository/PlayerRepositoryImpl.kt`

Changes:
- Seed tracked track genres from `mediaItem.metadata.genres` first.
- Merge with cached artist genres.
- During async enrichment, preserve already-known track genres when merging fetched data.

Result:
- If MA sends track-level genres, we keep them immediately.
- Artist enrichment still adds extra genres when available.

## Practical implication

- If MA truly returns no genres for both track and artist metadata, 0 genres is still expected.
- But now we no longer lose genres that were already present on the queue item.

## Live API evidence (2026-03-05)

Authenticated local calls against `https://mass.asksakis.net/api` confirmed:
- `POST /api` without bearer token returns `401 Authentication required`.
- Current active queue item can have `current_item.media_item.metadata.genres = []`.
- `music/tracks/get` for `Disco Balls` (`item_id=2661022692`, provider `deezer--GWnPbDSt`) returns `metadata.genres = null`.
- `music/artists/get` for artist `Møme` (`item_id=5542423`, same provider) returns `metadata.genres = null`.
- `music/refresh_item` on that track URI succeeds but genres remain null/empty after re-fetch.

This means the missing genres are not an app parsing bug for this item; they are missing upstream in MA/provider metadata for this track/artist.

## Query templates used

```bash
# 1) Login (REST docs flow)
curl -X POST "$MA_URL/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"credentials":{"username":"<user>","password":"<pass>"}}'
```

```bash
# 2) Current active queue for a player
curl -X POST "$MA_URL/api" \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"command":"player_queues/get_active_queue","args":{"player_id":"<player_id>"}}'
```

```bash
# 3) Track details
curl -X POST "$MA_URL/api" \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"command":"music/tracks/get","args":{"item_id":"<track_id>","provider_instance_id_or_domain":"<provider>"}}'
```

```bash
# 4) Artist details
curl -X POST "$MA_URL/api" \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"command":"music/artists/get","args":{"item_id":"<artist_id>","provider_instance_id_or_domain":"<provider>"}}'
```

```bash
# 5) Force refresh then re-check track
curl -X POST "$MA_URL/api" \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"command":"music/refresh_item","args":{"media_item":"<track_uri>"}}'
```
