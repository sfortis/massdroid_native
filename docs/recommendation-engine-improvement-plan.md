# Recommendation Engine Improvement Plan

## Purpose

Improve Smart Mix, Genre Radio, and Play Similar using the existing local
recommendation architecture before introducing LiteRT or another ML runtime.
The plan prioritizes recommendation correctness, measurable quality, stable
variety across process restarts, and predictable tuning without turning the
local database into an analytics warehouse.

## Goals

- Reduce repeated tracks, artists, and genre clusters across consecutive mixes.
- Prevent recommendations from teaching the engine that their own output is
  organic user preference.
- Update cooldown state only for tracks Music Assistant actually accepted.
- Make scoring consistent, explainable, testable, and ready for a future learned
  ranker.
- Measure recommendation quality locally using actual outcomes instead of only
  debug logs and manual listening checks.
- Keep all personal listening and recommendation data on-device.
- Preserve current hard exclusions, provider-agnostic identity handling, and
  queue replacement behavior.

## Non-goals

- Do not add LiteRT, a local LLM, or an embedding model in the first phases.
- Do not replace Music Assistant as the source of playable candidates.
- Do not store every candidate and every feature permanently.
- Do not train a model on-device before a reliable evaluation dataset exists.
- Do not replace hard safety rules such as blocked artists, disliked tracks,
  availability checks, deduplication, or per-artist/per-album caps with a model.
- Do not attempt to make Music Assistant requests work fully offline.

## Current Architecture

The current engine already contains most of the required layers:

1. Local Room history records plays, listened duration, likes, unlikes, skips,
   dislikes, track scores, artist scores, and genres.
2. `SeedTrackMixGenerator` retrieves candidates through Music Assistant
   `similar_artists` and `similar_tracks` routes.
3. `MixEngine` scores, samples, caps, deduplicates, and interleaves candidates.
4. `MixPlaybackOrchestrator` builds the mix, replaces the queue, salvages
   playable chunks when Music Assistant rejects a batch, and maintains recent
   mix cooldowns.
5. BLL temporal decay, completion weighting, recent taste, daypart affinity,
   explicit feedback, genre families, and discovery/variety/strictness controls
   already influence the result.

The plan extends these layers rather than introducing a parallel recommender.

## Current Gaps

### Recommendation output can train the engine itself

A track played from a generated queue can enter normal play history and later
become a seed. The confirmed-seed pool reduces this effect, but the database
does not explicitly distinguish a user-selected track from a Smart Mix result.

### Cooldown state is committed before queue acceptance

Recent tracks, artists, and genres are added before the queue salvage operation
has established which tracks Music Assistant accepted. Rejected tracks can
therefore be treated as recently recommended even though the user never had a
chance to hear them.

### Cooldown persistence is incomplete

Seed-cluster rotation survives process death, but recent track, artist, and
genre histories are in memory. An Android process restart can reset much of the
cross-mix repetition protection.

### Recommendation quality is not directly measurable

Play and feedback outcomes exist, but there is no durable connection between a
generated mix and the later listening outcome. There is also no record of queue
acceptance, source route, selected rank, or recommendation mode.

### Scoring semantics are distributed

Artist preference, recent preference, smart feedback, daypart, favorites,
route similarity, discovery sampling, and recency penalties are combined in
several places with different scales. This makes tuning harder and makes slider
behavior less predictable.

### Multi-route agreement is discarded (CLOSED 2026-08-23)

When both similarity routes returned the same track, route merging kept the
larger score and discarded the fact that two independent routes had agreed.

Closed: `mergeRoutes` now adds `ROUTE_AGREEMENT_BONUS` (0.12) to a candidate both
routes found. The bonus is deliberately smaller than the on-family/off-family gap,
so agreement lifts a candidate within its tier but cannot push an off-family track
past the on-family ones. Overlap is narrow in practice, 10 of 190 candidates on a
measured build, so this promotes a small well-supported set rather than reordering
the pool.

### Unsupported and empty provider responses cost repeated work (CLOSED 2026-08-23)

Non-empty results were cached but a valid empty or unsupported `similar_tracks`
response was not, so an unsupported provider was probed once per seed on every
build: eight wasted round-trips per mix for that user.

Closed: empty answers are cached too, with their own shorter TTL
(`MA_SIMILAR_TRACKS_EMPTY_TTL_MS`, 2 days against 14). The two cases mean
different things. A provider that does not implement the feature will still not
implement it in two days, while a provider that simply had nothing for one
particular track may well have something later.

### Genre adjacency is semantic, not personal

The current adjacency graph is derived from genre tags that co-occur on tracks
and artists. It is useful as catalog semantics, but it is not the same as genres
the listener moves between within real listening sessions.

**Measure before building this.** Co-occurrence signal on a real library was
measured on 2026-08-12 and it is weak: across 18 builds an average of 12
candidates appeared in two or more seeds' similar lists, and 1 in three or more.
A personal adjacency graph built on that risks having almost no edges. Establish
that enough session-level movement exists before committing to the structure.

## Measured context (2026-08-23)

Facts established on a real device and a live Music Assistant server. They change
how urgent parts of this plan are, so they belong next to the gaps.

### A server bug makes Phase 1 urgent rather than tidy

Music Assistant raises `ValueError: year must be in 1..9999, not 0` from
`MediaControllerBase.get_provider_item` when a track carries a zero year, and the
exception fails the WHOLE `player_queues/play_media` call. Measured on the owner's
server: **21 occurrences in 24 hours**. `deezer--GWnPbDSt://track/211798`
reproduces it through a plain `music/tracks/get_track`, so it is not something the
app causes or can cleanly avoid.

Consequence for this plan: every such failure currently writes 33 tracks into the
recent-track, recent-artist and recent-genre cooldowns for a mix the listener never
heard, and those tracks are then suppressed from future mixes. The gap named
"cooldown state is committed before queue acceptance" is therefore not a rare edge
case, it fires regularly. Worth reporting upstream as well.

### The engine changed after this plan was written

Two things shipped on 2026-08-12 (`ad1f7c7`, `be5d7ff`) that the architecture
section predates:

- Genre family became a ranking signal instead of a veto (`FamilyMatch` ON / OFF /
  UNKNOWN, off-family kept at 0.35). The old all-or-nothing gate was the main cause
  of off-genre mixes: half of 16 measured clusters had fewer than 10 same-family
  candidates for 33 slots, and one had none at all.
- Seeds sharing the primary's exact GENRE are now seated before seeds that only
  share its family. A post-rock anchor used to sit with seven indie seeds and
  produce 2 post-rock tracks out of 33; after the change a folk anchor reported
  `7 share its genre, 0 only its family` and 26 of 33 tracks carried a folk tag.

### Out of scope by decision

A country or language filter was considered (MusicBrainz exposes `area` and
`country` per artist, and a mix opened with a Québécoise artist for a listener who
dislikes French-language tracks) and was **explicitly ruled out** by the owner on
2026-08-23. Blocking artists individually remains the only tool. Do not reintroduce
it as a phase.

## Design Principles

- Separate candidate retrieval, hard filtering, relevance scoring, diversity
  selection, queue delivery, and outcome learning.
- Build a recommendation draft first; commit state only after queue delivery.
- Treat generated exposure as weaker evidence than organic user selection.
- Keep stored history bounded by count and age.
- Store only data that answers a specific product or evaluation question.
- Normalize scoring inputs before combining them.
- Keep selection deterministic when a fixed random seed is supplied.
- Make every migration backward-compatible and safe for existing installs.
- Retain the current user-facing reset controls and include new recommendation
  data in those controls.

## Roadmap Summary

| Phase | Outcome | Storage impact |
|---|---|---|
| 0. Baseline | Structured diagnostics and quality baseline | None |
| 1. Correct commit | Accepted-only cooldown, restart-safe variety | Bounded Settings data |
| 2. Provenance | Organic and generated plays become distinguishable | One Room table and two play columns |
| 3. Unified scoring | One explainable scorer and multi-route evidence | None required |
| 4. Session context | Recommendations follow current listening intent | Prefer derived/in-memory state |
| 5. Feedback | More precise signals than skip/dislike/block | Reuse feedback storage where possible |
| 6. Provider/latency | Fewer repeated probes and faster builds | Small bounded capability cache |
| 7. Evaluation | Deterministic offline comparison | Local test/export artifacts |
| 8. Candidate impressions | Training-grade exposure data, only if needed | Optional bounded Room table |
| 9. LiteRT | Learned reranking, only if proven useful | Optional model asset and runtime |

## Phase 0: Establish a Baseline

This phase changes no recommendation behavior and requires no database change.

### Add structured build diagnostics

Record one structured summary per recommendation build:

- recommendation mode;
- requested target count;
- build duration;
- seed count and selected cluster;
- candidates returned per route;
- candidates removed by each hard filter;
- candidates surviving family classification;
- generated count;
- accepted queue count;
- provider failures and timeouts;
- cache hits and misses;
- duplicate track and artist counts;
- overlap with the previous 1, 3, and 12 mixes.

Debug diagnostics should remain local, redact authentication data, and avoid
logging full catalog payloads.

### Define baseline metrics

Measure separately for Smart Mix, Genre Radio, and Play Similar:

- `build_success_rate`;
- `accepted_tracks / requested_tracks`;
- time to queue replacement;
- early skip rate, using the existing skip thresholds;
- median listened fraction;
- full-listen rate;
- like and dislike rate;
- track overlap across recent mixes;
- artist overlap across recent mixes;
- unique artist ratio per mix;
- genre-family drift within a mix;
- generated-to-organic replay rate.

Do not set quality targets until a representative baseline has been captured.
Correctness acceptance criteria can be fixed immediately; preference-quality
criteria should be relative to the measured baseline.

## Phase 1: Correct Queue Commit and Persist Cooldowns

This is the highest-value, lowest-complexity implementation phase. It should not
require a Room migration.

### Return the real queue delivery result

Change queue delivery to return a value such as:

```kotlin
data class QueueLoadResult(
    val acceptedUris: List<String>,
    val rejectedUris: List<String>,
    val replacedQueue: Boolean,
)
```

`playMediaSalvagingBadItems` should accumulate the chunks Music Assistant
accepted and return them to the orchestrator. An all-rejected result remains a
failure.

### Commit recommendation state after delivery

The desired sequence is:

```text
build RecommendationDraft
        -> deliver queue
        -> receive QueueLoadResult
        -> commit accepted track/artist/genre cooldown
        -> expose success to the UI
```

Do not add generated tracks, artists, or clusters to recent history during the
build. A build that is cancelled, rejected, or replaced before delivery must not
consume a cooldown slot.

### Persist a bounded cooldown snapshot

Reuse `SettingsRepository`, following the existing persisted seed-cluster
rotation pattern. No new Room table is required in this phase.

```kotlin
@Serializable
data class RecentMixSnapshot(
    val createdAt: Long,
    val mode: String,
    val trackUris: Set<String>,
    val artistKeys: Set<String>,
    val genre: String? = null,
    val clusterGenres: Set<String> = emptySet(),
)
```

Persistence rules:

- keep at most the last 12 accepted mixes;
- persist accepted tracks only;
- expire old snapshots after a defined retention window;
- hydrate once before the first build;
- clear or namespace the snapshot on account/server change;
- cap serialized size and ignore malformed entries safely;
- include the snapshot in the existing recommendation reset behavior.

### Phase 1 acceptance criteria

- A rejected track never enters recent-track or recent-artist cooldown.
- A cancelled or failed build does not alter cooldown or cluster rotation.
- A partially accepted mix commits only its accepted tracks.
- Recent cooldowns survive process recreation.
- Account switching cannot reuse another account's cooldown state.
- Queue replacement semantics remain unchanged.

## Phase 2: Add Minimal Recommendation Provenance

This phase introduces the minimum database support needed to distinguish
organic taste from recommendation exposure. It deliberately avoids a
candidate-level analytics schema.

### Database changes

Add one nullable reference and one origin value to `play_history`:

```kotlin
enum class PlayOrigin {
    ORGANIC,
    SMART_MIX,
    GENRE_RADIO,
    PLAY_SIMILAR,
    UNKNOWN,
}

// New PlayHistoryEntity fields
@ColumnInfo(name = "origin")
val origin: String = "unknown"

@ColumnInfo(name = "recommendation_run_id")
val recommendationRunId: String? = null
```

Add one compact table:

```kotlin
@Entity(
    tableName = "recommendation_runs",
    indices = [
        Index("created_at"),
        Index("queue_id"),
        Index("mode"),
    ],
)
data class RecommendationRunEntity(
    @PrimaryKey
    @ColumnInfo(name = "run_id")
    val runId: String,

    @ColumnInfo(name = "queue_id")
    val queueId: String,

    val mode: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "requested_count")
    val requestedCount: Int,

    @ColumnInfo(name = "generated_count")
    val generatedCount: Int,

    @ColumnInfo(name = "accepted_count")
    val acceptedCount: Int,

    val status: String,
    val genre: String? = null,

    @ColumnInfo(name = "cluster_genres_json")
    val clusterGenresJson: String = "[]",

    @ColumnInfo(name = "accepted_track_uris_json")
    val acceptedTrackUrisJson: String = "[]",

    @ColumnInfo(name = "accepted_artist_keys_json")
    val acceptedArtistKeysJson: String = "[]",

    @ColumnInfo(name = "random_seed")
    val randomSeed: Long,

    @ColumnInfo(name = "build_duration_ms")
    val buildDurationMs: Long,
)
```

The accepted URI sets are stored as JSON because they are bounded, written once,
and normally read as a whole when restoring cooldown or evaluating a run. A
normalized item table is unnecessary until candidate-level queries are needed.

Do not add a strict foreign key from old play-history rows to recommendation
runs unless deletion and migration behavior clearly benefits from it. A nullable
indexed run ID is sufficient for the first implementation and degrades safely.

### Attribute playback to the active run

Maintain a bounded active recommendation context per queue:

```kotlin
data class ActiveRecommendationContext(
    val queueId: String,
    val runId: String,
    val mode: String,
    val acceptedTrackUris: Set<String>,
)
```

When play history is recorded:

- if the queue has an active context and the played URI belongs to its accepted
  set, store the corresponding origin and run ID;
- otherwise store `ORGANIC` or `UNKNOWN`, depending on whether the source is
  known;
- clear or replace the context when the queue is replaced, cleared, or changes
  ownership;
- do not infer recommendation origin from track URI alone.

### Source-aware learning rules

Initial policy:

- organic selection: normal positive weighting;
- generated exposure followed by an early skip: normal negative signal;
- generated exposure with a passive short listen: neutral or very weak signal;
- generated exposure with a high completion ratio: moderate positive signal;
- explicit like/dislike: strong signal regardless of origin;
- later organic replay of a generated track: strong confirmation;
- queue replacement and Previous remain neutral, preserving current behavior.

Exact weights should be constants with unit tests and should be tuned only after
baseline data exists.

### Retention and reset

- Retain compact run summaries for the same broad period as recommendation
  history, or cap them to a fixed maximum count.
- Remove orphaned run summaries during recommendation cleanup.
- Include runs and provenance fields in Recommendation Insights reset.
- Keep blocked artists independent, matching current reset semantics.

### Phase 2 acceptance criteria

- A generated play can be joined to the run that introduced it.
- Organic and generated plays no longer contribute identical implicit evidence.
- Existing play-history rows migrate to `UNKNOWN` without data loss.
- Explicit feedback remains authoritative.
- Database growth stays bounded and visible in Recommendation Insights.

## Phase 3: Unify Scoring and Preserve Evidence

This phase restructures scoring without changing candidate APIs.

### Introduce a single feature representation

```kotlin
data class CandidateFeatures(
    val artistRouteSimilarity: Double? = null,
    val trackRouteSimilarity: Double? = null,
    val routeAgreement: Double = 0.0,
    val artistAffinity: Double = 0.0,
    val trackAffinity: Double = 0.0,
    val genreAffinity: Double = 0.0,
    val recentTasteAffinity: Double = 0.0,
    val sessionAffinity: Double = 0.0,
    val daypartAffinity: Double = 0.0,
    val favoriteBonus: Double = 0.0,
    val recentTrackPenalty: Double = 0.0,
    val recentArtistPenalty: Double = 0.0,
    val sourceExposurePenalty: Double = 0.0,
    val familyMatch: FamilyMatch,
)
```

Hard exclusions must run before scoring:

- blocked artist;
- suppressed artist;
- disliked/suppressed track;
- unavailable or blank URI;
- seed track itself where required;
- duplicate recording identity.

### Normalize score domains

Do not directly mix raw BLL values, route ranks, decayed feedback totals, and
bonuses. Normalize each signal to a documented bounded domain, preferably
`[-1, 1]` or `[0, 1]`, before combining it.

Possible transformations:

- route similarity: percentile or calibrated rank within that provider route;
- BLL preference: bounded monotonic transform;
- smart feedback: bounded decayed sum;
- daypart: confidence-weighted affinity, falling toward zero with sparse data;
- recency: bounded penalty based on time and number of recent appearances;
- favorites: explicit but limited bonus that cannot override hard negatives.

### Centralize weights

Create a single `CandidateScorer` and immutable `ScoringConfig`. Avoid separate
versions of recent, smart-feedback, and daypart weights at artist and track
levels unless the distinction is explicit in the feature model.

```kotlin
interface CandidateScorer {
    fun score(features: CandidateFeatures): Double
}
```

The first implementation remains deterministic and hand-tuned. This interface
is the future seam for a LiteRT ranker.

### Fuse route evidence

Replace max-only route merging with evidence preservation:

- keep both raw route ranks;
- keep provider and route source flags;
- add a bounded agreement bonus;
- calibrate route reliability separately when enough outcome data exists;
- never let agreement bypass hard filters or family verification.

A simple first version can use a small fixed agreement bonus after route scores
have been normalized. A probabilistic union or learned fusion should wait for
calibration data.

### Keep diversity separate from relevance

The selection stage continues to own:

- per-artist cap;
- per-album cap;
- first-pass unique artists;
- artist gap;
- track deduplication;
- discovery-weighted sampling;
- verified opening track.

It should consume scored candidates but should not recompute preference or
context features.

### Define slider contracts

- **Variety** changes the breadth and rotation inside a coherent candidate pool.
- **Discovery** changes distance from established taste and route depth.
- **Strictness** changes minimum confidence in implicit preference and seed
  eligibility.
- **Length** changes target queue size only.

Add tests proving that each slider is monotonic in its intended effect and does
not silently control unrelated behavior.

### Phase 3 acceptance criteria

- Every selected track has one inspectable feature set and final relevance
  score.
- A fixed input and random seed reproduce the same result.
- Route agreement is preserved.
- Hard exclusions remain impossible to override by score.
- Diversity behavior remains equal to or better than baseline.
- Slider behavior matches its documented contract.

## Phase 4: Add Session Context and Better Genre Movement

### Build a lightweight session context

Use the existing 30-minute session boundary and recent playback state to derive:

- last 3 to 5 completed tracks;
- recent genre families;
- recent manual selections;
- consecutive early skips;
- current seed or explicitly selected track;
- current queue origin;
- session start time and daypart.

Session signals must decay quickly and must not permanently overwrite long-term
taste. Examples:

- several completed ambient tracks increase ambient-session affinity;
- several early skips in one family temporarily reduce that family;
- a manually selected track carries more session intent than a passive generated
  track;
- replacing the queue clears stale session intent only when appropriate.

### Split genre adjacency into two graphs

Maintain distinct concepts:

1. **Semantic adjacency** from catalog tag co-occurrence.
2. **Personal transition affinity** from consecutive completed tracks within
   user sessions.

Improve semantic adjacency using a normalized association measure such as
conditional probability, Jaccard, or normalized PMI rather than pair-count
ratios alone. Require minimum support so rare tag pairs do not dominate.

Personal transition affinity should:

- use completed or intentionally selected tracks;
- downweight generated passive exposure;
- be directional when evidence supports it;
- decay over time;
- fall back to semantic adjacency for cold start.

### Add genre provenance and confidence where feasible

When the same artist or track receives genres from Music Assistant,
MusicBrainz, a provider, or cached enrichment, preserve enough source
information to avoid treating every tag as equally reliable. This can initially
be an in-memory confidence policy; a schema change is only justified if source
confidence materially improves evaluation results.

### Phase 4 acceptance criteria

- Current-session behavior can influence ranking without rewriting long-term
  preferences.
- Cold start continues to work through semantic adjacency and provider
  similarity.
- Personal transitions do not learn primarily from generated queues.
- Genre movement remains coherent at low Discovery and broadens predictably at
  high Discovery.

## Phase 5: Improve Feedback Semantics

Current skip, dislike, and artist block actions cover important cases but leave
a large gap between ambiguous skip and permanent rejection.

### Add lightweight recommendation feedback

Candidate actions:

- More like this.
- Less like this.
- Too familiar.
- Too far from this mix.
- Less of this genre for now.
- Do not recommend this track.
- Block this artist.

### Scope feedback correctly

- `Too familiar` affects novelty and cooldown, not artist preference.
- `Too far from this mix` affects route/family relevance for that run or session.
- `Less of this genre for now` is session-scoped unless repeated.
- `Do not recommend this track` remains a durable hard track suppression.
- `Block this artist` remains a durable hard artist exclusion.
- A normal skip stays duration-sensitive and ambiguous.

Do not add all controls to the primary player surface. Start with a compact
feedback sheet or Recommendation Insights action and validate usage.

## Phase 6: Provider Capability and Latency Improvements

### Cache provider capability outcomes

Represent the difference between:

- supported with results;
- supported with a valid empty result;
- unsupported;
- temporary error;
- timeout.

Use Music Assistant provider feature declarations where trustworthy. Add a
bounded negative-result TTL for valid empty and unsupported responses. Do not
cache authentication, transport, or temporary provider failures as unsupported.

### Make cache behavior observable

Measure per route/provider:

- success rate;
- empty rate;
- timeout rate;
- median latency;
- cache hit rate;
- usable candidates returned;
- accepted tracks and later outcomes.

This allows expensive, low-yield routes to be deprioritized without assuming all
providers behave the same.

### Prefer prefetch over a longer spinner

- Read cached candidates before taking server concurrency permits.
- Continue bounded background prefetch for likely next seeds.
- Avoid live enrichment work that cannot affect the current build within its
  deadline.
- Keep one total Music Assistant concurrency budget shared across routes.
- Preserve partial results when the build deadline expires.

Do not mutate a queue after playback begins merely because slower candidates
arrived. Late results should warm the next build unless the user explicitly asks
for queue continuation.

## Phase 7: Local Evaluation Harness

Before any learned ranker, build a deterministic offline evaluator that can read
an anonymized/local snapshot of recommendation data and replay selection.

### Required capabilities

- Supply a fixed clock and random seed.
- Reconstruct candidate features from a stored or synthetic candidate pool.
- Compare current scorer and proposed scorer on identical inputs.
- Report overlap, diversity, repetition, source mix, family coherence, and
  exclusion violations.
- Replay recent runs without modifying the production database.
- Export aggregate metrics without track titles or personal identifiers.

### Required test datasets

- cold-start user;
- narrow library;
- large multi-provider library;
- genre with few productive library seeds;
- provider without `similar_tracks`;
- many duplicate recordings across providers;
- heavy generated-listening history;
- strong explicit dislikes and blocked artists;
- sparse or conflicting genre metadata;
- repeated mixes before and after process recreation.

## Optional Phase 8: Candidate-Level Impressions

Add this phase only if aggregate run provenance is insufficient for scorer
tuning or a learned ranker is approved.

Possible table:

```kotlin
@Entity(
    tableName = "recommendation_candidates",
    primaryKeys = ["run_id", "track_uri"],
    indices = [Index("run_id"), Index("selected")],
)
data class RecommendationCandidateEntity(
    val runId: String,
    val trackUri: String,
    val selected: Boolean,
    val selectedRank: Int?,
    val accepted: Boolean,
    val finalScore: Double,
    val sourceFlags: Int,
    val dropReason: String?,
    val featuresJson: String? = null,
)
```

Complexity controls:

- store only top candidates or selected plus a sampled rejected set;
- use short retention, for example 30 days;
- cap total rows;
- omit `featuresJson` outside debug/evaluation builds unless needed;
- include cleanup and reset from the first migration;
- never sync the table externally by default.

This table is explicitly deferred. It is not required for cooldown persistence,
play-origin attribution, or the first scoring refactor.

## Optional Phase 9: LiteRT Ranker

LiteRT becomes appropriate only after:

- recommendation runs are linked to outcomes;
- generated and organic behavior are distinguishable;
- feature values are normalized and stable;
- the deterministic scorer has a reproducible baseline;
- there is enough positive and negative evidence;
- offline evaluation shows a learned ranker can improve a meaningful metric.

The learned model should replace only `CandidateScorer`. Candidate retrieval,
hard filters, diversity selection, queue delivery, and cooldown persistence stay
outside the model.

Rollout requirements:

- ship the model behind a feature flag;
- run shadow inference first;
- compare model and baseline scores locally;
- fall back immediately on model load or inference failure;
- benchmark latency, memory, APK/model size, and battery on low- and high-end
  devices;
- never claim an improvement without offline and real-device evidence.

## Testing Strategy

### Unit tests

- Queue salvage returns exactly the accepted and rejected URIs.
- Cooldown commit happens only after accepted delivery.
- Cooldown snapshots are bounded, expire correctly, and tolerate corrupt data.
- Play-origin classification handles generated, organic, replacement, Previous,
  and account-switch cases.
- Source-aware implicit feedback is monotonic with listened fraction.
- Score normalization remains finite for empty, extreme, and NaN inputs.
- Route fusion rewards agreement without overriding hard exclusions.
- Fixed seeds produce deterministic selection.
- Each tuning slider follows its contract.
- Session context expires and cannot become long-term taste by itself.

### DAO and migration tests

- Existing `play_history` rows survive with safe defaults.
- New indexes exist and query plans remain bounded.
- Run cleanup does not delete listening history unexpectedly.
- Recommendation reset removes runs and provenance as documented.
- Blocked artists remain intact when recommendation history is reset.

### Orchestrator integration tests

Use a fake Music Assistant repository to cover:

- complete queue acceptance;
- partial acceptance and salvage;
- all tracks rejected;
- timeout after partial candidate arrival;
- unsupported similarity route;
- duplicate tracks across routes/providers;
- cancellation and account switch during a build;
- process recreation and cooldown hydration.

### Real-device verification

- Build and install using the repository's full deploy workflow.
- Generate several consecutive mixes before and after app process recreation.
- Verify accepted queue contents against stored cooldown and run summaries.
- Exercise partial/failed provider conditions where reproducible.
- Capture timing, CPU, memory, and relevant recommendation logs.
- Report compile, build, install, and runtime verification as separate levels.

## Rollout Strategy

Use independent feature flags where behavior changes materially:

- `persisted_mix_cooldown`;
- `recommendation_provenance`;
- `unified_candidate_scorer`;
- `session_context_ranking`;
- `route_evidence_fusion`;
- `personal_genre_transitions`;
- future `litert_ranker`.

Recommended rollout order:

1. Diagnostics only.
2. Queue commit correctness.
3. Persisted cooldown.
4. Play origin and run summaries.
5. Unified scorer in shadow comparison.
6. Unified scorer active.
7. Session context and route fusion independently.
8. Personal genre transitions.
9. Candidate impressions or LiteRT only if evidence justifies them.

Each phase must preserve a clean fallback to the preceding behavior.

## Product and Privacy Requirements

- Keep recommendation learning opt-in under Smart Listening.
- Explain that recommendation history stays local.
- Provide a clear reset action and accurate description of what it removes.
- Keep blocked artists separately manageable.
- Do not expose raw internal scores as if they were objective confidence.
- Explanations should use human-readable reasons, for example:
  - "Similar to a track you replayed";
  - "Fits your recent evening listening";
  - "New artist in a genre you often finish";
  - "Recommended by two similarity sources".
- Avoid sending local preference features, run histories, or evaluation data to
  Music Assistant or another service unless the user explicitly opts in to a
  future sync feature.

## Definition of Done

The non-ML improvement program is complete when:

- queue delivery and cooldown state agree exactly;
- recent repetition protection survives process recreation;
- generated exposure is distinguishable from organic selection;
- recommendation outcomes can be measured per mode and run;
- scoring features and weights live behind one scorer interface;
- multi-route evidence is preserved;
- session context affects only short-term intent;
- provider failures and unsupported features do not cause repeated unnecessary
  work;
- all new state is bounded, resettable, account-safe, and migration-tested;
- offline and real-device comparisons show no regression in queue length,
  latency, hard exclusions, or diversity;
- any claimed quality improvement is backed by baseline-relative outcome data.

## Recommended First Implementation Slice

Keep the first pull request deliberately small:

1. Make queue salvage return accepted and rejected URIs.
2. Move recent track/artist/genre updates after successful queue delivery.
3. Move seed-cluster persistence after successful delivery.
4. Persist the last 12 accepted `RecentMixSnapshot` values in
   `SettingsRepository`.
5. Hydrate those snapshots before the first generated mix.
6. Add unit and fake-repository integration tests for complete, partial, failed,
   cancelled, restarted, and account-switched cases.

This slice fixes real correctness and repetition problems without a Room
migration. The provenance schema should be a separate change after this behavior
is stable.
