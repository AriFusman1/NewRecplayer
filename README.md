# PCAP Player

Replays recorded network traffic to a service under test: the same application bytes, in the
same order, with the same timing.

You upload a capture (pcap / pcapng) taken on a service's host, choose which traffic and which
time range to keep, and save it as a **record**. Later you play the record to any IP:port. The
player acts as the original source over ordinary sockets (the OS builds new headers), and
replies from the service are read and discarded.

**Features**
- **Upload and cut**: a packets-over-time graph of the whole capture; drag across it to choose the
  start and end time. The ports you pick are highlighted on the graph, with a live preview of
  what the record will contain.
- **Play**: TCP to a server, UDP to a server, or listen and wait for the service to connect (TCP
  client mode). A live graph shows progress; **click or drag on it to jump** to another moment.
- **Playlists**: several records in order, with a pause between them. Pick records from a list,
  then reorder by dragging. **Save playlists by name**; everyone can load and play them.
- **Play together**: select 2 or more records and start them at the same moment, each to its own
  target. If some of them are TCP clients, nothing plays until all of those services have
  connected; then everything starts together. All or nothing: if one target can't be used, none
  start.
- **Global limit**: at most 10 replays (configurable) run at once, for all users together.
- **Now playing tab**: every running replay, live; replays started together share one card with a
  tab per record. Leaving the tab doesn't stop anything.
- **History**: the last runs, with a filter under each column (text with suggestions, a calendar
  for times, number ranges); filtered views live in the URL and can be shared.
- **Remembers your targets**: each record pre-fills the IP and port it was last played to, and
  IP fields suggest your recent IPs. This is stored in your browser only.
- **Shared**: everyone sees every running replay live and can stop it or edit upcoming items.
- English and Hebrew (RTL) UI.

**Tech**: Java 17 + Vert.x backend, React 18 + TypeScript frontend, one S3 bucket for storage (no
database), shipped as one Docker image (OpenShift-friendly).

---

## Contents

- [Setting up a new computer](#setting-up-a-new-computer)
- [Quick start](#quick-start)
- [Using it](#using-it)
- [Configuration](#configuration)
- [Deploying](#deploying)
- [Code structure](#code-structure)
- [How it works](#how-it-works)
- [API](#api)
- [Testing](#testing)
- [Dependencies](#dependencies)

---

## Setting up a new computer

**1. Install the tools** (once):

| Tool | Version | macOS | Windows |
|---|---|---|---|
| Git | any recent | built in (Xcode command-line tools) | [git-scm.com](https://git-scm.com) |
| JDK | 17 | [Temurin 17 .pkg](https://adoptium.net/temurin/releases/?version=17) | Temurin 17 .msi |
| Maven | 3.9 | [download](https://maven.apache.org/download.cgi) the `-bin.tar.gz`, unpack it to `~/tools/` and add its `bin` to `PATH` | the `-bin.zip`, add `bin` to `PATH` |
| Node.js | 20 | [nodejs.org](https://nodejs.org/en/download) .pkg (choose v20) | .msi (v20) |
| libpcap | — | built in | [Npcap](https://npcap.com) |

Check with `java -version`, `mvn -v` and `node -v`.

**2. Get the code.** The current work is on the `yahali` branch (until it is merged into `main`):

```bash
git clone https://github.com/AriFusman1/NewRecplayer.git
cd NewRecplayer
git checkout yahali
```

**3. Signing in to GitHub (for `git push`).** When git asks for a password, GitHub does not accept
your account password: give a **personal access token** (github.com → Settings → Developer settings
→ Personal access tokens → Tokens (classic), with the `repo` scope). The operating system saves it
after the first push. Tokens expire (30 days by default): when a push says "Invalid username or
token", create a new token and push again. An SSH key avoids this.

**4. Build and run**: see [Option B](#option-b-run-on-your-machine) below. For daily work:

| You changed | Do |
|---|---|
| frontend code (`frontend/src`) | nothing: with `npm run dev` ([Option C](#option-c-frontend-development-hot-reload)) the page updates on save |
| backend code (`backend/src`) | stop the backend (Ctrl-C), `cd backend && mvn -q package -DskipTests`, start it again |
| configuration | restart the backend |

Don't rebuild the jar while the backend is running from it: replacing the file under a running Java
process can make it fail. Stop it first.

**5. Your data does not travel with the code.** With `STORAGE_TYPE=filesystem`, records, uploaded
captures and history are kept in `/tmp/pcap-player/store` on *that* computer. They are not in git,
and **macOS empties `/tmp` when it restarts**. To keep them, choose a folder of your own:

```bash
STORAGE_TYPE=filesystem STORAGE_FILESYSTEM_ROOT=$HOME/pcap-player-data java -jar backend/target/pcap-player.jar
```

To move them to another computer, copy that folder. With S3 storage the data is in the bucket and
every computer sees the same records.

**6. Known macOS limitation.** Apple's built-in libpcap (1.10.1) rejects pcapng files whose
interface records a snaplen of 0. Four unit tests use such files, so on macOS `mvn package` fails
in those tests: build with `-DskipTests` there, and run the full test suite on Linux or in Docker.
Real captures from dumpcap record a snaplen and read fine. Separately, pcapng files that mix link
types (for example Ethernet and Linux SLL2 interfaces in one file) are refused on every platform.

---

## Quick start

### Option A: Docker (nothing else to install)

```bash
docker compose up --build
```

Open **http://localhost:8080**. Data is kept in the `pcap-data` Docker volume.

Without Compose:

```bash
docker build -t pcap-player .
docker run -p 8080:8080 -e STORAGE_TYPE=filesystem -v pcap-data:/tmp/pcap-player/store pcap-player
```

### Option B: Run on your machine

**You need:** JDK 17+, Maven 3.9+, Node 20+, and the native **libpcap**:

| OS | libpcap |
|---|---|
| Windows | install [Npcap](https://npcap.com) (default options are fine) |
| Linux | `sudo apt install libpcap0.8` (Debian/Ubuntu) or `sudo dnf install libpcap` |
| macOS | built in |

**1. Build** (from the repository root):

<table><tr><th>Linux / macOS (bash)</th><th>Windows (PowerShell)</th></tr><tr><td>

```bash
cd frontend
npm ci
npm run build
cd ..
rm -rf backend/src/main/resources/webroot
cp -R frontend/dist backend/src/main/resources/webroot
cd backend
mvn -q package
cd ..
```

</td><td>

```powershell
cd frontend
npm ci
npm run build
cd ..
Remove-Item -Recurse -Force backend\src\main\resources\webroot -ErrorAction SilentlyContinue
Copy-Item -Recurse frontend\dist backend\src\main\resources\webroot
cd backend
mvn -q package
cd ..
```

</td></tr></table>

The build runs the unit tests. Add `-DskipTests` to `mvn` to skip them.

**2. Run** with local storage (no S3 needed):

<table><tr><th>Linux / macOS (bash)</th><th>Windows (PowerShell)</th></tr><tr><td>

```bash
STORAGE_TYPE=filesystem \
java -jar backend/target/pcap-player.jar
```

</td><td>

```powershell
$env:STORAGE_TYPE = "filesystem"
java -jar backend\target\pcap-player.jar
```

</td></tr></table>

Open **http://localhost:8080**.

- **Port 8080 already in use?** Set another port, for example `SERVER_PORT=8081` (PowerShell:
  `$env:SERVER_PORT = "8081"`).
- Local data goes to `/tmp/pcap-player/store`; on Windows that is `C:\tmp\pcap-player\store` on
  the current drive. Change it with `STORAGE_FILESYSTEM_ROOT`.

**3. Or use S3 / MinIO** instead of local storage. Put the S3 address and credentials in your
configuration file:

<table><tr><th>Linux / macOS (bash)</th><th>Windows (PowerShell)</th></tr><tr><td>

```bash
cp config/application.example.yml \
   config/application.yml
# edit config/application.yml
java -jar backend/target/pcap-player.jar
```

</td><td>

```powershell
Copy-Item config\application.example.yml `
          config\application.yml
# edit config\application.yml
java -jar backend\target\pcap-player.jar
```

</td></tr></table>

Start it from the repository root so `config/application.yml` is found; the log shows
`Configuration file: ...` when it is. Don't set `STORAGE_TYPE` in your shell, because environment
variables override the file. See [Configuration](#configuration). The bucket must already exist.
MinIO archived its open-source server in late 2025 and stopped publishing public images, so use
the image your internal registry mirrors; any S3-compatible server works the same way.

### Option C: Frontend development (hot reload)

Run the backend on port 8080 as in option B, then:

```bash
cd frontend
npm run dev
```

Open **http://localhost:5173**. Vite reloads the UI on every change and passes `/api` and `/ws`
through to the backend on port 8080.

---

## Using it

1. **Categories & Tags**: manage categories and tags (they can also be created in place while
   saving or editing a record, see below). A category's *connection mode* is fixed:
   - **TCP server**: the player connects to the service (the service is a TCP server).
   - **UDP server**: the player sends UDP datagrams to the service.
   - **TCP client**: the player listens and the service connects to it.

   Tags are global: create them here and put any tag on any record, whatever its category.
   Deleting a category that has records asks what to do with them: move them to another category
   with the same mode, or delete them with it. Renaming a tag to the name of another tag offers to
   merge the two (every record gets the remaining tag once).
2. **Upload**: drop a pcap/pcapng file. Then, in this order:
   - Drag across the summary graph to choose the time range (or drag its edges). Under each end of
     the graph is its time as `HH:MM:SS.mmm` with up/down arrows: click the hours, minutes, seconds
     or milliseconds to choose what the arrows move (↑ ↓ and ← → keys work too); cuts keep
     millisecond precision. Or set the start and end times: type
     them, or use the calendar button (a month calendar plus an HH:MM:SS clock; both fill the field,
     which stays editable). Only the recording's days can be picked; a date typed alone means from
     the recording's start (end field: to its end) on that day.
   - Pick the category and the ports (and optionally an IP). The **+** next to the category creates
     a new one (name + mode) and selects it. In the tags field, type to filter; Enter or **+**
     selects the tag with that name, or creates it. An existing name is selected, not duplicated.
   - Check the preview on the right, then **Save record**.

   The upload and the form are kept while you visit other pages (a dot on **Upload** shows one is
   open), so you can create a category or check another record and come back. Closing or reloading
   the browser tab asks first.
3. **Player**: find records by search, category, mode, tags or recording time. The recording-time
   fields take a date (the whole day), a date and HH, HH:MM or HH:MM:SS, typed or picked from the
   calendar. Picking a day fills 00:00:00 in "from" and 23:59:59 in "to"; the "to" calendar starts
   at the "from" day, and an end before the start is flagged. Then either:
   - **Play** a single record, giving its target IP:port (or the port to listen on).
   - **Add to playlist**, or open the playlist and use **Add records…**.
   - **Edit** a record: name, description, times, tags, and its category. The times are a cut of the
     whole recording, shown on the same graph with the same arrows as when uploading. The category can only
     change to one with the same connection mode, since the mode decides which ports and packets
     the record is built from.
   - **Select** several records (or all shown), then act on all of them from the bar that appears:
     - **Edit tags…** adds and removes tags on all of them at once.
     - **Play together** (2 or more records) asks for each one's target. After starting, the app
       switches to the **Now playing** tab. They all start at the same moment: once every record is
       ready (loaded, its time fixing prepared, a TCP server's connection made), the group picks one
       start instant 25 ms ahead and every record takes it as its time zero, so their first messages
       leave together (measured within about 0.3 ms for 10 records on one machine). If any target
       can't be used, none of them start. If some of them are **TCP clients**, nothing plays until every
       one of those services has connected ("Waiting for connection: 1/3 TCP clients connected");
       then all of them start together. A client that disconnects before the start is waited for
       again. Stop, a failed record, or the connect timeout (`replay.group-connect-timeout-ms`) ends
       the whole group. The server refuses a group of fewer than 2 with a 400.
   - **Limit**: at most `replay.max-active-replays` (default 10) replays run at once, for all users
     together; a replay waiting for a connection counts, and a playlist counts as one. Now Playing
     shows "7/10 in use". When every slot is taken, **Play** (on the cards, in the Play dialog and in
     the playlist) is disabled and says the server is full, not broken; **Play together** is
     disabled while more records are selected than there are free slots, and says how many are free.
     There is no queue: a start that does not fit (two users pressing Play at the same moment) is
     refused at once and as a whole, with the same explanation (`ACTIVE_LIMIT`, HTTP 409).
4. **Now playing** (a tab of its own; the thin bar at the bottom shows usage and opens it): live
   progress for every running replay. Leaving the tab does not stop anything. Click or drag
   on the graph to jump. Stop a replay, or edit the upcoming items of a playlist. Records played
   together share one card: an **All** tab with everyone's progress, a tab per record with its
   full live view, and **Stop all**.
5. **History**: the last runs and how they ended, with a filter under each column header: text with
   suggestions (Record, Category, Target, Started by, Playlist), from/to date and time fields for
   Start and End (the same calendar and clock as everywhere else), `100` / `>100` / `<50` /
   `100-500` for Messages, and the Result options. Filters live in the URL, so a filtered view can
   be shared.
6. **Time fixing** (optional): rewrite the time fields inside messages to the moment they are sent.
   - On **Categories & Tags**, upload an ICD in the **ICDs** card. The file is JSON, in the format
     described in [docs/icd-json-format.md](docs/icd-json-format.md), or in the annotation style
     (`"format": "annotations"`) that mirrors legacy annotated Java classes
     ([reference](skills/icd-from-annotations/reference/annotations.md)); an invalid file is rejected with
     every problem listed. Then choose the ICD in the category's dropdown.
   - Click the clock button next to the category to open its **Time fix** page. Open a message type,
     choose one **base** field and tick the fields to **fix**, giving each its time kind
     (Regular s/ms, LTime, GPS). Fields inside arrays are chosen once and fixed in every element.
     Save.
   - Every replay of the category's records now fixes those fields. The time kinds and the exact
     rules are in [docs/time-fix.md](docs/time-fix.md). To check an ICD against a real record, open
     `/api/records/{id}/decode`.
7. **Auto tagging** (optional, needs a category with an ICD): give a batch of pcaps (local files, a
   local folder, or the archive S3, optionally filtered by last-modified time) and rules such as
   "at least 10 Track messages where `targets[*].kind = AIR`". Every file is decoded and every message
   type counted; files that match a rule are saved as records with their message statistics, and the
   rule names become tags. **Evaluate only** tries rules without saving anything. The Player page can
   then filter by matched rule and by "message type ≥ N". Gzipped pcaps are accepted here and on the
   Upload page. Details: [docs/auto-tagging.md](docs/auto-tagging.md).

---

## Configuration

Settings come from three layers, each overriding the one before:

1. **Defaults**: `backend/src/main/resources/application.yml`, inside the jar. Don't edit it for
   your setup.
2. **Your configuration file**: the place for **the S3 address and secrets**. Copy
   `config/application.example.yml` to `config/application.yml` and fill it in. It is read
   automatically from the working directory (in Docker: `/app/config/application.yml`), or from
   any path set in `CONFIG_FILE`. If `CONFIG_FILE` is set and the file is missing, the app refuses
   to start rather than silently using defaults.
3. **Environment variables**: upper-case the key and replace `.` and `-` with `_`
   (`s3.secret-key` → `S3_SECRET_KEY`). Useful for one-off overrides.

```yaml
# config/application.yml
storage:
  type: s3
s3:
  endpoint: "http://minio.internal:9000"   # empty = AWS
  bucket: pcap-player
  access-key: "..."
  secret-key: "..."
  path-style: true
```

**Keeping secrets safe**:
- `config/application.yml` is ignored by git and by the Docker build, so it never ends up in the
  repository or in an image.
- The secret key is masked as `***` if the configuration is ever printed.
- In Kubernetes/OpenShift, create a Secret from the file and mount it at
  `/app/config/application.yml` (see [Deploying](#deploying)).

All keys:

| Key | Env var | Default | Meaning |
|---|---|---|---|
| server.host | SERVER_HOST | 0.0.0.0 | bind address |
| server.port | SERVER_PORT | 8080 | HTTP port (UI, API and WebSocket `/ws`) |
| storage.type | STORAGE_TYPE | s3 | `s3`, or `filesystem` for local use |
| storage.filesystem-root | STORAGE_FILESYSTEM_ROOT | /tmp/pcap-player/store | data folder for `filesystem` |
| s3.endpoint | S3_ENDPOINT | *(empty = AWS)* | e.g. `http://minio:9000` |
| s3.region | S3_REGION | us-east-1 | |
| s3.bucket | S3_BUCKET | pcap-player | must already exist |
| s3.access-key / s3.secret-key | S3_ACCESS_KEY / S3_SECRET_KEY | *(empty = AWS default chain)* | |
| s3.path-style | S3_PATH_STYLE | false | `true` for MinIO and most S3-compatible servers |
| s3.prefix | S3_PREFIX | *(empty)* | optional key prefix inside the bucket |
| work-dir | WORK_DIR | /tmp/pcap-player | uploads in progress and the local pcap cache |
| cache.max-bytes | CACHE_MAX_BYTES | 2 GiB | local disk cache of pcap files fetched from S3 |
| upload.expire-minutes | UPLOAD_EXPIRE_MINUTES | 120 | an uploaded but unsaved file is discarded after this |
| replay.connect-timeout-ms | REPLAY_CONNECT_TIMEOUT_MS | 5000 | TCP server mode connect timeout |
| replay.max-active-replays | REPLAY_MAX_ACTIVE_REPLAYS | 10 | replays running at once, all users together (waiting ones count) |
| replay.group-connect-timeout-ms | REPLAY_GROUP_CONNECT_TIMEOUT_MS | 300000 | records played together wait this long for all TCP clients to connect |
| replay.tcp-keep-trying | REPLAY_TCP_KEEP_TRYING | true | TCP server replays play without a connection and retry it; false = fail with CONNECT_FAILED / CONNECTION_LOST |
| replay.tcp-retry-interval-ms | REPLAY_TCP_RETRY_INTERVAL_MS | 1000 | how often such a replay retries the connection |
| extraction.fragment-timeout-ms | EXTRACTION_FRAGMENT_TIMEOUT_MS | 30000 | incomplete IPv4 fragment sets are dropped after this |
| extraction.tcp-gap-timeout-ms | EXTRACTION_TCP_GAP_TIMEOUT_MS | 2000 | a TCP hole not covered by an ACK becomes a gap after this |
| extraction.udp-duplicate-window-ms | EXTRACTION_UDP_DUPLICATE_WINDOW_MS | 5 | identical UDP datagrams within this window count once |
| extraction.cache-max-bytes | EXTRACTION_CACHE_MAX_BYTES | 256 MiB | parsed captures kept in memory (0 turns it off) |
| history.size | HISTORY_SIZE | 20 | runs kept in history |
| histogram.max-bins | HISTOGRAM_MAX_BINS | 120 | bars in a record's graph |
| archive-s3.endpoint / region / bucket | ARCHIVE_S3_ENDPOINT / _REGION / _BUCKET | *(empty bucket = off)* | the organization's archive of recordings, a **separate, read-only** S3 used as an auto-tagging source |
| archive-s3.access-key / secret-key | ARCHIVE_S3_ACCESS_KEY / ARCHIVE_S3_SECRET_KEY | *(empty = AWS default chain)* | never sent to the browser |
| archive-s3.path-style | ARCHIVE_S3_PATH_STYLE | false | `true` for MinIO and most S3-compatible servers |
| archive-s3.root-prefix | ARCHIVE_S3_ROOT_PREFIX | *(empty)* | the archive browser cannot go above it |
| auto-tag.max-files-per-run | AUTO_TAG_MAX_FILES_PER_RUN | 500 | a bigger selection is refused before the run starts |
| auto-tag.workers | AUTO_TAG_WORKERS | 4 | files processed in parallel within one run |
| auto-tag.max-concurrent-jobs | AUTO_TAG_MAX_CONCURRENT_JOBS | 1 | runs at once; others wait in a queue |
| auto-tag.float-tolerance | AUTO_TAG_FLOAT_TOLERANCE | 0.000001 | float equality tolerance in rules |

**Memory**: JVM options go in `JAVA_OPTS` (the image uses `-XX:MaxRAMPercentage=75`). A running
replay holds its record's payload in memory, and up to `extraction.cache-max-bytes` of parsed
captures are kept for reuse. Size the container for the largest records you play at the same time
plus that cache (see [Resources and behavior under load](#resources-and-behavior-under-load)).

**Logging** uses `java.util.logging`. The AWS SDK logs through SLF4J, which has no binding here, so
SLF4J prints one "no providers" line at startup and the SDK stays quiet.

---

## Deploying

### The image

```bash
docker build -t pcap-player:1.0.0 .
```

The Dockerfile has three stages: build the UI, build the jar with the UI inside, then a small JRE
runtime with libpcap. Optional build arguments:

| Build arg | Use |
|---|---|
| `REGISTRY` | base-image registry prefix, e.g. `nexus.local/` |
| `NPM_REGISTRY` | npm mirror URL |
| `MAVEN_MIRROR_URL` | Maven mirror URL |
| `RUN_TESTS=true` | run the backend unit tests during the build |

Run it with your configuration file mounted:

```bash
docker run -p 8080:8080 -v "$PWD/config/application.yml:/app/config/application.yml:ro" pcap-player:1.0.0
```

### OpenShift / Kubernetes

- **Configuration**: keep the S3 address and secrets in a Secret made from your file, and mount it
  where the app looks:

  ```bash
  oc create secret generic pcap-player-config --from-file=application.yml=config/application.yml
  ```

  ```yaml
  # in the Deployment's pod spec
  containers:
    - name: pcap-player
      volumeMounts:
        - { name: config, mountPath: /app/config, readOnly: true }
  volumes:
    - name: config
      secret: { secretName: pcap-player-config }
  ```
- **Run exactly one replica.** Running replays live in that pod's memory.
- **Probes:** liveness `GET /health/live`. Readiness `GET /health/ready`: metadata loaded and the
  bucket reachable. On startup the app retries loading metadata every 5 s until S3 answers.
- **User:** runs as any non-root UID in group 0. `/app` and `/tmp/pcap-player` are group-0
  writable, and all temp files are under `/tmp`.
- **Ports:** expose 8080 (HTTP and WebSocket on the same port). TCP-client-mode replays also
  listen on the ports users choose, so those must be reachable from the service under test
  (Service/NodePort or pod networking).
- **No capture privileges** are needed: files are only read, never captured live.
- **Resources**: see below.

### Resources and behavior under load

The app runs as one pod, and at most `replay.max-active-replays` (default 10) replays run in it at
once. Each running replay is one thread. To keep send times precise, a thread parks until just
before a message is due and then **spin-waits** the last stretch (0.2–10 ms, adapted to how late
the OS wakes threads). Spinning keeps a core busy, so only *half the CPUs the JVM may use*
(rounded down) spin at the same time; the others park instead and are late by the OS wake-up time,
which is well under 1 ms on Linux. The JVM takes its CPU count from the container's CPU limit, so
a 1-CPU pod does not spin at all and a 2-CPU pod spins one thread at a time. Records started with
Play together are the exception: every member spins for the shared start instant.

Measured with `tools/load_test.py` (10 replays at once, 4 UDP + 3 TCP server + 3 TCP client, each
500 messages/s for 15 s, on a Windows laptop with the JVM pinned to the given CPUs; Windows wakes
parked threads much later than Linux, so on a Linux pod the timing is better):

| CPUs | Version | Server CPU | Arrival vs. due time: median / p99 / worst | `GET /health/live` median / p99 |
|---|---|---|---|---|
| 2 | before (every thread spins) | 187% (both CPUs full) | 45 / 233 / 345 ms | 39 / 830 ms |
| 2 | now | 50% | 5 / 25 / 53–112 ms | 2–6 / 135–150 ms |
| 1 | now, after JIT warm-up | 10% | 5 / 17 / 53 ms | 21 / 153 ms |

Every message arrived in every run, and an 11th replay was refused with `ACTIVE_LIMIT`. On an idle
server the probe's own p99 is ~30 ms. The heap held ~45 MB during the run (process ~150 MB):
the replay engine's own memory is small, the script payloads are what grows.

**Recommended pod resources** for the default limit of 10:

```yaml
resources:
  requests: { cpu: "1",  memory: 1Gi }
  limits:   { cpu: "2",  memory: 2Gi }   # 75% of it (MaxRAMPercentage) is the heap
```

- **CPU**: 1 CPU is enough for 10 replays; 2 lets one replay at a time spin for sub-millisecond
  precision and keeps the UI responsive while records are parsed. Do not set the limit far below
  the request: CFS throttling stalls every thread, the replays included.
- **Memory**: a replay holds its record's payload; add the largest records played at the same time
  to `extraction.cache-max-bytes` (default 256 MB) and ~300 MB for the JVM. Raise the limit for
  records of hundreds of MB.
- Thread priorities (replay threads run at maximum priority) are ignored by the JVM on Linux unless
  it runs as root with `-XX:ThreadPriorityPolicy=1`, so they do not starve anything; replays waiting
  for a connection or a group start block without using CPU.

To measure your own setup, run the server (pinning its CPUs if you like) and in another terminal:

```bash
python3 tools/load_test.py --base http://localhost:8080 --replays 10 --rate 500 --seconds 20 --pid <server pid>
```

It writes and uploads three captures, starts the replays against local sockets, tries one more,
probes `/health/live` (event loop) and `/api/sessions` (worker pool) every 100 ms, and prints, per
replay, how late each message arrived relative to the first, plus HTTP latencies and the server's
CPU (via `psutil` if installed, `/proc` on Linux, or the Windows API). Simulate a pod's CPU limit
with `taskset -c 0,1 java -XX:ActiveProcessorCount=2 -jar …` (Linux) or
`start "" /affinity 3 java -XX:ActiveProcessorCount=2 -jar …` (Windows).

---

## Code structure

```
backend/                          Maven project; all versions pinned in pom.xml <properties>
  src/main/java/com/pcapplayer/
    Main.java              wiring: config → storage → catalog → replay engine → HTTP server
    configuration/         AppConfig (typed settings), ConfigLoader (defaults → your config file → env)
    web/                   HTTP server wiring (ApiServer) and shared request/response helpers (Http)
      routes/              one *Routes class per area: parse the request, call a service
      live/                LiveHub: pushes running replays and change hints over the WebSocket
    pcapProcessing/        everything that reads packets, in pipeline order
      capture/             reading pcap/pcapng files through libpcap (pcap4j); PcapInputOpener (gzip and pcap detection)
      decode/              link-layer decoding, IPv4 fragment reassembly, TCP/UDP packets
      flow/                rebuilding flows: TCP stream reassembly, UDP de-duplication
      summary/             the upload summary: CaptureScanner, CaptureSummary, Timeline (graph data)
      extract/             packets → replay script: MessageExtractor, its filter/settings/result, CachingExtractor
      script/              the replay script itself: ReplayScript, ScriptStep, ScriptFlow, EConnectionMode, Histogram
      diagnostics/         Warning: problems found in a capture (truncation, gaps, duplicates, ...)
    icd/                   ICDs (interface control documents) for decoding messages; see docs/icd-json-format.md
      model/               the format-agnostic model: IcdDefinition, HeaderDef, MessageDef, FieldDef, ...
      annotations/         @Icd, @IcdMessage, @IcdField, ... for ICDs written as Java classes
      loading/             AnnotationIcdLoader, JsonIcdLoader, IcdModelValidator (rules shared by both), BuiltInIcds,
                           IcdRegistry (the ICDs the server knows)
        annotationJson/    AnnotationJsonIcdLoader: the annotation-style JSON ("format": "annotations"), translated
                           into the same model (ClassTranslator, BitGroup, JavaTypes)
      decode/              script → messages: ScriptDecoder, FlowFramer (per-flow message reassembly),
                           MessageDecoder (bytes → field values and positions), DecodedMessage, DecodeStats
      analysis/            MessageAnalyzer (consumer of decoded messages), MessageCountAnalyzer, DecodeReport
      timefix/             time fixing: ETimeKind, TimeCodec (value ↔ instant), TimeAnchoring, FieldWriter,
                           TimeFixPlanner (decoded messages + profile → PatchPlan), MessagePatch, StepCopies
      profile/             TimeFixProfile: per category, the base field and fields to fix of each message;
                           TimeFixProfileValidator
      tree/                IcdTree: an ICD's structure for the UI (paths, types, sizes, which fields hold numbers)
      versions/            one sub-package per ICD version written in Java
    autotag/               auto tagging; see docs/auto-tagging.md
      rules/               TagRule, RuleCondition, FieldPredicate, EOperator, EValueType, RuleValidator,
                           PredicateEvaluator (operator semantics), PathPattern ([*] = any element),
                           CompiledRules, RuleMatchAnalyzer (a MessageAnalyzer: one counter per condition)
      archive/             ArchiveStore (read-only interface), S3ArchiveStore, ArchiveService, ArchivePaths,
                           TimeSpan, ArchiveSelection (LastModified filter)
      job/                 TagJobService (create, uploads, queue, cancel, restart), TagJobRunner (worker pool),
                           FileTagger (one file: open → decode → evaluate → save), ServicePorts, TagJob, FileResult
    recordManagement/
      service/             business rules: RecordService (preview, create, edit, delete), TaxonomyService
                           (categories, tags), PlaylistService, RecordScriptSource (records → replay
                           scripts), IcdService (ICDs, category → ICD), TimeFixService (profiles),
                           RecordDecodeService (debug decode), TagRuleService (auto-tagging rules),
                           CatalogService (entry point),
                           CatalogSupport (shared lock and helpers)
      model/               the stored data: Record, Category, Tag, PcapFile, SavedPlaylist, RecordStats, TaggingStats
      repository/          CatalogRepository: all metadata in memory, written through to storage;
                           TaggingIndex (records by matched rule and message count, for the list filters)
    uploadHandling/        UploadService: uploads in progress (hash and scan)
    playback/              the engine: ReplayEngine (sessions, target locks), ReplaySession (one thread
                           per session), ItemPlayer (timing and seeking), PreciseClock, Control
      model/               what other packages exchange with the engine: SessionView, HistoryEntry,
                           ItemRequest, Target, ScriptSource, ReplayListener
      transport/           TCP/UDP sockets for each connection mode
    persistence/           BlobStore (the storage interface), FileCache (local copies of pcap files)
      s3/                  S3BlobStore: AWS S3 and S3-compatible servers; S3Clients (shared with the archive)
      filesystem/          FileSystemBlobStore: a local folder (local use)
    errorHandling/         ApiException: the app's error type ({code, message, params} with an HTTP status)
    common/                Json, Strings, Secrets (masking)
  src/test/java/           unit tests (JUnit 5), in the same packages as the code they test

frontend/src/
  api/                     client.ts (one function per endpoint), types.ts (mirrors the backend JSON), live.ts (WebSocket)
  state/                   React contexts: catalog, live sessions, playlist draft, upload in progress, feedback (popups, toasts, useAction)
  pages/                   one folder per page: player/, upload/ (and its parts), catalog/, history/, playing/, timefix/, autotag/
  components/
    common/                small reusable pieces: Modal, Icon, Badges, Stat, TimeField, Sortable (drag-to-reorder)
    layout/                app frame: Header, BottomNav, FeedbackView (toasts, popups), NicknameDialog
    graphs/                BarGraph (live progress, seek), RangeGraph (choose a time range), graph math
    records/               RecordCard, EditRecordDialog, RecordPicker, BulkTagDialog, CategorySelect and TagInput (pick or create in place)
    playback/              NowPlaying, LiveView, PlayDialog, TargetFields
    playlist/              PlaylistDrawer
    icd/                   IcdsCard (upload, list, download, delete ICDs), IcdTreeView (a message's field tree, shared)
    timefix/               MessageTimeFixCard (one message's field tree with base / fix / kind)
    autotag/               RuleEditorDialog, RulesPicker, SourcePicker (the four sources), ArchiveBrowser, MessageCounts
  util/                    plain functions: time (date-fns), targets, record filters, browser storage,
                           timefix (editing a time-fix profile draft), tagRules (rule drafts and summaries),
                           localFiles (local folder filtering)
  i18n/                    i18next setup plus en.ts and he.ts (Hebrew must have every English key, or it does not compile)

docs/                             icd-json-format.md (ICD format), time-fix.md (time kinds and formulas),
                                  time-fix-plan.md (design of time fixing), auto-tagging.md (rules, sources,
                                  gzip, settings), auto-tagging-plan.md (design of auto tagging)
config/application.example.yml    template for your configuration (S3 address and secrets)
tools/gen_samples.py              generates test captures with known expected output
tools/gen_demo.py                 writes three plain demo captures (TCP server, TCP client, UDP) to try by hand
tools/peer.py                     a stand-in service on this machine that prints what a replay delivers
tools/load_test.py                10 replays at once against a running server: timing, HTTP latency, CPU
tools/icd-converter/              annotated Java classes (legacy @Ordinal, @Bits, ...) → annotation-style ICD JSON
skills/icd-from-annotations/      the same conversion as a skill for a language model (SKILL.md, examples, checker)
Dockerfile, docker-compose.yml
```

### Conventions

- **Names say what a value is**: `record`, `session`, `packet`, `tsNanos`, never `r`, `s`, `p`. The
  only short names are `i`/`j` for loop counters, `e` for a caught exception or UI event, and
  i18next's `t`/`tc`. JSON fields keep their API names (for example `by`).

- **Layers**: routes only parse requests and call a service. Business rules live in the
  services. Storage access goes only through `CatalogRepository`.
- **Errors**: throw `ApiException(status, code, params, message)`, or `ReplayFailure` in replay
  code (it picks its own HTTP status). The UI translates `code` with `err.<CODE>` in both i18n files.
- **Catalog writes** go through `CatalogSupport.mutate(...)`: it takes the shared lock and notifies
  every viewer. Slow work (parsing, uploading) is done before it, outside the lock.
- **Frontend server calls** go through `useAction().run(...)`, which handles the busy state, error
  popups and success toasts. Keep components free of try/catch boilerplate.
- **Times** are epoch microseconds everywhere in the API. The UI shows Israel time
  (`Asia/Jerusalem`, `DD/MM/YYYY HH:MM:SS`).
- **Prefer a library** over hand-written code whenever one does the job (for example Caffeine for
  caching, i18next for translations, date-fns for time zones, dnd-kit for drag-and-drop). Code is
  hand-written only where no library fits: the packet pipeline (IPv4 fragment reassembly, TCP stream
  rebuilding, UDP de-duplication), encapsulations pcap4j can't decode, and the precise replay clock.

### Adding things

- **A UI string**: add the key to `i18n/en.ts` and `i18n/he.ts`. Placeholders are written `{name}`.
- **A server error or warning**: use a new `code`, then add `err.<CODE>` / `warn.<CODE>` to both
  dictionaries.
- **A storage backend**: implement `persistence/BlobStore`.
- **A link type**: one case in `pcapProcessing/decode/LinkDecoder`.
- **An ICD**: write it in JSON ([format](docs/icd-json-format.md)), or as annotated Java classes in
  `icd/versions/<version>/` with the holder class listed in `icd/loading/BuiltInIcds`. For message
  classes written with the legacy annotations (`@Ordinal`, `@Bits`, `@Unsigned` ...), see
  [ICDs from legacy annotated classes](#icds-from-legacy-annotated-classes).
- **An endpoint**: add a method to the right service, a route in the matching `web/routes/*Routes` class, and a
  function in `frontend/src/api/client.ts`.

---

## How it works

### Reading captures

Files are opened with libpcap (`pcap_open_offline`, nanosecond precision) through pcap4j, so classic
pcap (both byte orders, µs and ns) and pcapng (`if_tsresol`, `if_tsoffset`) are handled by libpcap
itself. pcap4j decodes Ethernet, 802.1Q VLAN, Linux SLL, BSD loopback, raw IP, IPv4, TCP and UDP.
For encapsulations pcap4j 1.8 does not know (Linux SLL2, QinQ, `DLT_LOOP`, `DLT_IPV4`, big-endian
loopback), `LinkDecoder` finds the IPv4 header itself. **Only IPv4 TCP/UDP is used**; everything
else (including IPv6) is skipped.

**Limitation**: libpcap rejects a pcapng file whose interfaces have *different* link types (e.g.
dumpcap capturing `eth0` and `any` at once). Such an upload fails with "Unsupported capture".

Receive-side artifacts are handled:
- **IPv4 fragments** are reassembled (by src, dst, protocol, IP ID) **before** port filtering, even
  when they arrive out of order. A reassembled datagram is one message, timed at the fragment
  that completed it. Incomplete sets are dropped and reported.
- **GRO/LRO** super-packets: the IP total length is trusted only when it matches the captured bytes.
- **Snaplen truncation**: TCP keeps what was captured and the rest becomes a gap; truncated UDP
  datagrams are dropped. Both are reported.
- **Duplicates** (the same packet captured on two interfaces): TCP reassembly removes them; UDP
  drops an identical datagram (same 5-tuple, IP ID and payload) seen within 5 ms.
- Checksums are not validated.

### Building a record

- On upload, one pass builds the **summary**: packet counts, ports with their IPs, and the
  packets-over-time **timeline** for the graph. Timeline buckets start at 1 ms and double in width
  until the capture fits in 256 bars.
- The time range is applied after reassembly, so a range starting mid-connection starts the stream
  at the first in-range data.
- **TCP streams** are rebuilt by sequence number: retransmissions dropped, overlaps trimmed,
  out-of-order data held until the hole fills. Each time bytes become readable they form one
  message, timed when they became readable.
- **Gaps**: a hole is declared lost when the service's own ACK already covers it, after
  `tcp-gap-timeout-ms`, or at the end of the stream. It is skipped and reported.
- Consecutive connections become *close → reconnect* steps. Overlapping connections are merged in
  time order, with a warning.
- **Parsing is cached**: the result for a capture and filter is kept in memory (keyed by the file's
  SHA-256), so the live preview, saving, editing and every replay reuse it instead of re-reading
  the file.

### Replaying

- One thread per running session, at maximum priority. Every send time is `start + offset`, never
  relative to the previous send, so there is no drift.
- The clock parks, then spin-waits the last stretch. The spin margin adapts to how late the OS
  wakes threads, so it spins only as long as this host needs. On a laptop, sends on a 1 ms grid
  land within ~2 µs; with 20–200 ms gaps the worst case is ≈ 1 ms. Only half the CPUs spin at once
  (see [Resources and behavior under load](#resources-and-behavior-under-load)).
- **Slots**: a replay takes its slot before it starts (a group takes one per record, all or none)
  and gives it back exactly once, in its thread's last step, however it ends: completed, stopped,
  failed, a failed group member or connect timeout, or stopped before its thread ran. If its thread
  cannot even be created (`START_FAILED`, HTTP 503), the slot, target and port are given back at
  once, and in a group the members already running end with `GROUP_FAILED`. On shutdown the
  engine refuses new replays (`SHUTTING_DOWN`), stops all and waits up to 1 s for them. Giving back
  more slots than were taken is logged as a warning.
- **Jumping** (seek): playback continues from the chosen point at normal speed. Messages jumped
  over are not sent, and jumping back re-sends from that point. A TCP connection stays open across
  a jump.
- **TCP server mode**: connect; a failed or dropped connection fails the replay. `TCP_NODELAY`, one
  write per message.
- **UDP server mode**: datagrams from one local port.
- **TCP client mode**: listen; timing starts when the service connects. A recorded reconnect waits
  for the service to reconnect and shifts the rest of the schedule.
- Replies are read and discarded by a separate thread.
- Two replays can't use the same target IP:port or listen port; this is checked before anything starts.
- A replay runs to the end of the selected range, then reports "completed".
- **Playlists** run items in order with a pause after each. While one item plays, the next is
  prepared in the background. A failed item is recorded in history and the playlist continues.
- **Time fixing** (categories with an ICD and a time-fix profile; see [docs/time-fix.md](docs/time-fix.md)):
  - While a record is prepared, its messages are decoded and a patch plan is built. The plan is
    cached, so replaying the record again does not decode again.
  - Right before a message's first packet is sent, its time fields get the real send time, written
    into copies of its packets. The cached script is never modified.
  - Packets without time fields are sent as they are, and records without a profile take the
    unchanged code path.
  - After a jump into the middle of a message, the message is patched at its first remaining packet.

### Decoding messages (ICD)

A category can have an ICD (see [docs/icd-json-format.md](docs/icd-json-format.md)). Its records can
then be cut into messages and decoded:

- The decoder reads the record's **replay script**: the same bytes playback sends, with IP fragments
  and TCP segments already reassembled. Each `Send` step remembers its recorded flow (a TCP
  connection, or a UDP source/destination pair).
- Per flow, the bytes are cut into messages using the header's opcode and length. A message may span
  several packets, and one packet may hold several messages. Each decoded message keeps its field
  values and, for every field, the packets and offsets its bytes came from.
- Problems never stop anything. The bytes involved are passed through untouched, counted, and logged
  at most once per 10 s per kind:
  - an unknown opcode: skipped by its length
  - an impossible length: resync at the next packet
  - a message cut by a capture gap or the end of the record
  - a message that does not match its definition
- `GET /api/records/{id}/decode` shows the result, to check an ICD against real traffic. Add
  `?icd=<id>` to try an ICD before assigning it to the category. A field with a fixed point shows its
  real value; one that is out of its valid range or holds its invalidity value says so
  (`360.5 (OUT_OF_RANGE)`).

### ICDs from legacy annotated classes

Legacy systems describe each message as a Java class whose fields carry annotations from the
`iaf.ofek.oryekarot.general.io` library (`@Ordinal`, `@Bits`, `@Unsigned`, `@Size`, `@LengthEncoded`,
`@FixedPoint`, `@Enumerable` ...). PCAP Player reads such an ICD as JSON in the **annotation style**:
the same classes and fields, one JSON property per annotation (`"format": "annotations"`,
[reference](skills/icd-from-annotations/reference/annotations.md)). The loader turns it into the usual
model: fields read by ordinal, consecutive `@Bits` cut from one chunk, sizes from the Java types and
`@Unsigned`, lengths in bytes or written before the array. Fields read by code (`@ExistenceEncoded`,
`@Custom`, `@Encoded`) end the decodable part of a message; the rest of its bytes are kept and sent as
they are.

There are two ways to write that JSON from the classes:

- **The converter** (Java 17): reads the `.java` files with JavaParser, so it needs neither the
  annotation library nor compiled classes. Give it the source folder and a small config naming the
  header class, its opcode and length fields, and each message's opcode (the annotations do not say):

  ```bash
  cd tools/icd-converter && mvn -q package
  java -jar tools/icd-converter/target/icd-converter.jar path/to/sources icd-config.json gw.icd.json
  ```

  It prints a warning for every field it skipped (no `@Ordinal`), every field read by code, and every
  annotation it does not know. A config looks like
  `skills/icd-from-annotations/examples/01-basic/input/icd-config.json`.
- **The skill** `skills/icd-from-annotations/` for a language model (for example Qwen): give it
  `SKILL.md` (and `reference/annotations.md`) with the classes and the config. It checks its own output
  with `scripts/check_icd.py`. To measure a model, give it each `examples/<name>/input`, save its answers
  as `OUT/<name>.icd.json`, and run `python3 skills/icd-from-annotations/scripts/score_outputs.py OUT`.

The examples under `skills/icd-from-annotations/examples/` are shared: the converter's tests convert
each one and compare with its `expected.icd.json`, the skill's tests check those files, and the backend
tests load them and decode real bytes with them. The open assumptions (bit order inside a chunk, and how
`@Unsigned` sizes a field) are listed at the end of the reference.

### Auto tagging

Each file of a run goes through `FileTagger` on one of `auto-tag.workers` threads. The steps:

1. `PcapInputOpener` removes a gzip layer (detected by `1F 8B`) and checks the pcap/pcapng magic
   number, streaming into a temp file while hashing it.
2. `ServicePorts` finds the server ports (TCP: from the SYNs; otherwise the lower port of each pair),
   and the whole capture is extracted with them.
3. `ScriptDecoder` decodes it **once**, feeding `MessageCountAnalyzer` (full statistics),
   `RuleMatchAnalyzer` (one counter per rule condition) and a port collector.
4. At the end the rules are evaluated. A matching file goes through `RecordService.saveTagged`: a new
   record through the normal create path, or, if this category already has a record of the same
   SHA-256, that record gets the new statistics. Saves take turns, so two copies of the same file in
   one run cannot both create a record.

Rule semantics (operators, float tolerance, `[*]` = any element, each predicate on its own) are in
[docs/auto-tagging.md](docs/auto-tagging.md).

### Storage layout (one bucket)

```
pcaps/{fileId}.pcap|pcapng   capture, stored once per SHA-256
pcaps/{fileId}.json          name, size, format, uploader, scan summary (with timeline)
records/{recordId}.json      name, description, category, tags, ports, IP filter, range, stats
categories/{id}.json         name, connection mode and ICD id
icds/{icdId}.json            an uploaded ICD, exactly as uploaded
timefix/{categoryId}.json    the category's time-fix profile
tagrules/{categoryId}.json   the category's auto-tagging rules (and the ICD they were saved for)
tagjobs/{jobId}.json         an auto-tagging run: source, rule snapshot, status, progress, per-file results
playlists/{id}.json          saved playlist: name and items (record, target, pause)
tags.json                    all tags
history.json                 the last runs, newest first
```

All metadata is loaded into memory at startup (fetched in parallel) and written through on every
change. The S3 client sends checksums only when required. AWS SDK 2.30+ otherwise sends
`aws-chunked` bodies, which some S3-compatible servers store as part of the object.

---

## API

REST under `/api` (JSON). Times are epoch microseconds. Errors are
`{"code": "...", "message": "...", "params": {...}}`.

| Endpoint | |
|---|---|
| `GET /api/catalog` | categories, tags, records, files, saved playlists |
| `POST /api/uploads?fileName=&uploadedBy=` | raw file body (streamed to disk; gzipped pcaps are decompressed) → upload id + summary |
| `DELETE /api/uploads/{id}` | discard an unsaved upload |
| `GET /api/files/{id}/summary` | a stored file's summary |
| `POST /api/preview` | what a draft record would contain (stats, warnings, errors) |
| `POST /api/records` | create; `409 DUPLICATE` unless `resolution: "NEW_NAME"` |
| `POST /api/records/tags` | add and remove tags on several records: `{recordIds, add, remove}` → `{updated}` |
| `PUT` / `DELETE /api/records/{id}`, `GET /api/records/{id}/download` | edit (name, description, times, `categoryId` with the same mode or `422 CATEGORY_MODE_MISMATCH`, `tagIds`), delete, download the capture |
| `POST` / `PUT` / `DELETE /api/categories[/{id}]`, `GET /api/categories/{id}/usage` | categories; `DELETE ?moveTo={id}` moves the records to a category with the same mode instead of deleting them |
| `POST` / `PUT` / `DELETE /api/tags[/{id}]`, `POST /api/tags/{id}/merge` | tags; merge replaces the tag with `{intoId}` on every record, then deletes it |
| `GET /api/icds`, `GET /api/icds/{id}/tree`, `GET /api/icds/{id}/json` | ICDs (Java and uploaded), one ICD's structure, an uploaded ICD's file |
| `POST /api/icds` (body: the ICD JSON file, `content-type: application/json`), `DELETE /api/icds/{id}` | upload or replace an ICD (`422 ICD_INVALID` lists every problem with its JSON path); delete one no category uses |
| `PUT /api/categories/{id}/icd` | `{icdId}` (null to clear): the ICD the category's messages follow |
| `GET` / `PUT /api/categories/{id}/timefix` | the category's time-fix profile with its ICD tree and warnings / save `{messages: [{opcode, base: {path, kind}, fields: [...]}]}` |
| `GET /api/records/{id}/decode?limit=&opcode=&icd=` | debug: the record decoded with its category's ICD (or `icd`): counts per opcode, decode problems, the first `limit` messages (default 100) with fields and byte locations |
| `GET /api/records/search?categoryId=&rule=&opcode=&minCount=` | ids of records with that matched rule and/or at least `minCount` messages of `opcode` (auto-tagging statistics) |
| `GET` / `POST /api/categories/{id}/tag-rules`, `PUT` / `DELETE /api/categories/{id}/tag-rules/{ruleId}` | auto-tagging rules: list (with the ICD tree and warnings), create, edit, delete; `422` lists every problem |
| `GET /api/auto-tag/capabilities` | `{archiveConfigured, maxFilesPerRun}` |
| `GET /api/archive/browse?prefix=`, `POST /api/archive/preview` | browse the archive S3 / count the files (and bytes) a selection holds |
| `GET` / `POST /api/tag-jobs`, `GET /api/tag-jobs/{id}?status=` | auto-tagging runs: list, create (`422 TAGJOB_TOO_MANY_FILES` above the limit), one run with its per-file results |
| `POST /api/tag-jobs/{id}/files/{index}` (raw body), `POST /api/tag-jobs/{id}/start`, `POST /api/tag-jobs/{id}/cancel` | upload a local file of an `UPLOADING` run, queue it once uploaded, cancel |
| `POST` / `PUT` / `DELETE /api/playlists[/{id}]` | saved playlists |
| `GET /api/history` | recent runs |
| `GET` / `POST /api/sessions` | running replays / start one (a single replay or a playlist; optional `playlistName` is recorded in History) |
| `POST /api/sessions/group` | play 2 or more records together (one replay each, common start, all or nothing; waits for TCP clients); fewer than 2 → 400 `GROUP_TOO_SMALL` |
| `POST /api/sessions/{id}/stop` | stop |
| `POST /api/sessions/{id}/seek` | jump the current item to `positionUs` |
| `PUT /api/sessions/{id}/upcoming` | replace a running playlist's upcoming items |
| `GET /health/live`, `GET /health/ready` | probes |

WebSocket `/ws` (server → client): `sessions` (on every change and 5 times a second while playing,
with `active`/`max` for the global limit),
`notice` (failure popups), `catalog` and `history` (hints to refetch).

---

## Testing

```bash
cd backend && mvn test                   # backend unit tests
cd frontend && npx tsc --noEmit -p .     # frontend type check (also part of npm run build)
cd frontend && npm test                  # frontend unit tests (Node's built-in test runner, no extra packages)
```

Frontend unit tests are `src/**/*.test.ts`; `npm test` compiles them with `tsconfig.test.json` and runs
them with `node --test`. They cover the History filters (`src/util/historyFilters.ts`) and the
calendar arithmetic (`src/util/dateRange.ts`).

Tests that read real capture files need libpcap; they are skipped when it is missing. On macOS
four pcapng tests fail because of Apple's libpcap (see
[Setting up a new computer](#setting-up-a-new-computer), step 6); everything else passes.

`tools/gen_samples.py OUT_DIR` (Python 3, standard library only) writes test captures plus
`expected.json`, which describes exactly what must be replayed. The captures cover:
- **UDP**: out-of-order fragments, a duplicate captured on a VLAN interface, an incomplete fragment
  set, snaplen truncation, Ethernet padding, ARP/IPv6 noise.
- **TCP**: out-of-order, retransmitted and overlapping segments, an ACK-confirmed gap, a 30 KB GRO
  super-packet with IP length 0, a closed connection followed by a new one.
- **TCP client mode**: sequence wrap-around, and a range starting mid-connection.
- **Formats**: big- and little-endian, pcap and pcapng, µs and ns resolution.

`tools/gen_samples.py` writes `tcp_server.pcapng` with two link types on purpose; libpcap rejects
it. To try the player by hand use `tools/gen_demo.py OUT_DIR` instead: `tcp_server_demo.pcap`
(port 6000), `tcp_client_demo.pcap` (port 7000) and `udp_demo.pcap` (ports 5000 and 5001), every
message a readable text line. To watch a replay arrive locally, run `tools/peer.py`:

| Record mode | Start first | Then |
|---|---|---|
| TCP server | `python tools/peer.py tcp-listen 6000` | play to `127.0.0.1:6000` |
| TCP client | play, listening on port 7000 | `python tools/peer.py tcp-connect 127.0.0.1 7000` |
| UDP server | `python tools/peer.py udp-listen 5000` | play to `127.0.0.1:5000` |

A TCP server replay plays even when nothing listens on the target: it keeps its schedule, retries
the connection every second (`replay.tcp-retry-interval-ms`), and the messages due while there is
no connection are not delivered ("8 not delivered" in Now playing and History; the log says
"Cannot connect … — playing anyway"). Start `peer.py tcp-listen` in the middle of a replay and it
gets the messages from that moment on; a connection that drops is retried the same way. With
`replay.tcp-keep-trying: false` it fails instead (`CONNECT_FAILED` / `CONNECTION_LOST`). UDP needs no
listener (datagrams are just sent), and a TCP client replay waits for the service to connect.

`tools/load_test.py` plays 10 replays at once against a running server and reports timing, HTTP
latency and CPU; see [Resources and behavior under load](#resources-and-behavior-under-load).

---

## Dependencies

Check these against your internal mirrors.

### Backend (all inside the fat jar)

| Artifact | Version |
|---|---|
| io.vertx:vertx-core, vertx-web | 4.5.21 |
| com.fasterxml.jackson.core:jackson-databind, jackson-dataformat-yaml | 2.18.4 |
| software.amazon.awssdk:s3 (netty-nio-client and apache-client **excluded**), url-connection-client | 2.31.30 |
| org.yaml:snakeyaml (pinned; Vert.x would otherwise force 2.0) | 2.3 |
| org.pcap4j:pcap4j-core, pcap4j-packetfactory-static | 1.8.2 |
| net.java.dev.jna:jna (pinned; pcap4j asks for 5.3.1) | 5.14.0 |
| com.github.ben-manes.caffeine:caffeine (in-memory cache of parsed captures) | 3.1.8 |
| org.junit.jupiter (tests only) | 5.11.4 |

Full runtime tree: `mvn dependency:list -DincludeScope=runtime`. Build plugins:
maven-compiler-plugin 3.13.0, maven-resources-plugin 3.3.1, maven-jar-plugin 3.4.1,
maven-shade-plugin 3.6.0, maven-surefire-plugin 3.5.2. Maven 3.9.9, JDK 17.

### Frontend

| Package | Version | Kind |
|---|---|---|
| react, react-dom | 18.3.1 | runtime |
| react-router-dom | 6.28.0 | runtime |
| i18next / react-i18next | 24.2.3 / 15.7.4 | runtime: translations |
| date-fns / @date-fns/tz | 4.4.0 / 1.5.0 | runtime: Israel-time formatting and parsing |
| @dnd-kit/core / sortable / utilities | 6.3.1 / 10.0.0 / 3.2.2 | runtime: drag-to-reorder (mouse, touch, keyboard) |
| use-debounce | 10.1.1 | runtime: upload preview delay |
| reconnecting-websocket | 4.4.0 | runtime: live connection auto-reconnect |
| pretty-bytes | 6.1.1 | runtime: file sizes |
| vite | 5.4.11 | build |
| @vitejs/plugin-react | 4.3.4 | build |
| typescript | 5.6.3 | build |
| @types/react / @types/react-dom | 18.3.12 / 18.3.1 | build |

Node 20.18.1. The full tree is pinned in `frontend/package-lock.json`.

### Docker base images

`node:20.18.1-alpine3.20`, `maven:3.9.9-eclipse-temurin-17`, `eclipse-temurin:17.0.13_11-jre-jammy`.
