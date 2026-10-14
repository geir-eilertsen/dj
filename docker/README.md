# dj: an AI DJ for Lyrion Music Server

**dj** decides what plays next on your [Lyrion Music Server](https://lyrion.org) (LMS) players.
It runs next to LMS and talks to it through an LMS plugin: when a queue is about to run out,
DJ adds songs that fit what you're playing, the time of day and the mood you asked for, and
tells you why it chose each one.

Source, issues and the plugin: **https://github.com/geir-eilertsen/dj**

## What it does

- **Keeps the music going.** As LMS's *Don't Stop The Music* mixer it tops up the queue with
  songs that follow what's playing, whether that's your library or music played straight from
  TIDAL or Spotify.
- **Start DJ.** One tap replaces the queue with a fresh mix. On a stopped player it starts
  from what that player usually plays at this time of day.
- **Moods in your own words.** *Play a mood…* takes "mellow prog for Sunday morning" or
  "Kim Larsen og dansk rock fra 80-tallet" and steers the music by it for the next 4 hours.
- **New music.** About 40% of each mix is music you don't have yet: more by your artists and
  by similar artists, found in the background on TIDAL.
- **A reason for every song.** Claude picks the final order so the music flows, with a
  one-line reason that pops up on Now Playing in Material Skin.
- **Learns from skips.** Skipped suggestions count against the song and a little against
  the artist.
- **Christmas songs stay in December** (from Advent to 6 January), unless you ask for them.
- **Concerts.** Upcoming concerts by the artist that's playing (Europe by default), in track
  info and as a pop-up, and a list of every artist you play that's on tour.

Artist similarity and styles come from Last.fm and are cached locally. If Last.fm, Claude or
the internet is slow or down, DJ uses its own ranking, and if DJ itself is down, LMS falls
back to its default behaviour: the music never stops because of DJ.

## Quick start

You need LMS (tested with 9.1, needs 8.0 or newer) with the **DJ** plugin, and Docker on the
same machine.

**1. Run the service** with this `docker-compose.yml`:

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

**2. Install the plugin.** In LMS, *Settings → Manage Plugins → Additional Repositories*:
add `https://raw.githubusercontent.com/geir-eilertsen/dj/main/repo.xml`, then tick
**DJ (AI suggestions)**, save and restart LMS.

**3. Play.** In Material Skin: **Apps → DJ → Start DJ** or **Play a mood…**.

## Keys (optional, all recommended)

| Variable | Get it from | Without it |
|---|---|---|
| `LASTFM_API_KEY` | [last.fm/api/account/create](https://www.last.fm/api/account/create) (free) | DJ only knows play counts and release years |
| `ANTHROPIC_API_KEY` | [console.anthropic.com](https://console.anthropic.com), created **inside a workspace** | No reasons, no Claude ordering; moods by keywords only |
| `TICKETMASTER_API_KEY` | [developer.ticketmaster.com](https://developer.ticketmaster.com) (free) | Concert dates only from the regional sources ticked in the plugin settings |

What each service is sent is listed under [Privacy](https://github.com/geir-eilertsen/dj#privacy).

Claude Haiku costs roughly $0.0005 per mix. Keep keys in `.env`, not in the compose file. DJ
never logs them.

## Configuration

| Variable | Default | |
|---|---|---|
| `LMS_URL` | `http://localhost:9000` | LMS address |
| `TZ` | UTC | Time zone for time-of-day picks and the Christmas season |
| `DJ_PORT` | `9010` | HTTP port the plugin calls |
| `DJ_DISCOVERY_SHARE` | `0.4` | Share of each mix from new music (0 turns discovery off) |
| `DJ_LLM_MODEL` | `claude-haiku-5-5` | Claude model |
| `DJ_LLM_LANGUAGE` | `English` | Language of the reasons |
| `DJ_LLM_TIMEOUT_MS` | `5000` | Give up on Claude after this long and use DJ's own order |
| `DJ_CANDIDATES` | `50` | How many candidates Claude chooses from |
| `DJ_CONCERT_COUNTRIES` | `europe` | Concerts in these countries: ISO codes and/or `europe` (e.g. `europe,TR`, `US,CA`), or `all` |

More settings are listed in the [project README](https://github.com/geir-eilertsen/dj#configuration-environment-variables).

## Image details

- **Platforms:** `linux/amd64` and `linux/arm64` (64-bit Raspberry Pi OS). 32-bit ARM isn't
  available: there is no Java 21 runtime image for it.
- **Tags:** `latest` and version tags such as `0.11.0`.
- **Data:** SQLite databases in `/data` (artist profiles, discovered music, listening history,
  moods, concerts). Mount a volume there to keep them across updates.
- **Port:** `9010` (`GET /health`, `GET /status`, `GET /mix`, `/mood`, `/why`, `/concerts`).
- **Memory:** about 500 MB.
- **User:** runs as `nobody` (65534), not root.
- **Network:** host networking is simplest. With port mapping instead (`-p 9010:9010`), set
  `LMS_URL` to LMS's address and the service address in LMS under *Settings → Advanced → DJ*.

## License

MIT
