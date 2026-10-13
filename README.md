# dj

AI-powered "what to play next" for [Lyrion Music Server](https://lyrion.org) (LMS).

When a player's queue is about to run out, LMS's **Don't Stop The Music** (DSTM) asks the
`DJ` plugin for more tracks. The plugin asks the **suggestion service**, which picks tracks
from the LMS library. If the service is slow or down, DSTM falls back to its default
behaviour and the music keeps playing.

```
LMS ──DSTM──▶ plugin/ (Perl) ──GET /mix──▶ service/ (Java) ──JSON-RPC──▶ LMS library
```

What it does today:

- Follows whatever is playing, from your library or straight from TIDAL/Spotify.
- Ranks tracks by artist similarity and style tags from Last.fm (cached locally), your play
  counts and release years; avoids what's queued (and other versions of it) and spreads
  artists out.
- Mixes in about 40% new music from TIDAL: more by your artists, and by similar artists you
  don't have, found in the background through LMS's TIDAL plugin.
- **Start DJ** on a stopped player starts from what that player usually plays at this time
  of day.
- Skipped suggestions count against the song (and a little against the artist).

- Claude (Haiku) picks and orders the final tracks so the music flows and fits the time of
  day, with a one-line reason for each, shown on Now Playing as each song starts. Without an
  API key, or if Claude is slow or unreachable, DJ's own ranking is used.
- **Play a mood…** takes a request in your own words ("mellow prog for Sunday morning") and
  steers the music by it for the next 4 hours.
- Christmas songs stay out of the mix until Advent, unless you ask for them.

See `CLAUDE.md` for the architecture and roadmap.

## Getting started

You need Lyrion Music Server (tested with 9.1) and Docker on the same machine. The service
image is [`geireilertsen/dj`](https://hub.docker.com/r/geireilertsen/dj), for PCs (amd64) and
64-bit Raspberry Pi OS (arm64).

**1. Get the keys (optional, both recommended).**

- A [Last.fm API key](https://www.last.fm/api/account/create) (free) gives DJ artist
  similarity and styles. Without it DJ only knows play counts and release years.
- An [Anthropic API key](https://console.anthropic.com) lets Claude pick the final order,
  give reasons and understand moods (about $0.0005 per mix). Create it **inside a
  workspace**; keys outside one are rejected. Without it DJ uses its own ranking.

**2. Run the service.** Save this as `docker-compose.yml`:

```yaml
services:
  dj:
    image: geireilertsen/dj:latest
    container_name: dj
    network_mode: host        # reaches LMS on localhost:9000; the plugin reaches DJ on localhost:9010
    environment:
      - TZ=Europe/Oslo        # your time zone: DJ picks music by time of day
      - LMS_URL=http://localhost:9000
    env_file:
      - path: .env            # LASTFM_API_KEY=... and ANTHROPIC_API_KEY=..., one per line
        required: false
    volumes:
      - dj-data:/data
    restart: unless-stopped

volumes:
  dj-data:
```

```sh
docker compose up -d
curl localhost:9010/health      # {"status":"ok"}
```

In the background DJ now looks up your artists on Last.fm (a few minutes for a thousand
artists) and, if LMS's TIDAL plugin is signed in, finds new music by them and similar
artists. `curl localhost:9010/status` shows how far it has come.

**3. Install the LMS plugin** from its repository:

1. In LMS open *Settings → Manage Plugins* (Material Skin: *Settings → Server → Manage
   Plugins*).
2. At the bottom, under *Additional Repositories*, add
   `https://raw.githubusercontent.com/geir-eilertsen/dj/main/repo.xml` and save.
3. Find **DJ (AI suggestions)** in the list, tick it, save and restart LMS when asked.

LMS offers new versions of the plugin there too. If the service runs on another machine, set
its address under *Settings → Advanced → DJ* (and use `ports: ["9010:9010"]` instead of host
networking).

**4. Play.** In Material Skin open **Apps → DJ → Start DJ** (or **Play a mood…**). DJ replaces
the queue, starts playing and becomes the player's Don't Stop The Music mixer, so it keeps
adding music when the queue runs low. Long-press **Start DJ** to pin it to the home screen.

To use DJ only when a queue runs out, choose it per player under *Settings → Player →
Don't Stop The Music → "DJ (AI suggestions)"*.

## Layout

| Path | What |
|---|---|
| `plugin/` | LMS plugin `Plugins::DJ`: registers the DSTM mixer, settings page |
| `service/` | Java 21 suggestion service (hexagonal: `dj.domain` core, `dj.adapter.*`) |
| `docker-compose.yml` | Runs the service next to LMS (host networking) |

## Suggestion service

### Build and test

```sh
cd service
mvn test        # unit tests
mvn package     # target/dj-service.jar
```

### Run

Locally:

```sh
java -jar service/target/dj-service.jar
```

Or in Docker (needs access to the Docker daemon, e.g. `sudo` or membership of the `docker` group):

```sh
docker compose up -d --build
docker compose logs -f dj
```

Check it:

```sh
curl localhost:9010/health
curl 'localhost:9010/mix?player=b0:3c:dc:72:f2:72&seed=119,131&count=3'
```

### API

`GET /mix?player=<player id>&seed=<track id,...>&count=<n>`

- `player`: required. The LMS player id (MAC address).
- `seed`: optional. Library ids of the tracks at the end of the queue. Comma-separated or repeated.
- `count`: optional, 1–50, default 5.

```json
{"source":"fallback","tracks":[{"id":"61","url":"tidal://295476141.flc","title":"the slab","artist":"Slowdive","album":"everything is alive"}]}
```

`source` is `llm` when the LLM chose the order and `fallback` otherwise. Without `seed`,
the end of the player's queue is used. `fresh=1` asks for tracks that start a new queue
rather than follow the current one (led by the player's mood, if it has one).

Moods (requests in the listener's own words):

- `POST /mood` with `player` and `text` (query or form body) sets the player's mood and
  returns how DJ understood it: `{"text","tags","artists","fromYear","toYear","setAt","expiresAt"}`.
- `GET /mood?player=<id>` returns `{"current": <mood or null>, "recent": ["text", ...]}`.
- `POST /mood/clear?player=<id>` ends the mood.

`GET /why?player=<id>&url=<track url>` returns `{"reason"}` for a track DJ suggested, else 404.

`GET /status` shows the number of artist profiles and discovered tracks, whether Last.fm is
configured, and the last enrichment and discovery results. Errors return
`400` for a bad request, `503` if LMS can't be reached, and `{"error": "..."}` as the body.

### Configuration (environment variables)

| Variable | Default | |
|---|---|---|
| `LMS_URL` | `http://localhost:9000` | LMS base URL |
| `DJ_PORT` | `9010` | HTTP port |
| `DJ_CANDIDATES` | `50` | Candidate pool size offered to the LLM |
| `DJ_LLM_TIMEOUT_MS` | `5000` | Give up on the LLM after this long |
| `DJ_LIBRARY_TTL_MINUTES` | `15` | How long the library snapshot is reused |
| `DJ_DATA_DIR` | `data` (`/data` in Docker) | Where the SQLite database lives |
| `LASTFM_API_KEY` | (none) | Enables artist similarity and tags. Put it in `.env` |
| `DJ_ENRICH_INTERVAL_HOURS` | `24` | How often artist info is refreshed |
| `DJ_ARTIST_MAX_AGE_DAYS` | `30` | When artist info and discovered music count as stale |
| `DJ_SEED_LOOKUP_MS` | `1500` | Max wait for looking up an unknown artist during a mix |
| `DJ_DISCOVERY_SHARE` | `0.4` | Share of each mix from TIDAL discovery (0 turns it off) |
| `ANTHROPIC_API_KEY` | (none) | Enables Claude: picks, reasons, mood understanding. Put it in `.env` |
| `DJ_LLM_MODEL` | `claude-haiku-5-5` | Claude model |
| `DJ_LLM_LANGUAGE` | `English` | Language of the reasons |

### Last.fm

Artist similarity and style tags come from Last.fm. They are fetched in the background
and stored in SQLite, so suggestions keep working when Last.fm or the internet is down.

1. Create an API key at https://www.last.fm/api/account/create.
2. Put it in `/home/dj/.env`, which is gitignored:
   ```
   LASTFM_API_KEY=your_key_here
   ```
3. Run `docker compose up -d --build`. The first run takes about a minute for 84 artists.
   Check progress with `curl localhost:9010/status`.

Without a key the service still works, ranking by play counts and years only.

### Claude

Put a **workspace-scoped** Anthropic API key in `.env` (create it inside a workspace in the
Console; an unscoped key is rejected):

```
read -rsp 'Anthropic API key: ' k && printf 'ANTHROPIC_API_KEY=%s\n' "$k" >> /home/dj/.env
docker compose up -d
```

Each mix costs roughly $0.0005 with Claude Haiku. `DJ_LLM_MODEL`, `DJ_LLM_LANGUAGE` (reason
language, default English) and `DJ_LLM_TIMEOUT_MS` (default 5000) tune it.

## LMS plugin

### Release

`tools/release-plugin.sh` publishes the plugin for the repository: bump `<version>` in
`plugin/install.xml`, commit, then run the script. It zips `plugin/`, attaches the zip to a
GitHub release `plugin-v<version>`, and commits and pushes `repo.xml` pointing at it with
the zip's SHA-1. LMS checks the repository about once a day and offers the update.

### Install by hand

LMS runs in the `lms` container, which keeps its plugins in
`/home/lms/config/cache/InstalledPlugins/Plugins` (mounted as `/config/...`). Copy the
plugin there as `DJ`, owned by the container's user (uid 499), then restart LMS:

```sh
sudo rsync -a --delete /home/dj/plugin/ /home/lms/config/cache/InstalledPlugins/Plugins/DJ/
sudo chown -R 499:users /home/lms/config/cache/InstalledPlugins/Plugins/DJ
sudo docker restart lms
```

Use the same commands to update the plugin after changing it.

To uninstall, remove that directory and restart LMS (or uninstall it from *Settings → Manage Plugins*).

### Configure

1. *Settings → Manage Plugins*: check that **DJ** and **Don't Stop The Music** are enabled.
2. *Settings → Advanced → DJ*: set the service URL (default `http://localhost:9010`)
   and the timeout (default 10 s).
3. Select the mixer for a player. Either use the UI (*Settings → Player → (choose player) →
   Don't Stop The Music → "DJ (AI suggestions)"*; in Material Skin it's the player's settings
   dialog), or the CLI:

   ```sh
   curl -s localhost:9000/jsonrpc.js -H 'Content-Type: application/json' -d \
     '{"id":1,"method":"slim.request","params":["b0:3c:dc:72:f2:72",["playerpref","plugin.dontstopthemusic:provider","PLUGIN_DJ_DSTM"]]}'
   ```

### Start DJ button

The plugin also appears as an app: **Apps → DJ → Start DJ** in Material Skin
(*My Apps* in the classic web UI). One tap asks the service for 10 tracks following the
current song (or, on a stopped player, songs it usually plays at this time of day),
switches off LMS's random mix, replaces the queue, starts playing and makes
DJ the player's Don't Stop The Music mixer, so the music keeps going.

**Why this track?** in the same menu shows the reason DJ gave for the song that's playing.
The reason is also shown without any clicks: when a song DJ chose starts, Material Skin pops
it up for 20 seconds on every window showing that player (`material-skin send-notif`), and
it is the first line of the song's track info ("Why DJ chose this").

To get a home screen button in Material Skin, long-press **Start DJ** (or use its ⋮ menu)
and choose *Pin to home screen*.

### Moods

**Apps → DJ → Play a mood…** takes a request in your own words, in any language:
"mellow prog for Sunday morning", "Kim Larsen og dansk rock fra 80-tallet". DJ replaces the
queue with 10 songs for it and keeps following it in everything it adds, until you choose
**Clear mood**, use **Start DJ**, or 4 hours pass. Your last 5 moods for the player are listed
under the fixed items for one tap.

Claude turns the words into style tags (from the Last.fm tags your artists have), artists and
years, once, when you set the mood. The mood then counts for 75% of how well each song fits
(the rest follows what's playing), songs outside the requested years score lower, and Claude
is told the request when it picks. Without Claude, DJ spots known tags, artist names and
decades ("80s", "1970s", "80-tallet") in the words.

Christmas songs (by title or album, in English and the Nordic languages, German and Spanish)
are only suggested from the first Sunday of Advent to 6 January, unless the mood asks for them
("julemusikk") or one is playing at the end of the queue. Discovery still finds them, so
they're ready in December.

Pinning a recent mood to the home screen pins its *position* in the list (LMS menus work by
position), so it changes when you set new moods; pin **Play a mood…** instead.

### Verify that the music keeps playing

1. Start the service and confirm `curl localhost:9010/health` answers.
2. On a player with DJ selected, clear the queue, add **one or two** tracks and press play.
   Make sure repeat is off. DSTM only acts when fewer than 2 tracks remain and repeat is off.
3. Within a few seconds the queue gains 5 new tracks, and the service log shows
   `Mix for <player>: 5 tracks (FALLBACK) in N ms`.
4. Fallback check: stop the service (`docker compose stop dj`) and let the queue run low
   again. The plugin logs a warning and DSTM uses its default (RandomPlay), so playback
   continues.

For more detail in the LMS log (`/home/lms/config/logs/server.log`), set
*Settings → Advanced → Logging → plugin.dj* to *Info*.

## License

MIT, see [LICENSE](LICENSE).
