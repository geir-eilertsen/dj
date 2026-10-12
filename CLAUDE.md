# dj: AI music suggestions for Lyrion Music Server

When a player's queue is about to run out, LMS's "Don't Stop The Music" (DSTM) asks our
plugin for more tracks; the plugin asks the Java suggestion service, which picks tracks
from the LMS library. Later: acoustic similarity, listening history, LLM reranking,
natural-language requests and explanations.

## Environment (host "media", 192.168.3.118)

- LMS 9.1.1 runs in Docker (`lmscommunity/lyrionmusicserver`, container `lms`),
  compose file `/home/lms/docker-compose.yml`, **`network_mode: host`**.
  HTTP/JSON-RPC on `:9000`, CLI `:9090`. Not ours: never edit `/home/lms` without asking.
- Host ↔ container paths: `/home/lms/config` → `/config`, `/home/lms/music` → `/music`.
- Third-party plugins live in `/home/lms/config/cache/InstalledPlugins/Plugins/`
  (owned by uid 499, the container user; writing there needs sudo).
- Players (all squeezelite): Kino `85:12:72:08:18:41` (this host),
  Kjøkken `b8:27:eb:d5:88:ba`, Geir PC `b0:3c:dc:72:f2:72`, Loftstue `88:a2:9e:c6:16:cf`.
- **The library has no local files** (as of 2026-10-12): ~745 `spotify:track:` and ~306
  `tidal://` library imports; the only genres are "Spotify" and "TIDAL". Consequences:
  bliss-analyser can't analyse them, genre metadata is useless, and the audio itself always
  needs the internet. "Playback must never depend on the internet" therefore means: the
  *suggestion path* must never depend on a cloud service.
- Host has JDK 21 + Maven 3.8 (apt) and `gh`. User `geir` is in the `docker` group (run
  docker as `sg docker -c ...` in shells that predate the group change). No passwordless sudo.
- Repo: https://github.com/geir-eilertsen/dj (public; never commit secrets).

## Architecture

### LMS plugin (`/plugin`, Perl, `Plugins::DJ`)
- Thin. Registers a DSTM handler `PLUGIN_DJ_DSTM` in `postinitPlugin` (same pattern as
  CDrummond's lms-blissmixer, which is the reference for structure/conventions).
- Handler signature (verified in `Slim/Plugin/DontStopTheMusic/Plugin.pm`, branch public/9.1):
  `registerHandler($id, sub { my ($client, $cb) = @_; ... })`, then `$cb->($client, \@urls)`.
- **The callback must be called exactly once on every path.** DSTM sets
  `pluginData(active => 1)` before calling us and only clears it in the callback; never
  calling back wedges DSTM for that player until LMS restarts. On error/timeout call
  `$cb->($client, [])`. DSTM then falls back to RandomPlay (if enabled).
- Async only: `Slim::Networking::SimpleAsyncHTTP->new($ok, $err, { timeout => N })`. Never block.
- DSTM de-dupes returned URLs against the queue itself.
- Prefs namespace `plugin.dj`: `service_url`, `timeout`.

### Suggestion service (`/service`, Java 21, Maven, plain Java, no framework)
Hexagonal: `dj.domain` has no framework dependencies (JDK only); adapters live in `dj.adapter.*`;
wiring in `dj.App` (manual constructor injection; no DI container).

Ports (`dj.domain.port`):
- `LibraryPort.allTracks()`: every track with year and play count, from LMS JSON-RPC
  `/jsonrpc.js` (never LMS's SQLite files). Adapter `LmsLibrary` caches a snapshot (TTL).
  `titles` has no random sort; tag `O` = play count. LMS exposes no last-played time.
- `PlaybackContextPort`: player name + the player's whole queue. Adapter `LmsPlaybackContext`
  (`status`, own 2 s client because it's on the mix path); falls back to request seeds.
- `ArtistProfileStore`: offline artist profiles (tags + similar artists). Adapter
  `SqliteArtistProfileStore` (`$DJ_DATA_DIR/dj.db`, mirrored in memory for fast reads).
- `ArtistInfoSource`: online artist info, background only. Adapter `LastFmArtistInfo`
  (`artist.getSimilar` + `artist.getTopTags`, 250 ms spacing, error 6 = unknown artist).
- `SimilarityPort`: acoustic neighbours. Stub (`NoSimilarity`); Bliss can't analyse streams.
- `LlmPort`: reranks candidates, returning IDs only. Stub (`NoLlm`) returns nothing.

Artist names are matched via `ArtistNames.key()` (case, accents, punctuation, "the", "&").

Core flow (`MixService`):
1. Context = player queue. Seeds = request seeds, else the last 5 queued tracks.
2. Candidates = acoustic neighbours (none yet), then `CandidateScorer` over the whole library:
   similar-artist match (either artist's profile) + tag cosine + play count + year closeness
   + jitter; queued tracks and the same song on another service excluded; artists in the
   last 4 queued tracks ×0.3; greedy artist spreading (×0.5 per earlier pick). Weights in
   `ScoringWeights.DEFAULTS`.
3. Ask the LLM to pick and order **by ID only**, under a hard timeout.
4. `TrackSelection`: **discard any ID not in the candidate list**, drop duplicates, cap at
   count, top up from candidate order if short.
5. LLM slow, failing or empty → candidate order. A mix is always produced if the library
   has tracks; missing profiles just weaken the scoring.

Background: `ArtistEnricher` runs 10 s after start and then every `DJ_ENRICH_INTERVAL_HOURS`
when `LASTFM_API_KEY` is set. It (re)fetches library artists with no profile or one older than
`DJ_ARTIST_MAX_AGE_DAYS`, saves "unknown" profiles too, saves under the library's spelling, and
aborts a run after 5 failures in a row.

HTTP API (`dj.adapter.http`): `GET /mix?player=<mac>&seed=<id,id,..>&count=<n>` →
`{"source","tracks":[{"id","url","title","artist","album"}]}`; `GET /health`; `GET /status`
(profile count, Last.fm configured, last enrichment result).

Config via env vars: `LMS_URL` (default `http://localhost:9000`), `DJ_PORT` (9010),
`DJ_CANDIDATES` (50), `DJ_LLM_TIMEOUT_MS` (3000), `DJ_LIBRARY_TTL_MINUTES` (15),
`DJ_DATA_DIR` (`data`; `/data` in Docker, named volume `dj-data`), `LASTFM_API_KEY` (from
`.env`, gitignored, never logged), `DJ_ENRICH_INTERVAL_HOURS` (24), `DJ_ARTIST_MAX_AGE_DAYS` (30).

Runs in Docker next to LMS with `network_mode: host` (plugin → `http://localhost:9010`,
service → `http://localhost:9000`).

## Conventions
- Per-language idioms; no global style. Perl: LMS plugin style (tabs, `use strict`,
  `Slim::Utils::Log` category `plugin.dj`, strings in `strings.txt` with TAB-indented lines).
  Java: standard Java conventions, 4-space indent, records for value types, JUnit 5.
- Domain code must not import adapter packages or third-party libraries.
- Tests: `cd service && mvn test`. Domain tests use hand-written fakes, not mocks.
- Small, focused git commits.
- Ask before installing system packages or touching the running LMS (restarts, copying the
  plugin, changing player prefs).

## Roadmap
- **Step 1 (done):** plugin + service skeleton, random tracks, DSTM fallback verified.
- **Step 2 (done):** Last.fm artist similarity + tags cached in SQLite, play-count weighting,
  queue-aware exclusion, recent-artist penalty, artist spreading. Ideas: tune weights from
  real listening; track-level `track.getSimilar`; LMS ratings once used.
- **"Start DJ" home-page button:** an LMS plugin menu (Material Skin: pin to home screen)
  that asks the service for a fresh mix, replaces the queue, plays it and sets the player's
  DSTM mixer to DJ. Also the natural home for step 4 requests and "why this track?".
- **Step 3: LLM reranking.** `OllamaLlm` (local, default) and `ClaudeLlm` (optional, API key
  via env). Prompt with compact candidate lines (id, artist, title, album, year, tags);
  response is a JSON list of IDs + a one-line reason each. Store reasons for "why this track?".
- **Step 4: natural-language requests** ("mellow prog for Sunday morning"): an endpoint
  plus an LMS menu/CLI command to set a per-player "mood" that biases candidates and the LLM.
  Last.fm tags give the vocabulary for matching moods/genres.
- **Discovery mode:** suggest tracks outside the library via Spotify/TIDAL (playable URLs),
  clearly separated from library-only mode. Last.fm similar artists not in the library are
  already stored.
- Local audio + bliss-analyser would make `SimilarityPort` real.
- Observability: per-candidate score breakdown endpoint for tuning.

## Gotchas
- `!` commands in Claude Code can't prompt for a sudo password; long commands pasted into a
  terminal can wrap and break. Prefer short commands or scripts.
- When LMS's own random mix is active on a player (`randomplay: 1` in `status`), DSTM does
  not call any mixer. Clear the queue to return to DJ.
