# PCAP Player

Replays the traffic a source sent to one of our services, recorded in a pcap/pcapng file on the
service host, to a service-under-test — the same application bytes, in the same order, with the
same timing gaps. It impersonates the source over ordinary sockets (the OS builds new headers);
replies from the service are drained and ignored.

- **Backend**: Java 17, Vert.x 4.5 (HTTP, plain WebSocket, static files), AWS SDK v2 S3 client.
  The pcap/pcapng parser, IPv4 fragment reassembly and TCP stream reassembly are hand-written.
- **Frontend**: React 18 + TypeScript + Vite; plain CSS, hand-written i18n (English LTR / Hebrew RTL).
- **Storage**: one S3 bucket (AWS or S3-compatible), no database.
- **Deployment**: one Docker image, OpenShift-friendly, single replica.

---

## Repository layout

```
backend/                     Maven project (all versions pinned in pom.xml <properties>)
  src/main/java/com/pcapplayer/
    capture/   pcap + pcapng streaming readers (byte order, µs/ns, if_tsresol, multi-interface)
    net/       link layers (Ethernet/VLAN/QinQ, SLL, SLL2, raw), IPv4, fragment reassembly, TCP/UDP
    extract/   upload scan, message extraction, TCP stream reassembly, UDP de-duplication, histogram
    replay/    engine, sessions (single replay = 1-item session, playlist = N), precise clock, transports
    storage/   BlobStore interface + S3 and filesystem implementations, local file cache
    catalog/   categories, tags, files, records, history (in memory, write-through to the store)
    api/       Vert.x router, routes per area, WebSocket hub, health probes
    config/    application.yml + environment overrides
  src/main/resources/application.yml
frontend/                    Vite project (exact versions in package.json, full tree in package-lock.json)
  src/api  src/i18n  src/state  src/components  src/pages  src/util
tools/gen_samples.py         stdlib-only generator of test captures (fragmented UDP, out-of-order TCP, …)
Dockerfile
```

---

## Dependencies (check these against your internal mirrors)

### Backend — runtime (all inside the fat jar)

Direct dependencies:

| Artifact | Version |
|---|---|
| io.vertx:vertx-core | 4.5.21 |
| io.vertx:vertx-web | 4.5.21 |
| com.fasterxml.jackson.core:jackson-databind | 2.18.4 |
| com.fasterxml.jackson.dataformat:jackson-dataformat-yaml | 2.18.4 |
| software.amazon.awssdk:s3 (netty-nio-client and apache-client **excluded**) | 2.31.30 |
| software.amazon.awssdk:url-connection-client | 2.31.30 |
| org.yaml:snakeyaml (pinned; Vert.x would otherwise force 2.0) | 2.3 |

Full resolved runtime tree (`mvn dependency:list -DincludeScope=runtime`):

```
com.fasterxml.jackson.core:jackson-annotations:2.18.4
com.fasterxml.jackson.core:jackson-core:2.18.4
com.fasterxml.jackson.core:jackson-databind:2.18.4
com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.4
io.netty:netty-buffer / codec / codec-dns / codec-http / codec-http2 / codec-socks / common /
         handler / handler-proxy / resolver / resolver-dns / transport /
         transport-native-unix-common:4.1.125.Final            (pulled in by vertx-core)
io.vertx:vertx-auth-common / vertx-bridge-common / vertx-core / vertx-web / vertx-web-common:4.5.21
org.reactivestreams:reactive-streams:1.0.4
org.slf4j:slf4j-api:2.0.7                                       (no binding; see Logging)
org.yaml:snakeyaml:2.3
software.amazon.awssdk:annotations / arns / auth / aws-core / aws-query-protocol / aws-xml-protocol /
         checksums / checksums-spi / crt-core / endpoints-spi / http-auth / http-auth-aws /
         http-auth-aws-eventstream / http-auth-spi / http-client-spi / identity-spi / json-utils /
         metrics-spi / profiles / protocol-core / regions / retries / retries-spi / s3 / sdk-core /
         third-party-jackson-core / url-connection-client / utils:2.31.30
software.amazon.eventstream:eventstream:1.0.1
```

Build plugins: maven-compiler-plugin 3.13.0, maven-resources-plugin 3.3.1, maven-jar-plugin 3.4.1,
maven-shade-plugin 3.6.0. Maven 3.9.9, JDK 17.

### Frontend

| Package | Version | Kind |
|---|---|---|
| react | 18.3.1 | runtime |
| react-dom | 18.3.1 | runtime (pulls loose-envify 1.4.0, js-tokens 4.0.0, scheduler 0.23.2) |
| react-router-dom | 6.28.0 | runtime (pulls @remix-run/router 1.21.0, react-router 6.28.0) |
| vite | 5.4.11 | build only |
| @vitejs/plugin-react | 4.3.4 | build only |
| typescript | 5.6.3 | build only |
| @types/react / @types/react-dom | 18.3.12 / 18.3.1 | build only |

Node 20.18.1. The complete transitive build tree is pinned in `frontend/package-lock.json`.

### Docker base images

`node:20.18.1-alpine3.20`, `maven:3.9.9-eclipse-temurin-17`, `eclipse-temurin:17.0.13_11-jre-jammy`.

---

## Configuration

Everything is in `backend/src/main/resources/application.yml`. **Any key can be overridden by an
environment variable**: upper-case the path and replace `.` and `-` with `_`
(`s3.access-key` → `S3_ACCESS_KEY`). A whole extra YAML file can be layered on top with
`CONFIG_FILE=/path/to/file.yml`.

| Key | Env var | Default | Meaning |
|---|---|---|---|
| server.host | SERVER_HOST | 0.0.0.0 | bind address |
| server.port | SERVER_PORT | 8080 | HTTP port (UI, API, WebSocket `/ws`) |
| storage.type | STORAGE_TYPE | s3 | `s3`, or `filesystem` for local development |
| storage.filesystem-root | STORAGE_FILESYSTEM_ROOT | /tmp/pcap-player/store | root for `filesystem` |
| s3.endpoint | S3_ENDPOINT | *(empty = AWS)* | e.g. `http://minio:9000` |
| s3.region | S3_REGION | us-east-1 | |
| s3.bucket | S3_BUCKET | pcap-player | must exist |
| s3.access-key | S3_ACCESS_KEY | *(empty = AWS default chain)* | |
| s3.secret-key | S3_SECRET_KEY | | |
| s3.path-style | S3_PATH_STYLE | false | `true` for MinIO / most S3-compatible servers |
| s3.prefix | S3_PREFIX | *(empty)* | optional key prefix inside the bucket |
| work-dir | WORK_DIR | /tmp/pcap-player | uploads in progress + local pcap cache |
| cache.max-bytes | CACHE_MAX_BYTES | 2147483648 | local pcap cache size (LRU) |
| upload.expire-minutes | UPLOAD_EXPIRE_MINUTES | 120 | uploaded-but-unsaved files are discarded after this |
| replay.connect-timeout-ms | REPLAY_CONNECT_TIMEOUT_MS | 5000 | TCP-server mode connect timeout |
| extraction.fragment-timeout-ms | EXTRACTION_FRAGMENT_TIMEOUT_MS | 30000 | incomplete IPv4 fragment sets are dropped after this |
| extraction.tcp-gap-timeout-ms | EXTRACTION_TCP_GAP_TIMEOUT_MS | 2000 | a TCP hole not covered by an ACK becomes a gap after this |
| extraction.udp-duplicate-window-ms | EXTRACTION_UDP_DUPLICATE_WINDOW_MS | 5 | identical UDP datagrams within this window are one |
| history.size | HISTORY_SIZE | 20 | runs kept in `history.json` |
| histogram.max-bins | HISTOGRAM_MAX_BINS | 120 | bars in the live graph (1 per second until this cap) |

JVM options go in `JAVA_OPTS` (the image defaults to `-XX:MaxRAMPercentage=75`). A running replay
holds its record's payload bytes in memory (≈ the selected traffic), so size the container memory
for the largest records you expect to play concurrently.

**Logging** uses `java.util.logging`. The AWS SDK logs through SLF4J, which has no binding here,
so SLF4J prints one "no providers" line at startup and the SDK stays quiet.

---

## Running locally

Needs JDK 17, Maven 3.9 and Node 20.

### Without S3 (quickest)

```bash
cd frontend && npm ci && npm run build && cd ..
cp -R frontend/dist backend/src/main/resources/webroot
cd backend && mvn -q package && cd ..
```

```bash
STORAGE_TYPE=filesystem java -jar backend/target/pcap-player.jar
```

Open http://localhost:8080.

### With MinIO

Note: MinIO archived its open-source server in late 2025. `dl.min.io` now answers `410 Gone` and
the public images are no longer published, so use the MinIO image your internal registry already
mirrors (any other S3-compatible server works the same way).

```bash
docker run -d --name minio -p 9000:9000 -p 9001:9001 -e MINIO_ROOT_USER=minio -e MINIO_ROOT_PASSWORD=minio123 <your-registry>/minio/minio:<tag> server /data --console-address :9001
```

Create the bucket `pcap-player` (console at http://localhost:9001, or `mc mb local/pcap-player`), then:

```bash
S3_ENDPOINT=http://localhost:9000 S3_ACCESS_KEY=minio S3_SECRET_KEY=minio123 S3_PATH_STYLE=true java -jar backend/target/pcap-player.jar
```

### Frontend development

Run the backend as above, then `cd frontend && npm run dev` and open http://localhost:5173
(Vite proxies `/api` and `/ws` to :8080).

### Docker / OpenShift

```bash
docker build -t pcap-player:1.0.0 .
```

Build args for an isolated network: `REGISTRY` (base image registry prefix, e.g. `nexus.local/`),
`NPM_REGISTRY`, `MAVEN_MIRROR_URL`.

- Runs as an arbitrary non-root UID in group 0; `/app` and `/tmp/pcap-player` are group-0 writable,
  all temp files are under `/tmp`.
- Probes: liveness `GET /health/live`; readiness `GET /health/ready` (metadata loaded from S3 and the
  bucket reachable). On startup the app retries loading metadata every 5 s until S3 answers.
- **Run exactly one replica**: running replays live in that pod's memory.
- Expose port 8080 (HTTP + WebSocket on the same port). TCP-client-mode replays also listen on the
  ports users choose, so those must be reachable from the service-under-test (Service/NodePort or
  pod networking, as your setup requires).

---

## How it works

### Capture parsing
Streaming readers for classic pcap (both byte orders, µs and ns magic) and pcapng (SHB/IDB/EPB/SPB
and the obsolete PB, several sections and interfaces, per-interface link type, `if_tsresol`,
`if_tsoffset`, both byte orders). Link types: Ethernet with any number of 802.1Q/QinQ tags, Linux
SLL and SLL2, raw IPv4 (and BSD loopback). Only IPv4 TCP/UDP is used; everything else is skipped.

Receive-side artifacts:
- **GRO/LRO**: the IP total length is trusted only when it is consistent with the captured bytes;
  otherwise (super-packets, total length 0) the captured length is used. No MTU assumptions.
- **Snaplen truncation**: TCP keeps the captured part and the missing bytes become a gap; UDP
  datagrams are dropped. Both are counted and shown as warnings.
- **Duplicate captures**: TCP reassembly dedupes naturally; UDP drops a datagram with the same
  5-tuple, IP ID and payload seen within 5 ms.
- Checksums are never validated.

### Building messages
- IPv4 fragments are reassembled (key src, dst, protocol, IP ID) **before** port filtering; a
  reassembled datagram is one message, timestamped at the fragment that completed it. Incomplete
  sets are dropped and counted.
- The time range is applied after reassembly; packets outside it are ignored, so a range starting
  mid-connection simply starts the stream at the first in-range segment.
- TCP streams are rebuilt by 64-bit-unwrapped sequence number: retransmissions dropped, overlaps
  trimmed, out-of-order data held until the hole fills. Each time bytes become readable they form
  one chunk, stamped with the time they became readable.
- **Gaps**: a hole is declared lost by the capture when the service's own ACK already covers it
  (the host got the bytes, the capture did not), after `tcp-gap-timeout-ms`, or at stream end. The
  gap is skipped, held data keeps its own arrival time, and the gap is reported (upload summary
  with time/connection/bytes, and in the replay log when playback passes it).
- Consecutive connections become *close → reconnect* steps (close at FIN/RST time, reconnect at the
  next connection's start). Overlapping connections are merged in timestamp order with a warning.

### Replay engine
- One platform thread per running session (MAX priority). Every send time is `zero + offset` from
  the range start, never relative to the previous send, so there is no drift.
- The clock parks, then spin-waits the last stretch. The spin margin **adapts** to how late the OS
  actually wakes parked threads, so it spins only as long as the host needs.
- Measured on the development machine (a macOS laptop, the noisiest case; Linux servers are
  normally better): back-to-back sends on a 1 ms grid land within ~2 µs of schedule; with gaps
  of 20–200 ms, worst case ≈ 1 ms, p99 < 1 ms.
- TCP: `TCP_NODELAY`, one write per chunk; a separate thread drains and discards replies.
- **TCP server mode**: connect; connect failure or a dropped connection fails the replay.
- **UDP server mode**: datagrams from one ephemeral local port.
- **TCP client mode**: listen; timing starts when the service connects; extra connections are
  refused while one is active. A recorded reconnect closes the socket and waits for the service to
  reconnect, shifting the rest of the schedule; an unexpected disconnect stops the replay
  ("Client disconnected").
- Conflicts (same target IP:port, or listen port in use) are rejected before anything starts.
- A replay runs until the **end of the selected range** (so the timeline and a following playlist
  sleep are faithful), then reports "completed".
- **Playlists**: items run in order with a sleep after each. A failed item gets a failed history
  entry and the playlist **continues** with the next item. Anyone can reorder, remove, add and
  retarget upcoming items, and change their sleeps, while it runs; the current item is locked.

### Storage layout (one bucket)
```
pcaps/{fileId}.pcap|pcapng   capture, stored once per SHA-256
pcaps/{fileId}.json          sha256, original name, size, format, uploader, scan summary
records/{recordId}.json      name, description, category, tags, ports, IP filter, range, stats, creator
categories/{id}.json         name + connection mode
tags.json                    all tags (global or per category)
history.json                 last 20 runs, newest first
```
All metadata is loaded into memory at startup and written through on every change.

The S3 client is set to compute request/response checksums **only when required**. AWS SDK 2.30+
otherwise sends `aws-chunked` bodies with trailing checksums, which several S3-compatible servers
store as part of the object.

### API
REST under `/api` (JSON). Times are epoch **microseconds**; the UI converts to and from Israel time
(`Asia/Jerusalem`, `DD/MM/YYYY HH:MM:SS`). Errors are `{code, message, params}` and the UI translates `code`.

| | |
|---|---|
| `GET /api/catalog` | categories, tags, records, files |
| `POST /api/uploads?fileName=&uploadedBy=` | raw file body (streamed to disk) → upload id + summary |
| `DELETE /api/uploads/{id}` | discard an unsaved upload |
| `POST /api/preview` | stats/warnings/errors for a draft (upload or stored file + filters + range) |
| `POST /api/records` | create; `409 DUPLICATE` unless `resolution: "NEW_NAME"` |
| `PUT /api/records/{id}` · `DELETE /api/records/{id}` · `GET /api/records/{id}/download` | |
| `POST/PUT/DELETE /api/categories[/{id}]` · `GET /api/categories/{id}/usage` | |
| `POST/PUT/DELETE /api/tags[/{id}]` | |
| `GET /api/history` | |
| `GET/POST /api/sessions` · `POST /api/sessions/{id}/stop` · `PUT /api/sessions/{id}/upcoming` | replays |

WebSocket `/ws` (server → client): `sessions` (snapshot on change and 5×/s while playing),
`notice` (failure popups), `catalog` and `history` (refetch hints).

### Adding things
- A new UI string: add the key to `frontend/src/i18n/en.ts`; the Hebrew file fails to compile until it has it too.
- A new server error or warning: return a new `code`, then add `err.<CODE>` / `warn.<CODE>` to both dictionaries.
- Another storage backend: implement `storage/BlobStore`.
- Another link type: one case in `net/LinkDecoder`.

---

## Verifying the parser

`tools/gen_samples.py OUT_DIR` (Python 3 stdlib only) writes captures plus `expected.json`
describing exactly what must be replayed: UDP with out-of-order fragments, a duplicate captured on
a QinQ interface, an incomplete fragment set, snaplen truncation, Ethernet padding, ARP/IPv6 noise;
TCP with out-of-order, retransmitted and overlapping segments, an ACK-confirmed capture gap, a
segment captured on two interfaces (Ethernet + SLL2), a 30 KB GRO super-packet with IP length 0, a
FIN-closed connection followed by a new one; TCP-client mode with sequence wrap-around and a range
starting mid-connection; big- and little-endian, pcap and pcapng, µs and ns resolution. All
scenarios were checked against the Java extractor during development.
