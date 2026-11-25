# AGENTS.md

Guidance for AI coding agents working in this repository. The full user and design documentation
is in [README.md](README.md); this file covers what an agent needs to build, test and change code.

## Project

PCAP Player: upload a pcap/pcapng capture, cut a time range of one service's traffic into a
**record**, then replay it to any IP:port over TCP or UDP with the original timing.

- `backend/`: Java 17, Maven, Vert.x HTTP + WebSocket server, pcap4j (libpcap) for reading captures.
- `frontend/`: React 18 + TypeScript + Vite. The production build is copied into
  `backend/src/main/resources/webroot/` (git-ignored) and served by the backend.
- `config/application.example.yml`: configuration template. `tools/gen_samples.py`: generates test
  captures with their expected replay output.

## Build, test, run

Run these from the repository root. On Windows use PowerShell equivalents (see README).

```bash
# Backend: compile and run the unit tests (JUnit 5)
cd backend && mvn -q package             # add -DskipTests to skip tests

# Frontend: type-check and build, then bundle it into the backend jar
cd frontend && npm ci && npm run build
rm -rf backend/src/main/resources/webroot && cp -R frontend/dist backend/src/main/resources/webroot
cd backend && mvn -q package -DskipTests

# Run with local storage (UI, API and WebSocket on one port)
STORAGE_TYPE=filesystem SERVER_PORT=8090 java -jar backend/target/pcap-player.jar

# Frontend hot reload against a backend on port 8080
cd frontend && npm run dev
```

- `npm run build` runs `tsc --noEmit` first, so it is also the frontend type check.
- Runtime needs the native libpcap: Npcap on Windows, `libpcap0.8` on Linux. The
  `Pcap4jCaptureReaderTest` tests use it too.
- End-to-end check: `python3 tools/gen_samples.py OUT_DIR` writes captures plus `expected.json`
  (messages, offsets, sha256). Upload them, save records, replay to a local socket and compare.
  `tcp_server.pcapng` mixes link types on purpose and is rejected by libpcap (a known limitation).
- Load check: `python3 tools/load_test.py --base URL --pid SERVER_PID` plays 10 mixed replays at once
  and reports arrival timing, HTTP latency and server CPU (see README, Resources and behavior under load).
- The build environment may be an isolated network with internal Maven/npm mirrors: every new
  dependency must exist there. Pin exact versions (backend versions live in `pom.xml` `<properties>`).

## Code layout

Backend packages under `backend/src/main/java/com/pcapplayer/`:

| Package | Role |
|---|---|
| `configuration` | `AppConfig`, `ConfigLoader` (defaults, then config file, then env vars) |
| `web`, `web/routes`, `web/live` | HTTP server, one `*Routes` class per area, WebSocket `LiveHub` |
| `pcapProcessing/{capture,decode,flow,summary,extract,script,diagnostics}` | packet pipeline, in that order |
| `icd/{model,annotations,loading,decode,analysis,timefix,profile,tree,versions}` | ICDs, message decoding, time fixing |
| `autotag/{rules,archive,job}` | auto tagging: rules and their evaluation, the read-only archive S3, tagging jobs |
| `recordManagement/{service,model,repository}` | catalog business rules, stored data, `CatalogRepository` |
| `uploadHandling` | `UploadService` |
| `playback`, `playback/model`, `playback/transport` | replay engine, its exchange types, sockets |
| `persistence`, `persistence/{s3,filesystem}` | `BlobStore` and its implementations, `FileCache` |
| `errorHandling` | `ApiException` |
| `common` | `Json`, `Strings`, `Secrets` |

Tests live in `backend/src/test/java/` in the same package as the code they test; shared helpers are
in `testSupport`. Frontend layout: `api/`, `state/`, `pages/`, `components/`, `util/`, `i18n/`.

## Rules

### Java

- **Enums** are named `E<Name>` (`EConnectionMode`, `EItemState`). JSON constant names stay unchanged.
- **Packages** are named by role; multi-word names use camelCase (`pcapProcessing`,
  `recordManagement`); single words stay lowercase (`web`, `playback`). This is deliberate.
- **No nested classes**: every class is top-level in its own file (package-private if only its
  package uses it). Nested `record`s and `interface`s are fine. Test fakes are their own files too.
- **Blank lines**:
  1. A Javadoc directly below a code line gets a blank line above it, including right after
     `class X {`.
  2. A line that opens a block (`if`/`for`/`while`/`try`/method/lambda ending in `{`) gets a blank
     line above it when the previous line ends a statement (`;`) or a block (`}`). Not after an
     opening `{`, not inside a multi-line signature, not before `else`/`catch`/`finally`.
- Keep the code compatible with **Java 17** (`maven.compiler.release=17`).

### Everywhere

- **Prefer a library** over hand-written code whenever one does the job. Name the exact artifact
  and version. Hand-written code is kept only where no library fits (packet pipeline, precise clock).
- **Names say what a value is** (`record`, `session`, `packet`, `tsNanos`). Short names only for
  loop counters `i`/`j`, a caught exception or UI event `e`, and i18next's `t`/`tc`.
- **Layers**: routes only parse requests and call a service; business rules live in services;
  storage is accessed only through `CatalogRepository`.
- **Catalog writes** go through `CatalogSupport.mutate(...)`. Do slow work (parsing, uploading)
  before it, outside the lock.
- **Errors**: throw `ApiException(status, code, params, message)`, or `ReplayFailure` in replay code.
  Every `code` needs `err.<CODE>` (warnings: `warn.<CODE>`) in both `i18n/en.ts` and `i18n/he.ts`.
- **UI strings** go in both `en.ts` and `he.ts`; the Hebrew file must have every English key or the
  build fails. Placeholders are `{name}`.
- **Frontend server calls** go through `useAction().run(...)` (busy state, error popups, toasts).
- **Times** are epoch microseconds in the API. The UI shows Israel time (`Asia/Jerusalem`,
  `DD/MM/YYYY HH:MM:SS`).

## Adding things

- **An endpoint**: a service method, a route in the matching `web/routes/*Routes` class, and a
  function in `frontend/src/api/client.ts` (types in `api/types.ts`).
- **A storage backend**: implement `persistence/BlobStore`.
- **A link type**: one case in `pcapProcessing/decode/LinkDecoder`.

## Before you finish

- Run `mvn -q package` in `backend/` and `npm run build` in `frontend/` for any change in that part.
- Update `README.md` when behavior, configuration or the code layout changes.
