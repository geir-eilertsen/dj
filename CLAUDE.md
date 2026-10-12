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
- `PlaybackContextPort`: player name, playing or not, and the whole queue *as LMS reports it*
  (tracks played straight from TIDAL/Spotify have negative ids but real artist/title).
  Adapter `LmsPlaybackContext` (`status`, own 2 s client: it's on the mix path).
- `ArtistProfileStore` / `ArtistInfoSource`: artist tags + similar artists. Adapters
  `SqliteArtistProfileStore` (`dj.db`, mirrored in memory) and `LastFmArtistInfo`
  (`artist.getSimilar` + `getTopTags`, 250 ms spacing, error 6 = unknown artist).
- `TopTracksSource`: Last.fm `artist.getTopTracks` (same adapter).
- `CatalogPort`: song → playable streaming track. Adapter `LmsTidalCatalog` searches through
  LMS's TIDAL plugin (OPML: Search → query → Songs, `menu:1` gives "Title\nArtist"); needs a
  player id (any connected one); exact artist + `SongTitles` match, so covers/karaoke fail.
- `DiscoveryStore`: discovered tracks (TrackId = URL) + artists played from streaming.
  Adapter `SqliteDiscoveryStore` (`discovery.db`, in memory).
- `PlayHistory` / `FeedbackStore`: DJ's own listening log, suggestions and skips.
  Adapter `SqlitePlayHistory` (`history.db`, schema v2).
- `PlayerActivityPort`: what each player plays now, queue position, queue slices.
  Adapter `LmsPlayerActivity`.
- `SimilarityPort`: acoustic neighbours. Stub (`NoSimilarity`); Bliss can't analyse streams.
- `LlmPort`: picks and orders candidates, returning `Pick(id, reason)`. Adapter `ClaudeLlm`
  (Anthropic Java SDK, `claude-haiku-5-5`, effort low, **thinking disabled** - measured: thinking
  on = 3.3-5.6 s, off = 2.1-2.8 s, same quality; JSON-schema output `{"picks":[{id,reason}]}`;
  no SDK retries; refusal/max_tokens/errors throw -> fallback; `warmUp()` lists models at start).
  Prompt: weekday + time, last 8 queued tracks with tags, candidate lines
  `id | artist | title | album | year | tags | library/new`. `NoLlm` when no key.

Name matching: `ArtistNames.key()` (case, accents, punctuation, "the", "&"),
`ArtistNames.groupKey()` (side projects/line-ups, "feat.": variety rules only),
`SongTitles.key()` (strips remaster/live/mix/demo/version/EP/feat. tags, keeps "(Pt.1)").

Core flow (`MixService`, settings in `MixSettings`):
1. Context from LMS. Seeds: if the player is **stopped** (Start DJ from scratch),
   `TimeOfDaySeeds` (5 songs this player played ±90 min of now, same weekday/weekend type,
   last 8 weeks, 14-day half-life); else request seeds (library or queue ids), else the last
   5 queued tracks. Unknown seed artists are looked up on demand (`ArtistEnricher.ensure`,
   max `DJ_SEED_LOOKUP_MS`); streamed artists trigger background `Discoverer.discoverAround`.
2. `CandidateScorer` ranks the library and, separately, discovered tracks (relevance floor
   `DISCOVERY_MIN_RELEVANCE` 0.25): similar artist + tag cosine + play count + year + jitter;
   excludes queued songs and other versions of them, one version per song; recent artists
   ×0.3; skip penalties (song ×0.4, artist ×0.85 per skip, 60-day half-life); artist
   spreading. Library and discovered lists are interleaved (`DJ_DISCOVERY_SHARE` 0.4 →
   L L D L D).
3. LLM picks by ID only under a timeout; `TrackSelection` discards unknown IDs, dedupes, caps
   at one track per artist group per 5, tops up from candidate order. Reasons are kept only
   for picked tracks.
4. Suggestions (with reasons) are recorded per player for skip detection and `/why`.

Background jobs (`App`): `HistoryRecorder.poll` every 10 s (logs plays after 30 s; a
suggested track left with >15 s to go or jumped over = skip). Every 24 h: `ArtistEnricher`
then `Discoverer.discoverAll` (top 5 songs not in the library per library/listened artist,
top 3 for each one's 3 closest similar artists not in the library; skips namesakes whose
online top 20 contains none of ≥2 library songs; aborts after 5 failures in a row).

HTTP API (`dj.adapter.http`): `GET /mix?player=<mac>&seed=<id,id,..>&count=<n>` →
`{"source","tracks":[{"id","url","title","artist","album","reason"?}]}`;
`GET /why?player=&url=` (404 if DJ gave no reason); `GET /health`; `GET /status`.

Config via env vars: `LMS_URL` (`http://localhost:9000`), `DJ_PORT` (9010),
`DJ_CANDIDATES` (50), `DJ_LLM_TIMEOUT_MS` (3000), `DJ_LIBRARY_TTL_MINUTES` (15),
`DJ_DATA_DIR` (`data`; `/data` in Docker, volume `dj-data`), `LASTFM_API_KEY` (`.env`, never
logged), `DJ_ENRICH_INTERVAL_HOURS` (24), `DJ_ARTIST_MAX_AGE_DAYS` (30),
`DJ_SEED_LOOKUP_MS` (1500), `DJ_DISCOVERY_SHARE` (0.4; 0 disables discovery),
`ANTHROPIC_API_KEY` (`.env`; must be a **workspace-scoped** key, an unscoped one gets 400),
`DJ_LLM_MODEL` (`claude-haiku-5-5`), `DJ_LLM_LANGUAGE` (English), `DJ_LLM_TIMEOUT_MS` (5000).
Plugin timeout default 10 s (prefs migration 1 raised saved 5 s values).

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
- **Done:** step 1 skeleton; step 2 Last.fm similarity + history scoring; Start DJ button +
  icon (`tools/Icon.java`); artist spreading/cap; song-version dedupe; following streamed
  music; TIDAL discovery (~40%); per-player time-of-day starts; skip penalties.
- **Next ideas:** Christmas filter (discovery found "Gnags' Julesang" in October); Spotify as
  a fallback catalogue (Spotty's JSON-RPC search behaves like an interactive menu with history:
  needs investigation); a "why this track?" endpoint showing score parts.
- **Step 3 (done):** Claude Haiku picks/orders with reasons; "Why this track?" in the DJ menu.
  Local Ollama was dropped: the server has 3 GB RAM and no GPU. An `OllamaLlm` for a LAN
  machine could still implement `LlmPort`. Ideas: put the reason in LMS's track info / now
  playing; let skips and reasons feed back into the prompt.
- **Step 4: natural-language requests** ("mellow prog for Sunday morning"): an endpoint
  plus an LMS menu item to set a per-player "mood" that biases candidates and the LLM.
  Last.fm tags give the vocabulary.
- Local audio + bliss-analyser would make `SimilarityPort` real.

## Gotchas
- Restarting LMS while discovery runs makes TIDAL searches fail; the run aborts after 5 and
  retries those artists 10 min later / next run. Prefer restarting LMS when discovery is idle
  (`/status` → `lastDiscovery` set).
- The user plays a lot straight from TIDAL (not the library): never assume seeds are library
  tracks.
- Last.fm merges same-named artists (TNT); see the namesake guard in `Discoverer`.
- Testing with `player=test` logs "Could not read status for test": LMS drops the connection
  for unknown players. Harmless; real players are fine.
- Time-of-day history is per player by the user's choice (not household-wide).
- The user's UI is Material Skin (also used on their phone). Its section is "Apps", not
  "My Apps" (classic UI). Home screen items are per device.
- `!` commands in Claude Code can't prompt for a sudo password; long commands pasted into a
  terminal can wrap and break. Prefer short commands or scripts.
- When LMS's own random mix is active on a player (`randomplay: 1` in `status`), DSTM does
  not call any mixer. Clear the queue to return to DJ.
