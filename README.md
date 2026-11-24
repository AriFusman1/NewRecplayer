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
- **Remembers your targets**: each record pre-fills the IP and port it was last played to, and
  IP fields suggest your recent IPs. This is stored in your browser only.
- **Shared**: everyone sees every running replay live and can stop it or edit upcoming items.
- English and Hebrew (RTL) UI.

**Tech**: Java 17 + Vert.x backend, React 18 + TypeScript frontend, one S3 bucket for storage (no
database), shipped as one Docker image (OpenShift-friendly).

---

## Contents

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

1. **Categories & Tags**: create a category first. Its *connection mode* is fixed:
   - **TCP server**: the player connects to the service (the service is a TCP server).
   - **UDP server**: the player sends UDP datagrams to the service.
   - **TCP client**: the player listens and the service connects to it.
2. **Upload**: drop a pcap/pcapng file. Then, in this order:
   - Drag across the summary graph to choose the time range, or type the start and end times.
   - Pick the category and the ports (and optionally an IP).
   - Check the preview on the right, then **Save record**.
3. **Player**: find records by search, category, mode, tags or time. Then either:
   - **Play** a single record, giving its target IP:port (or the port to listen on).
   - **Add to playlist**, or open the playlist and use **Add records…**.
4. **Now Playing** (the bar at the bottom): live progress for every running replay. Click or drag
   on the graph to jump. Stop a replay, or edit the upcoming items of a playlist.
5. **History**: the last runs and how they ended.

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
| extraction.fragment-timeout-ms | EXTRACTION_FRAGMENT_TIMEOUT_MS | 30000 | incomplete IPv4 fragment sets are dropped after this |
| extraction.tcp-gap-timeout-ms | EXTRACTION_TCP_GAP_TIMEOUT_MS | 2000 | a TCP hole not covered by an ACK becomes a gap after this |
| extraction.udp-duplicate-window-ms | EXTRACTION_UDP_DUPLICATE_WINDOW_MS | 5 | identical UDP datagrams within this window count once |
| extraction.cache-max-bytes | EXTRACTION_CACHE_MAX_BYTES | 256 MiB | parsed captures kept in memory (0 turns it off) |
| history.size | HISTORY_SIZE | 20 | runs kept in history |
| histogram.max-bins | HISTOGRAM_MAX_BINS | 120 | bars in a record's graph |

**Memory**: JVM options go in `JAVA_OPTS` (the image uses `-XX:MaxRAMPercentage=75`). A running
replay holds its record's payload in memory, and up to `extraction.cache-max-bytes` of parsed
captures are kept for reuse. Size the container for the largest records you play at the same time
plus that cache.

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
      capture/             reading pcap/pcapng files through libpcap (pcap4j)
      decode/              link-layer decoding, IPv4 fragment reassembly, TCP/UDP packets
      flow/                rebuilding flows: TCP stream reassembly, UDP de-duplication
      summary/             the upload summary: CaptureScanner, CaptureSummary, Timeline (graph data)
      extract/             packets → replay script: MessageExtractor, its filter/settings/result, CachingExtractor
      script/              the replay script itself: ReplayScript, ScriptStep, EConnectionMode, Histogram
      diagnostics/         Warning: problems found in a capture (truncation, gaps, duplicates, ...)
    icd/                   ICDs (interface control documents) for decoding messages; see docs/icd-json-format.md
      model/               the format-agnostic model: IcdDefinition, HeaderDef, MessageDef, FieldDef, ...
      annotations/         @Icd, @IcdMessage, @IcdField, ... for ICDs written as Java classes
      loading/             AnnotationIcdLoader, JsonIcdLoader, IcdModelValidator (rules shared by both), BuiltInIcds
      versions/            one sub-package per ICD version written in Java
    recordManagement/
      service/             business rules: RecordService (preview, create, edit, delete), TaxonomyService
                           (categories, tags), PlaylistService, RecordScriptSource (records → replay
                           scripts), CatalogService (entry point), CatalogSupport (shared lock and helpers)
      model/               the stored data: Record, Category, Tag, PcapFile, SavedPlaylist, RecordStats
      repository/          CatalogRepository: all metadata in memory, written through to storage
    uploadHandling/        UploadService: uploads in progress (hash and scan)
    playback/              the engine: ReplayEngine (sessions, target locks), ReplaySession (one thread
                           per session), ItemPlayer (timing and seeking), PreciseClock, Control
      model/               what other packages exchange with the engine: SessionView, HistoryEntry,
                           ItemRequest, Target, ScriptSource, ReplayListener
      transport/           TCP/UDP sockets for each connection mode
    persistence/           BlobStore (the storage interface), FileCache (local copies of pcap files)
      s3/                  S3BlobStore: AWS S3 and S3-compatible servers
      filesystem/          FileSystemBlobStore: a local folder (local use)
    errorHandling/         ApiException: the app's error type ({code, message, params} with an HTTP status)
    common/                Json, Strings, Secrets (masking)
  src/test/java/           unit tests (JUnit 5), in the same packages as the code they test

frontend/src/
  api/                     client.ts (one function per endpoint), types.ts (mirrors the backend JSON), live.ts (WebSocket)
  state/                   React contexts: catalog, live sessions, playlist draft, feedback (popups, toasts, useAction)
  pages/                   one folder per page: player/, upload/ (and its parts), catalog/, history/
  components/
    common/                small reusable pieces: Modal, Icon, Badges, Stat, TimeField, Sortable (drag-to-reorder)
    layout/                app frame: Header, BottomNav, FeedbackView (toasts, popups), NicknameDialog
    graphs/                BarGraph (live progress, seek), RangeGraph (choose a time range), graph math
    records/               RecordCard, EditRecordDialog, RecordPicker
    playback/              NowPlaying, LiveView, PlayDialog, TargetFields
    playlist/              PlaylistDrawer
  util/                    plain functions: time (date-fns), targets, record filters, browser storage
  i18n/                    i18next setup plus en.ts and he.ts (Hebrew must have every English key, or it does not compile)

docs/                             icd-json-format.md (ICD format), time-fix-plan.md (design of time fixing)
config/application.example.yml    template for your configuration (S3 address and secrets)
tools/gen_samples.py              generates test captures with known expected output
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
  `icd/versions/<version>/` with the holder class listed in `icd/loading/BuiltInIcds`.
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
  land within ~2 µs; with 20–200 ms gaps the worst case is ≈ 1 ms.
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

### Storage layout (one bucket)

```
pcaps/{fileId}.pcap|pcapng   capture, stored once per SHA-256
pcaps/{fileId}.json          name, size, format, uploader, scan summary (with timeline)
records/{recordId}.json      name, description, category, tags, ports, IP filter, range, stats
categories/{id}.json         name and connection mode
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
| `POST /api/uploads?fileName=&uploadedBy=` | raw file body (streamed to disk) → upload id + summary |
| `DELETE /api/uploads/{id}` | discard an unsaved upload |
| `GET /api/files/{id}/summary` | a stored file's summary |
| `POST /api/preview` | what a draft record would contain (stats, warnings, errors) |
| `POST /api/records` | create; `409 DUPLICATE` unless `resolution: "NEW_NAME"` |
| `PUT` / `DELETE /api/records/{id}`, `GET /api/records/{id}/download` | edit, delete, download the capture |
| `POST` / `PUT` / `DELETE /api/categories[/{id}]`, `GET /api/categories/{id}/usage` | categories |
| `POST` / `PUT` / `DELETE /api/tags[/{id}]` | tags |
| `POST` / `PUT` / `DELETE /api/playlists[/{id}]` | saved playlists |
| `GET /api/history` | recent runs |
| `GET` / `POST /api/sessions` | running replays / start one (a single replay or a playlist) |
| `POST /api/sessions/{id}/stop` | stop |
| `POST /api/sessions/{id}/seek` | jump the current item to `positionUs` |
| `PUT /api/sessions/{id}/upcoming` | replace a running playlist's upcoming items |
| `GET /health/live`, `GET /health/ready` | probes |

WebSocket `/ws` (server → client): `sessions` (on every change and 5 times a second while playing),
`notice` (failure popups), `catalog` and `history` (hints to refetch).

---

## Testing

```bash
cd backend && mvn test                   # backend unit tests
cd frontend && npx tsc --noEmit -p .     # frontend type check (also part of npm run build)
```

Tests that read real capture files need libpcap; they are skipped when it is missing.

`tools/gen_samples.py OUT_DIR` (Python 3, standard library only) writes test captures plus
`expected.json`, which describes exactly what must be replayed. The captures cover:
- **UDP**: out-of-order fragments, a duplicate captured on a VLAN interface, an incomplete fragment
  set, snaplen truncation, Ethernet padding, ARP/IPv6 noise.
- **TCP**: out-of-order, retransmitted and overlapping segments, an ACK-confirmed gap, a 30 KB GRO
  super-packet with IP length 0, a closed connection followed by a new one.
- **TCP client mode**: sequence wrap-around, and a range starting mid-connection.
- **Formats**: big- and little-endian, pcap and pcapng, µs and ns resolution.

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
