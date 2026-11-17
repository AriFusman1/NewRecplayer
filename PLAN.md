# PCAP Player — implementation plan

## Layout
```
backend/   Vert.x 4.5 (Java 17, Maven, shaded fat jar) — package com.pcapplayer
frontend/  React 18 + TypeScript + Vite
tools/     gen_samples.py (stdlib-only generator of test captures)
Dockerfile, README.md
```

## Backend packages
| Package | Responsibility |
|---|---|
| `capture` | `CaptureReaders.open()` → `Pcap4jCaptureReader` (libpcap via pcap4j, pcap + pcapng). Emits `RawFrame(tsNanos, linkType, bytes, origLen)`. |
| `net` | `LinkDecoder` (Ethernet + 802.1Q/QinQ, SLL, SLL2, raw IPv4) → `Ipv4Packet`; `Ipv4Reassembler` (key src,dst,proto,id; timeout ⇒ incomplete); `TcpSegment` / `UdpDatagram` decoders. GRO-safe length handling, truncation flags, no checksum checks. |
| `extract` | `CaptureScanner` (upload summary: times, protocols, ports per role, IPs, truncated/fragment counts). `MessageExtractor` (filter by mode/ports/IP/range → `ReplayScript`), `TcpStreamReassembler` (seq unwrap, dedupe, overlap trim, reorder, gaps via reverse-ACK or timeout, connection boundaries), `UdpDeduplicator`. |
| `replay` | `ReplayEngine` (active sessions, target/listen-port locks), `ReplaySession` (one thread; single replay = session with 1 item, playlist = N items), transports `TcpConnectTransport` / `UdpSendTransport` / `TcpListenTransport`, `PreciseClock` (park + spin, schedule from zero, no drift). |
| `storage` | `BlobStore` interface + `S3BlobStore` (AWS/MinIO). `CatalogRepository` (categories, tags, records, files — in memory, write-through), `HistoryRepository`, `LocalFileCache` (/tmp copies of pcaps for parsing). |
| `config` | `AppConfig`: `application.yml` (Jackson YAML) with env-var overrides (`S3_BUCKET` → `s3.bucket`). |
| `api` | Vert.x Web router (REST + static frontend + `/health/live`, `/health/ready`), `LiveHub` (plain WebSocket `/ws`), `ApiException` with i18n-able `code` + params. Blocking work runs on the worker pool. |

## Replay script model
`ReplayScript { mode, List<Step> }` with steps `Send(offsetNanos, bytes)` and `Reconnect(closeOffsetNanos, connectOffsetNanos)`. Offsets are from range start. Payloads held in memory while a replay runs.

## REST API (`/api`)
| Method | Path | Purpose |
|---|---|---|
| POST | `/uploads?fileName=` (raw body) | stream to /tmp, SHA-256, scan → `{uploadId, summary, sameFile}` |
| POST | `/uploads/{id}/preview`, `/files/{fileId}/preview` | stats + warnings + errors for category/ports/IP/range |
| GET | `/files/{fileId}/summary` | scan summary of a stored file |
| GET/POST/PUT/DELETE | `/records[/{id}]` | list, create (from upload or existing file, conflict → 409 with duplicate info), edit (name/desc/times), delete |
| GET | `/records/{id}/download` | original pcap |
| GET/POST/PUT/DELETE | `/categories[/{id}]` | delete returns affected record count via `GET /categories/{id}/usage` |
| GET/POST/PUT/DELETE | `/tags[/{id}]` | |
| GET | `/history` | last 20 |
| GET | `/sessions` | active sessions |
| POST | `/sessions` | start (1 item = single replay, N = playlist) |
| POST | `/sessions/{id}/stop` | stop (by nickname) |
| PUT | `/sessions/{id}/upcoming` | replace upcoming items (playlist editing) |

## WebSocket `/ws` (server → client JSON)
`sessions` (full snapshot; on change + ~5 Hz while playing), `notice` (error/info popups with code+params), `catalog` (records/categories/tags changed → refetch), `history`.

## S3 layout
`pcaps/{fileId}.pcap|pcapng` + `pcaps/{fileId}.json` (sha256, original name, size, format), `records/{id}.json`, `categories/{id}.json`, `tags.json`, `history.json`.

## Times
Internal epoch nanos; API epoch **microseconds** (safe as JS numbers); UI converts to/from Asia/Jerusalem `DD/MM/YYYY HH:MM:SS` with `Intl`. A typed end time means "through the end of that second".

## Assumptions (tell me if any is wrong)
1. TCP gap detection: a hole is declared a gap when the service's own ACK covers it (capture drop) or after `gap-timeout` (default 2 s) / connection end. Data after the gap keeps its own arrival timestamps.
2. Consecutive TCP connections are replayed as close → reconnect; close time = FIN/RST time (or next connect time if no FIN/RST was seen in range).
3. Range filtering happens after IP reassembly; segments outside the range are ignored entirely (mid-connection start works naturally).
4. Name uniqueness is enforced by auto-numbering on every save path.
5. Target lock is by resolved IP:port regardless of UDP/TCP.
6. A replay waits until the range end before it is "completed" (the bar graph spans the whole range).
7. Playlist: a failed item gets a failed history entry and the playlist continues with the next item (your answer).
8. Duplicate by name only + "same recording" → reuse the same-named record's file (your answer).

No actuator in Vert.x: liveness/readiness are hand-written routes (`/health/live`, `/health/ready` — ready once metadata is loaded from S3).
