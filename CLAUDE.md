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
- Host has JDK 21 + Maven 3.8 (apt). User `geir` is not in the `docker` group by default.

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
- `LibraryPort`: tracks from LMS via JSON-RPC `/jsonrpc.js` (never LMS's SQLite files).
  Adapter `LmsLibrary`. Note: `titles` ignores `sort:random`, so the adapter loads a cached
  snapshot of the library and samples in Java.
- `SimilarityPort`: acoustic/other neighbours. Stub (`NoSimilarity`) returns nothing.
- `LlmPort`: reranks candidates, returning IDs only. Stub (`NoLlm`) returns nothing.
- `PlaybackContextPort`: player, seed/recent tracks, time of day. Stub (`BasicPlaybackContext`).

Core flow (`MixService`):
1. Build context; candidates = similarity neighbours, topped up with random library tracks;
   exclude seed tracks.
2. Ask the LLM to pick and order **by ID only**, under a hard timeout.
3. `TrackSelection`: **discard any ID not in the candidate list**, drop duplicates, cap at
   count, top up from candidate order if short.
4. LLM slow, failing or empty → candidate (similarity) order. A mix is always produced if
   the library has tracks.

HTTP API (`dj.adapter.http`): `GET /mix?player=<mac>&seed=<id,id,..>&count=<n>` →
`{"tracks":[{"id","url","title","artist","album"}]}`; `GET /health`.

Config via env vars: `LMS_URL` (default `http://localhost:9000`), `DJ_PORT` (9010),
`DJ_CANDIDATES` (50), `DJ_LLM_TIMEOUT_MS` (3000), `DJ_LIBRARY_TTL_MINUTES` (15).

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

## Roadmap (not built yet)
- **Step 2: real candidates.** Bliss can't analyse streamed tracks, so options include:
  local audio library + bliss-analyser SQLite; Last.fm/ListenBrainz similar artists/tracks
  (cached locally, so the suggestion path stays offline-tolerant); artist/album affinity from
  LMS play counts and ratings (`LibraryPort` additions); avoid recently played artists.
- **Step 3: LLM reranking.** `OllamaLlm` (local, default) and `ClaudeLlm` (optional, API key
  via env). Prompt with compact candidate lines (id, artist, title, album, year); response
  is a JSON list of IDs + a one-line reason each. Store reasons for "why this track?".
- **Step 4: natural-language requests** ("mellow prog for Sunday morning"): an endpoint
  plus an LMS menu/CLI command to set a per-player "mood" that biases candidates and the LLM.
- **Discovery mode:** suggest tracks outside the library via Spotify/TIDAL (playable URLs),
  clearly separated from library-only mode.
- Real `PlaybackContextPort` adapter: player name, recent history from LMS `status`.
- Observability: log each mix decision (source: llm/fallback, latency) for tuning.
