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
- Upcoming concerts by the artist that's playing (from Ticketmaster and the regional sources you
  tick in the plugin settings; Europe by default, or the countries you choose), in track info and
  as a pop-up, plus a list of every concert by the artists you play.

See `CLAUDE.md` for the architecture and roadmap.

## Getting started

DJ is two parts: a small LMS plugin, and the **DJ service** that does the thinking, which runs
in Docker. Installing the plugin alone does nothing.

You need:

- Lyrion Music Server 8.0 or newer (tested with 9.1), with the built-in *Don't Stop The Music*
  plugin enabled.
- Docker on the LMS machine (or another machine that can reach LMS). The service image is
  [`geireilertsen/dj`](https://hub.docker.com/r/geireilertsen/dj), for PCs (amd64) and 64-bit
  Raspberry Pi OS (arm64), and uses about 500 MB of RAM.
- Recommended: [Material Skin](https://github.com/CDrummond/lms-material), which shows DJ's
  reasons and concerts as pop-ups. The other skins show them in track info.
- Optional: LMS's TIDAL plugin, signed in, for new music (see below). DJ follows whatever you
  play, including Spotify through Spotty.

**1. Get the keys (optional, both recommended).**

- A [Last.fm API key](https://www.last.fm/api/account/create) (free) gives DJ artist
  similarity and styles. Without it DJ only knows play counts and release years.
- An [Anthropic API key](https://console.anthropic.com) lets Claude pick the final order,
  give reasons and understand moods (about $0.0005 per mix). Create it **inside a
  workspace**; keys outside one are rejected. Without it DJ uses its own ranking.
- A [Ticketmaster API key](https://developer.ticketmaster.com) (free) gives concert dates.
  Without it DJ only knows the shows of the regional sources you tick in the plugin settings.

Put the keys in a file `.env` next to `docker-compose.yml`, one per line
(`LASTFM_API_KEY=...`). DJ never logs them.

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
      # - DJ_CONCERT_COUNTRIES=europe   # or e.g. US,CA, or all
    env_file:
      - path: .env            # LASTFM_API_KEY=..., ANTHROPIC_API_KEY=..., TICKETMASTER_API_KEY=..., one per line
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

## Privacy

Your library, listening history, moods and skips stay in the service's own database (`/data`).
DJ sends no telemetry. What leaves your network, and only with that service's key set:

| Service | What DJ sends |
|---|---|
| Last.fm | Names of your artists, to look up similar artists, styles and top songs |
| Anthropic (Claude) | For each mix: the player's name, day and time, your mood request, the last 8 songs played and about 50 candidate songs (artist, title, album, year, styles) |
| Ticketmaster | Names of your artists, to look up concerts |
| TIDAL (through LMS's TIDAL plugin) | Searches for songs to play, from your TIDAL account |

Regional concert sources (Madness Live!, Folken) are only read (their public pages), and only if you
tick them; nothing about you is sent.

## Troubleshooting

- **Start DJ says it can't get suggestions:** check that `curl localhost:9010/health`
  answers on the LMS machine, and that the address in the plugin's settings (*Settings →
  Manage Plugins → DJ*) is right.
- **DJ doesn't add songs when the queue runs out:** choose *DJ (AI suggestions)* under
  *Settings → Player → Don't Stop The Music* (Start DJ does this for you). Repeat must be off.
  If LMS's own random mix is running on the player, DSTM never asks DJ: clear the queue.
- **No reasons, or the same artists every time:** check `curl localhost:9010/status`; `lastFm`
  and `llm` show whether the keys were picked up. The first Last.fm lookup of a big library
  takes a few minutes.
- **No concerts:** the first check of all your artists starts two minutes after the service
  starts, and takes about a second per artist. `lastConcertScan` in `/status` shows the result.
- **Logs:** `docker compose logs dj` for the service; for the plugin, set *Settings →
  Advanced → Logging → plugin.dj* to *Info* and look in LMS's `server.log`.

Questions and ideas are welcome as [GitHub issues](https://github.com/geir-eilertsen/dj/issues).

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
curl 'localhost:9010/mix?player=aa:bb:cc:dd:ee:ff&seed=119,131&count=3'   # a player's MAC and library track ids
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

`GET /concerts?artist=<name>` returns `{"artist","concerts":[{"date","time"?,"title","venue","city","country","url"?}]}`:
the artist's upcoming concerts in the chosen countries, soonest first, in the venue's local date and time.
It is looked up if the last lookup is over a day old (`504` if that takes over 4 s; the lookup
finishes in the background). `GET /concerts` returns `{"artists":[{"artist","concerts"}]}` for
every artist with concerts, by the date of their next one. `404` with no concert source.

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
| `TICKETMASTER_API_KEY` | (none) | Concerts from Ticketmaster. Put it in `.env` |
| `DJ_CONCERT_LOOKUP_MS` | `4000` | Max wait for looking up the playing artist's concerts |
| `DJ_CONCERT_COUNTRIES` | `europe` | Countries whose concerts are shown: ISO codes and/or `europe`, or `all` |
| `DJ_CONCERT_MAX_AGE_HOURS` | `24` | How long a concert lookup is reused; also how often all artists are checked |

### Last.fm

Artist similarity and style tags come from Last.fm. They are fetched in the background
and stored in SQLite, so suggestions keep working when Last.fm or the internet is down.

1. Create an API key at https://www.last.fm/api/account/create.
2. Put it in `.env` next to `docker-compose.yml` (gitignored in this repository):
   ```
   LASTFM_API_KEY=your_key_here
   ```
3. Run `docker compose up -d`. The first run takes a few minutes for a thousand artists.
   Check progress with `curl localhost:9010/status`.

Without a key the service still works, ranking by play counts and years only.

### Claude

Put a **workspace-scoped** Anthropic API key in `.env` (create it inside a workspace in the
Console; an unscoped key is rejected):

```
read -rsp 'Anthropic API key: ' k && printf 'ANTHROPIC_API_KEY=%s\n' "$k" >> .env
docker compose up -d
```

Each mix costs roughly $0.0005 with Claude Haiku. `DJ_LLM_MODEL`, `DJ_LLM_LANGUAGE` (reason
language, default English) and `DJ_LLM_TIMEOUT_MS` (default 5000) tune it.

### Concert sources

**Ticketmaster.** Most concerts come from the [Ticketmaster Discovery API](https://developer.ticketmaster.com)
(free: 5000 requests a day). Create an app there, then:

```
read -rsp 'Ticketmaster consumer key: ' k && printf 'TICKETMASTER_API_KEY=%s\n' "$k" >> .env
docker compose up -d
```

Two minutes after start, and then daily, DJ checks every library artist (most played first) and
every artist you've played from TIDAL/Spotify: about two requests per artist on tour, one for
the others. Only concerts in Europe are kept, unless `DJ_CONCERT_COUNTRIES` says otherwise
(ISO country codes and the word `europe`, comma-separated, e.g. `europe,TR` or `US,CA`; `all`
keeps every country). A change applies at once, without new lookups. Artists are matched by exact name, so a band
Ticketmaster lists under another name is missed, and two bands sharing a name can be mixed up.
Shows sold only through other ticket sellers aren't included.

Which sources are asked is chosen in the plugin settings (*Settings → Advanced → DJ → Concert
sources*): Ticketmaster is on by default, regional sources are off until you tick them. Unticking
one hides its shows at once; ticking one looks up your artists there straight away. A show two
sources both list is shown once, as the first source in the list has it.

**Madness Live!** ([madnesslive.es](https://www.madnesslive.es)), a Spanish rock and metal
promoter that sells its own tickets, needs no key. It has no API: DJ reads its ticket shop once
a day (one page a second, about 80 pages) and finds an artist anywhere on a bill, support acts
too. A site redesign would make it find nothing until the parser is updated; the last good
catalogue is kept meanwhile. Festival passes are skipped (their line-ups aren't on the page).

**Folken** ([folken.no](https://www.folken.no)), a club in Stavanger (Norway) that sells its own
tickets, needs no key either. DJ reads its programme page once a day (one request) and finds an
artist anywhere on a bill, support acts too. Dates without a year are taken as the coming one.

## LMS plugin

### Release

`tools/release-plugin.sh` publishes the plugin for the repository: bump `<version>` in
`plugin/install.xml`, commit, then run the script. It zips `plugin/`, attaches the zip to a
GitHub release `plugin-v<version>`, and commits and pushes `repo.xml` pointing at it with
the zip's SHA-1. LMS checks the repository about once a day and offers the update.

### Install by hand

For development: copy `plugin/` as `DJ` into LMS's `cache/InstalledPlugins/Plugins` folder,
owned by the user LMS runs as, then restart LMS. With LMS in Docker (the
`lmscommunity/lyrionmusicserver` image, its config mounted from `/path/to/lms/config`):

```sh
sudo rsync -a --delete plugin/ /path/to/lms/config/cache/InstalledPlugins/Plugins/DJ/
sudo chown -R 499:users /path/to/lms/config/cache/InstalledPlugins/Plugins/DJ   # the container's user
docker restart lms
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
     '{"id":1,"method":"slim.request","params":["aa:bb:cc:dd:ee:ff",["playerpref","plugin.dontstopthemusic:provider","PLUGIN_DJ_DSTM"]]}'
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

### Concerts

Track info for a song shows **Upcoming concerts (N)** when its artist has concerts coming up
(in Europe, or the countries set by `DJ_CONCERT_COUNTRIES`). When a song starts, Material Skin pops up the next three ("On tour:
14.11.2026 Oslo, …"), at most every 12 hours per artist and player. **Apps → DJ → Upcoming
concerts** lists every artist you play that is touring, soonest first.

### Verify that the music keeps playing

1. Start the service and confirm `curl localhost:9010/health` answers.
2. On a player with DJ selected, clear the queue, add **one or two** tracks and press play.
   Make sure repeat is off. DSTM only acts when fewer than 2 tracks remain and repeat is off.
3. Within a few seconds the queue gains 5 new tracks, and the service log shows
   `Mix for <player>: 5 tracks (FALLBACK) in N ms`.
4. Fallback check: stop the service (`docker compose stop dj`) and let the queue run low
   again. The plugin logs a warning and DSTM uses its default (RandomPlay), so playback
   continues.

For more detail in the LMS log (`logs/server.log` in LMS's config folder), set
*Settings → Advanced → Logging → plugin.dj* to *Info*.

## License

MIT, see [LICENSE](LICENSE).
