# Plan: auto-tagging pcaps by their ICD content

Status: **implemented** (stages 1-6). Decisions from the review are in section 0. The user documentation is
[auto-tagging.md](auto-tagging.md).

## 0. Decisions after review, and changes from the plan

| # | Decision |
|---|---|
| Q1 | The time-fix branch was merged into `main`; `feature/auto-tagging` is built on `main`. |
| Q2 | Ports are found automatically (SYN destination; otherwise the lower port of a pair) and the record keeps the ports where ICD messages were decoded. **Addition:** the record starts at the first message that counted for a condition of a matched rule, not at the start of the capture. |
| Q3 | A duplicate in another category creates a new record in this category (the file is stored once). |
| Q4 | Several records of this category on the same file: all are updated. |
| Q5 | `MATCHED` is the status of a matching file in an evaluate-only run. |
| Q6 | Matched rule names are also added to the record as tags (created if missing). |
| Q7 | `auto-tag.max-concurrent-jobs` defaults to 1. |

Changes from the plan while implementing:
- `PcapInputOpener` lives in `pcapProcessing/capture` (not `autotag/input`): the regular upload path uses it too,
  and it is part of reading captures.
- `RuleMatchAnalyzer` is in `autotag/rules` next to the compiled rules it reads (no separate `analysis` package).
- The rule and job services are `recordManagement/service/TagRuleService` (catalog data, like `TimeFixService`) and
  `autotag/job/TagJobService` (jobs, queue, temp files).
- The record-filter index is `recordManagement/repository/TaggingIndex`, maintained by `CatalogRepository`.
- `PcapFile.sha256` already holds the hash of every stored file, so no lazy hash back-fill was needed.

This feature builds on the ICD / time-fix feature ([time-fix-plan.md](time-fix-plan.md)).

---

## 1. What already exists and is reused

| Need | Existing code | How it is used |
|---|---|---|
| ICD of a category | `Category.icdId`, `IcdService.require(id)`, `IcdRegistry` | The run's ICD. A category without an ICD cannot be used for a run. |
| Decoding | `ScriptDecoder.decode(script, rangeStartNanos, icd, analyzers...)` → `DecodedMessage` / `FieldValue` | One pass per file. No new decoder. |
| Analyzer interface | `MessageAnalyzer` (`onMessage`, `onEnd(DecodeStats)`) | `RuleMatchAnalyzer` is a new implementation. |
| Counting | `MessageCountAnalyzer` (per opcode, name) | Full statistics of every file. |
| Parse problems | `DecodeStats` / `EDecodeProblem` | Parse error and unknown-opcode counts in the stats. |
| pcap → script | `MessageExtractor` / `CachingExtractor` with an `ExtractionFilter` | Same path playback and `RecordDecodeService` use, so tagging sees exactly what playback sends. |
| Reading pcaps | `Pcap4jCaptureReader` (libpcap, **needs a file path**), `CaptureScanner` → `CaptureSummary` | A gzipped input is decompressed to a temp file first (libpcap cannot read a stream). |
| Record creation | `RecordService.create(CreateRequest)` with an `uploadId`; `UploadService.register(temp, name, by)` hashes (SHA-256) and scans | Matching files are registered as uploads and saved through the same `create` path. |
| File dedup | `PcapFile.sha256` (every stored file already has it), `CatalogRepository.fileBySha` | Duplicate detection. Every stored file already has a hash, so no lazy back-fill is needed (see 6.3). |
| Persistence | `CatalogRepository` over `BlobStore` (S3 or filesystem), one JSON object per item, all loaded in memory at start | New kinds: `tagrules/{categoryId}.json`, `tagjobs/{jobId}.json`. |
| System S3 config | `AppConfig.S3` + `ConfigLoader` (defaults → yml → env vars), `S3BlobStore` (AWS SDK v2, already a dependency) | The archive S3 reuses the same SDK and the same config pattern. |
| ICD tree UI | `IcdTree*` (backend), `MessageTimeFixCard` + `util/timefix.ts` (`flatten`) (frontend) | The tree is extracted into a shared `IcdTreeView` component (section 8.1). |

**No new libraries.** gzip is `java.util.zip.GZIPInputStream`, SHA-256 is `java.security.MessageDigest`, S3 is the
AWS SDK already in `pom.xml`, the worker pool is `java.util.concurrent`.

---

## 2. New backend packages and classes

```
autotag/                       (new top-level role, like icd and playback)
  input/        PcapInputOpener, OpenedPcap, EInputKind (PCAP, PCAPNG, NOT_A_PCAP)
  rules/        TagRule, RuleCondition, FieldPredicate, EOperator, EConditionKind,
                PredicateEvaluator (one value vs one predicate), PathMatcher (array "any element"),
                ConditionEvaluator, RuleSet (compiled rules for one run), RuleValidator, RuleSummary
  analysis/     RuleMatchAnalyzer (implements icd.analysis.MessageAnalyzer), RuleOutcome
  archive/      ArchiveStore (read-only interface: list, open, size), S3ArchiveStore,
                ArchiveObject (key, size, lastModified), TimeSpan (UTC instants, inclusive), ArchiveListing
  job/          TagJob, ETagJobStatus, TagJobSource (+ ESourceKind), FileResult, EFileStatus, JobProgress,
                TagJobQueue (bounded concurrent jobs, FIFO), TagJobRunner (one job: worker pool, cancel, cleanup),
                FileTagger (one file: open → decode → evaluate → save), TaggingStats (the stats stored on a record)
recordManagement/service/
                TagRuleService (CRUD + validation), TagJobService (create/list/get/cancel, local upload area)
web/routes/     AutoTagRoutes
```

Changes to existing code:

| Where | Change |
|---|---|
| `uploadHandling/UploadService.register` | Goes through `PcapInputOpener`: a gzipped upload is decompressed to a temp file, then scanned and hashed. The original `.gz` suffix is dropped from the name. So normal uploads accept gzipped pcaps too. |
| `recordManagement/model/Record` | `+ TaggingStats tagging` (nullable; old JSON loads as `null`). |
| `recordManagement/service/RecordService` | A package-level `createFromTagging(...)` that calls the same validation and `storeUpload` path as `create`, and `updateTagging(recordId, stats)`. |
| `recordManagement/repository/CatalogRepository` | Load/put/delete `tagrules/*.json` and `tagjobs/*.json`. An in-memory index for record filters (section 7). Deleting a category deletes its rules. |
| `configuration/AppConfig`, `ConfigLoader`, `resources/application.yml`, `config/application.example.yml` | `archive-s3` and `auto-tag` sections (section 4). |
| `Main` | Builds `ArchiveStore` (or none when not configured), `TagJobQueue`; on start marks unfinished jobs `FAILED` ("interrupted"). |
| `web/ApiServer`, `CatalogRoutes` | Mount `AutoTagRoutes`; record list filter params. |

---

## 3. Gzip and pcap detection (stage 1)

`PcapInputOpener.open(InputStream raw, String name, Path tempDir)` → `OpenedPcap(Path pcap, String cleanName,
boolean gzipped, EInputKind kind, long size, String sha256)`:

1. Wrap in a `BufferedInputStream`, `mark`, read 2 bytes. `1F 8B` → wrap in `GZIPInputStream`. Detection is by
   bytes only, never by extension.
2. Read the first 4 bytes of the (decompressed) stream and check the magic number:
   `A1B2C3D4`, `D4C3B2A1` (µs), `A1B23C4D`, `4D3CB2A1` (ns), `0A0D0D0A` (pcapng). Anything else → `NOT_A_PCAP`
   (nothing written to disk).
3. Stream the content to a temp file while computing SHA-256 of the **decompressed** bytes (`DigestOutputStream`).
4. A corrupt gzip (`ZipException`/`EOFException` mid-stream) is an error → the file is `FAILED`, temp file deleted.
5. `cleanName`: drop a trailing `.gz` (case-insensitive) when the content was gzipped.

The scan by `CaptureScanner` stays the final word: a file with a good magic number that libpcap still rejects is
reported as `FAILED` (corrupt), not `NOT_A_PCAP`.

---

## 4. Configuration (stages 3 and 4)

```yaml
archive-s3:              # the organization's archive of recordings; READ-ONLY; separate from s3:
  endpoint: ""           # ARCHIVE_S3_ENDPOINT
  region: us-east-1      # ARCHIVE_S3_REGION
  bucket: ""             # ARCHIVE_S3_BUCKET   (empty = the archive sources are hidden in the UI)
  access-key: ""         # ARCHIVE_S3_ACCESS_KEY
  secret-key: ""         # ARCHIVE_S3_SECRET_KEY   (masked in logs, like s3.secret-key)
  path-style: true       # ARCHIVE_S3_PATH_STYLE
  root-prefix: ""        # ARCHIVE_S3_ROOT_PREFIX  (the browser cannot go above it)
auto-tag:
  max-files-per-run: 500 # AUTO_TAG_MAX_FILES_PER_RUN
  workers: 4             # AUTO_TAG_WORKERS        (files processed in parallel within one job)
  max-concurrent-jobs: 1 # AUTO_TAG_MAX_CONCURRENT_JOBS (others wait in the queue)
  float-tolerance: 1e-6  # AUTO_TAG_FLOAT_TOLERANCE
```

- `ArchiveStore` is its **own interface** with only `list(prefix, recursive)`, `open(key)` and `head(key)`. It
  does **not** implement `BlobStore`, so there is no `put`/`delete` method to call by mistake. Read-only is
  enforced by the type, not by discipline.
- `S3ArchiveStore` builds its `S3Client` the same way `S3BlobStore` does (I will move the client-building lines
  into a small shared `S3Clients.build(...)` to avoid copying them).
- Every key the UI sends is checked to be under `root-prefix` (no `..`, no escaping the root).
- Credentials stay on the server; the API returns only keys, sizes and dates.

**Time span.** The UI sends the span as two epoch-microsecond instants (the project's API convention). The UI
converts the user's local input (`Asia/Jerusalem`, as everywhere else in the UI) to UTC. The backend compares
`LastModified` (UTC) with `start ≤ t ≤ end`. The pure function `TimeSpan.contains(Instant)` and the list filter are
unit-tested at the boundaries and across a DST change.

---

## 5. Rules (stage 2)

```json
// tagrules/{categoryId}.json
{ "categoryId": "…", "icdId": "sample-v1", "updatedAtMs": 0,
  "rules": [ { "id": "uuid", "name": "Busy tracks",
               "conditions": [
                 { "kind": "COUNT",  "opcode": 16, "minCount": 10 },
                 { "kind": "FIELDS", "opcode": 16, "minCount": 10,
                   "predicates": [ { "path": "targets[*].kind", "op": "EQ",  "values": [ { "name": "AIR", "number": 2 } ] },
                                   { "path": "count",           "op": "GT",  "values": [ { "number": 5 } ] } ] } ] } ] }
```

- `EOperator`: `EQ, NE, GT, GE, LT, LE, BETWEEN, IN, NOT_IN, CONTAINS, STARTS_WITH`.
- Allowed operators per field type (`RuleValidator`), as in the request:
  numeric → `EQ NE GT GE LT LE BETWEEN IN NOT_IN`; enum → `EQ NE IN NOT_IN` (value stored by name **and** number;
  matching uses the number, so renaming an enum entry in a new ICD version is caught as a warning);
  bool → `EQ`; string → `EQ NE CONTAINS STARTS_WITH IN NOT_IN`.
- **Float equality** (`EQ`, `NE`, `IN`, `NOT_IN` on `FLOAT32/64`): `|a − b| ≤ tolerance`, absolute, default
  `1e-6`, configurable. `BETWEEN` and ordering operators are exact and inclusive.
- **Integers** are compared as integers (a `UINT64` above `Long.MAX_VALUE` as unsigned / `BigInteger`), so there
  is no rounding through `double`.
- **Arrays.** A path with `[*]` is true when **any element** satisfies the predicate. Each predicate is checked on
  its own: `targets[*].kind = AIR AND targets[*].speed > 300` is true if *some* target is AIR and *some* (maybe
  another) target is faster than 300. `PathMatcher` turns `targets[*].kind` into a match against the decoded paths
  `targets[0].kind`, `targets[1].kind`, … (the decoder already produces indexed paths).
- **Evaluation** (`RuleMatchAnalyzer`):
  - Compiled once per run into `RuleSet`: conditions grouped by opcode, so each message only checks the conditions
    for its own opcode.
  - Per file: one `long` counter per condition. A `COUNT` condition counts the message; a `FIELDS` condition counts
    it when every predicate is true.
  - `onEnd`: a condition is true when its counter ≥ `minCount`; a rule matches when all its conditions are true;
    the file is saved when ≥ 1 rule matches. The analyzer never stops the decode early (full statistics are needed).
- **Validation on save** (422 with a list of `{path, message}` errors): rule name not blank and unique per
  category, ≥ 1 condition, opcode in the ICD, path exists in that message, path is a leaf value, operator allowed,
  value count right for the operator (1, 2 for `BETWEEN` with `low ≤ high`, ≥ 1 for lists), value parses and fits
  the field's type range, enum name exists, `minCount ≥ 1`.
- **ICD drift.** When the stored `icdId` differs from the category's current ICD, or a stored opcode/path no longer
  exists, `GET` returns warnings (`warn.TAGRULE_ICD_MISMATCH`, `warn.TAGRULE_PATH_MISSING`) and a run with such
  a rule is refused until it is edited.

**API**

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/categories/{id}/tag-rules` | `{rules, icdTree, warnings}` |
| POST | `/api/categories/{id}/tag-rules` | create a rule |
| PUT | `/api/categories/{id}/tag-rules/{ruleId}` | edit a rule |
| DELETE | `/api/categories/{id}/tag-rules/{ruleId}` | delete a rule |

---

## 6. Job engine (stage 4)

### 6.1 Lifecycle
```
PENDING ─(local source)→ UPLOADING ─→ RUNNING ─→ DONE
   │                        │            ├────→ CANCELLED   (user)
   └─────────── queue ──────┘            └────→ FAILED      (setup error, or "interrupted" after a restart)
```
- `POST /api/tag-jobs` creates the job with a **snapshot** of the chosen rules, the category, the ICD id, the
  source description, `evaluateOnly`, and (for S3 sources) the resolved object list. Above `max-files-per-run`
  it is refused with `TAGJOB_TOO_MANY_FILES {count, max}`; nothing is truncated.
- **Local sources**: the job starts as `UPLOADING`. The browser uploads each file with
  `POST /api/tag-jobs/{id}/files?name=…&path=…` (raw body, streamed to disk like `/api/uploads`) into
  `{workDir}/tagjobs/{jobId}/`, then calls `POST /api/tag-jobs/{id}/start`. Files are kept on local temp disk (not the
  system S3): they are read by libpcap from disk anyway, and this avoids a second round trip to S3.
- **S3 sources**: the job goes straight into the queue. Each worker streams its object from the archive.
- `TagJobQueue` runs at most `max-concurrent-jobs` jobs; others stay `PENDING` in FIFO order.
- The job is written to `tagjobs/{id}.json` at every status change and at most every 2 s while running (progress
  and results so far). On start-up, any job in `PENDING`/`UPLOADING`/`RUNNING` becomes `FAILED` with reason
  `interrupted`, and its temp folder is deleted.

### 6.2 One file (`FileTagger`)
1. Open via `PcapInputOpener` → temp file (decompressed) + SHA-256 + gzipped flag; `NOT_A_PCAP` stops here.
2. `CaptureScanner.scan` → `CaptureSummary`. Failure → `FAILED` (corrupt).
3. Build the extraction filter (see Q2 for ports): category mode, the summary's whole time range.
4. `ScriptDecoder.decode(script, start, icd, countAnalyzer, ruleAnalyzer)` — one pass.
5. Not matched → `NOT_MATCHED` with counts. Matched + `evaluateOnly` → reported as matched with no record id
   and nothing written (see Q5).
6. Matched → if a stored file with the same SHA-256 exists and a record of **this category** uses it, update that
   record's `tagging` stats (`MATCHED_EXISTING_UPDATED`); otherwise create the record through `RecordService`
   (`MATCHED_SAVED`).
7. Always delete the worker's temp file in `finally`.

Memory: a worker holds one decompressed temp file on disk and one extracted script in memory. The extraction for
tagging does **not** go into the shared Caffeine script cache (it would evict playback scripts with files that
are never replayed).

### 6.3 Duplicates
`PcapFile.sha256` is already the SHA-256 of the stored (decompressed) content for every file, old and new, so the
"lazy hash for older records" from the request is already satisfied. Lookup is `fileBySha(sha)` → records with
that `fileId` and the run's category.

### 6.4 Cancel and errors
- `POST /api/tag-jobs/{id}/cancel` sets a flag. Workers check it between files and between decode steps (the
  analyzer throws a private `CancelledException` from `onMessage`), so an in-flight file is abandoned quickly.
  Results so far are kept; unstarted files are listed as not processed. Temp folder deleted. Status `CANCELLED`.
- An exception in one file → that file is `FAILED` with the message; the run goes on. Parse problems inside a file
  are only counts.

### 6.5 Record name and stats
- Record name: the clean file name without extension; the existing `uniqueName` adds ` (1)` etc. on conflict.
- `TaggingStats { icdId, totalMessages, counts: [{opcode, name, count}], parseErrors, unknownOpcodes,
  matchedRules: [name], jobId, taggedAtMs }`.

**API**

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/archive/list?prefix=&recursive=&fromUs=&toUs=` | Archive browser and time-span preview: folders, objects, count, total size, `overLimit` |
| POST | `/api/tag-jobs` | Create a job (source, categoryId, ruleIds, evaluateOnly) |
| POST | `/api/tag-jobs/{id}/files` | Upload one local file (raw body) |
| POST | `/api/tag-jobs/{id}/start` | Local uploads finished |
| POST | `/api/tag-jobs/{id}/cancel` | Cancel |
| GET | `/api/tag-jobs` | Past jobs (summary only) |
| GET | `/api/tag-jobs/{id}?status=` | Job with progress and per-file results (optionally filtered) |

---

## 7. Record stats and list filters (stage 5)

The catalog is a set of JSON objects loaded into memory; there is no database. "Queryable, not only a JSON blob"
therefore means an **in-memory index** kept by `CatalogRepository` next to the records:
- `rule name → set of record ids`
- `opcode → sorted list of (count, record id)` per category

Both are rebuilt at load and updated in `putRecord`/`deleteRecord`. `GET /api/records?rule=…&opcode=…&minCount=…`
then answers from the index instead of scanning every record. (With a few thousand records a scan would also be
fast; the index keeps it fast as the catalog grows.)

---

## 8. UI (stage 6)

### 8.1 Shared ICD tree
`components/icd/IcdTreeView.tsx` is extracted from `MessageTimeFixCard`: message header, collapsible field rows,
type and size columns, with a `renderControls(node)` prop for each row. The time-fix card is rewritten on top of
it (same look, same behaviour), and the rule editor uses it with its own controls.

### 8.2 Pages
- New route `/autotag` (and a nav entry), new route `/autotag/jobs/:jobId`.
- **Auto tagging page**: category picker (only categories with an ICD), source tabs (local files, local folder +
  span, archive files, archive folder + span), preview of count and size with the limit, rules checklist with
  inline create/edit (rule editor dialog), **Evaluate only** checkbox, **Start**.
- **Local folder**: `<input type="file" webkitdirectory multiple>`. The browser gives every file with
  `webkitRelativePath` and `lastModified`. "Include subfolders" off = keep only files whose relative path has no
  further `/` after the chosen folder. Filter by `lastModified` in the span, skip size-0 files, show count and total
  size, block above the limit. Then create the job and upload the files one or two at a time with progress
  (`fetch` with the `File` as body; the browser streams it from disk).
- **Rule editor**: the ICD tree; on a message "Add count condition"; on a leaf field "Add predicate" → operator
  dropdown filtered by type, value input by type (enum dropdown by name, number, list, two numbers for between),
  min count per condition. A readable summary line ("≥10 × Track where targets[*].kind = AIR AND count > 5").
- **Job page**: status, phase, progress bar, cancel; summary counters; status filter; results table (file, status,
  matched rules, total messages, record link), expandable to the per-opcode counts. Polls every 2 s while active.
  Past jobs list on the auto-tagging page.
- **Records list (catalog / player)**: matched rules as chips; stats in the record details; filters "matched rule"
  and "message type M count ≥ N" (M from the category's ICD).
- All strings in `en.ts` and `he.ts`; all calls through `useAction().run`.

---

## 9. Tests (per stage)

- Stage 1: gzipped and plain pcap; `.gz` that is not gzipped; gzipped non-pcap; corrupt gzip; pcapng; empty
  file; upload path accepting `.pcap.gz`.
- Stage 2: every operator × every type, enum by name, float tolerance, `between` edges, `in`/`not in`, strings;
  array any-element and independence of predicates; `COUNT`/`FIELDS` conditions; AND within a rule; several rules;
  validator errors.
- Stage 3: `TimeSpan` boundaries and Asia/Jerusalem ↔ UTC (DST); listing filter recursive / non-recursive with a
  fake `ArchiveStore`; root-prefix escape refused.
- Stage 4: integration test over synthetic pcaps from `testSupport/Frames` encoding `SampleIcd` messages
  (`SampleIcdMessages`): some gzipped, one not a pcap, one corrupt gzip, one duplicate of an existing record;
  fake archive store; filesystem `BlobStore` in a temp dir. Assert per-file statuses, counts, records and stats,
  and the temp folder is empty. Cancel mid-job with a slow fake store. Restart → `FAILED interrupted`.
- Stage 5: index updates and filter queries.

---

## 10. Open questions

**Q1 — Base branch.** The ICD/time-fix code is only on `claude/hopeful-planck-7e5zkb`, which is 13 commits behind
`main` and conflicts with it in `README.md`, `ItemPlayer`, `ReplayEngine`, `ReplaySession` and (auto-merged)
`TaxonomyService`, `CatalogPage.tsx`, `App.tsx`, `styles.css`. I started `feature/auto-tagging` from the
time-fix branch. Options: (a) keep it that way and merge `main` later, (b) I first merge `main` into this branch
and resolve the conflicts (playback files), then build on it. **Which do you want?**

**Q2 — Which ports become the record?** A record is a slice of a pcap: category mode + **ports** (+ optional IP)
+ time range. A whole archive pcap can contain many services. My proposal:
- decode every flow of the category's protocol on the service side (all destination ports for `*_SERVER`, all
  source ports for `TCP_CLIENT`),
- the saved record's ports = the ports of the flows where **at least one ICD message was decoded**, and its time
  range = the whole capture.
Alternative: rules/runs carry an explicit port list. **OK with the automatic choice?**

**Q3 — Duplicate in another category.** If the same file already exists as a record of a *different* category,
do I create a new record in this category (my default; the file itself is stored once anyway) or update nothing
and report it?

**Q4 — Several existing records on the same file** (same category, e.g. different port slices made by hand). My
default: update all of them and report `MATCHED_EXISTING_UPDATED` with the first id.

**Q5 — Evaluate-only status names.** `MATCHED_SAVED` and `MATCHED_EXISTING_UPDATED` both say something was
written, which is false in evaluate-only mode. I plan a sixth status `MATCHED` ("would be saved") used only there.
**OK to add `MATCHED`?**

**Q6 — Tags vs. rule names.** The project already has **tags** (`Tag`, bulk tagging on `YAKIR`). The request
stores matched *rule names* on the record, separate from tags. Should a matched rule also add a tag with the same
name, so the existing tag filters work? Default: no, rule names are their own field.

**Q7 — Default `max-concurrent-jobs`**: the request says one or two; I default to **1** (a job already uses 4
workers and lots of disk I/O).

---

## 11. Stages (each ends with `mvn -q package` / `npm run build`, a summary, and a stop for review)

1. `PcapInputOpener` + use in `UploadService` + tests.
2. Rule model, operators, `RuleMatchAnalyzer`, persistence, validation, API + tests.
3. Archive S3 client (read-only), listing, time-span filter, config + tests.
4. Job engine (queue, pool, per-file processing, save + stats, duplicates, cancel, cleanup, restart) +
   integration tests.
5. Record stats index and list filters (backend).
6. UI: shared ICD tree, rule editor, auto-tagging page (four sources), job page, records chips/filters, i18n.
   README / docs (`docs/auto-tagging.md`): archive S3 config, rules, limits and concurrency, gzip.
