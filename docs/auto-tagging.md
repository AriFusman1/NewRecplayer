# Auto tagging

Auto tagging takes a batch of pcaps, decodes each one with a category's ICD, counts every message
type, and checks your **rules**. Every file that matches at least one rule is saved as a record of
that category. It keeps its **message statistics** (the count of every message type) and the names
of the rules it matched. Files that match nothing are not saved; they are listed in the run's
results with their counts.

Example: 30 pcaps and the rule "at least 10 Track messages where `targets[*].kind = AIR`". 5 files
match. Those 5 become records, each with the count of every message type in it.

The design is in [auto-tagging-plan.md](auto-tagging-plan.md).

## Trying it with the demo

The `dummy/` folder has a demo ICD (`demo-sensor.icd.json`: Heartbeat, TargetReport, SystemStatus) and, in
`dummy/pcaps`, 11 small captures shaped so that each matches a different rule (one gzipped, one that is not
a pcap). `dummy/setup_and_try.py` uploads the ICD, creates the *Demo sensor* category with example rules and
runs a job on `dummy/pcaps`: evaluate only, or with `--save` the matching files become records tagged with
their rules. `dummy/README.md` lists every file with the rules it should match.

## Running it

Open **Auto tagging** in the bottom bar.

1. **Category**: only categories with an ICD are listed. Its ICD is used to decode the files and to
   build the rules.
2. **Files**, from one of four sources:

   | Source | How |
   |---|---|
   | Local files | Choose several files on your computer. |
   | Local folder | Choose a folder, tick *Include subfolders* if needed, and give an optional *Last modified* span. The browser filters by each file's last-modified time and uploads **only** the files inside the span. |
   | S3 files | Browse the archive S3 and tick files. |
   | S3 folder | Open a folder in the archive browser, tick *Include subfolders* if needed, and give an optional span. The server lists the folder and keeps the objects whose S3 **LastModified** is inside the span. |

   - The span is typed in Israel time and converted to UTC before comparing. Both ends are included.
     A start date alone means 00:00:00 and an end date alone means 23:59:59.
   - Empty files and folder markers are skipped. Extensions do not matter: a file whose content is not
     a pcap is reported as *Not a pcap*.
   - The count and total size are shown before you start. Above the limit
     (`auto-tag.max-files-per-run`, 500 by default) the run cannot start: narrow the span or the
     selection. Nothing is silently left out.
3. **Rules**: tick the category's rules to use in this run, or create and edit them here.
4. **Evaluate only**: tick it to run everything but **save nothing**, to try rules out.
5. **Start**. Local files are uploaded first (two at a time), then the run is queued. The run page
   opens.

The **run page** shows progress (files done, bytes) and has a **Cancel** button. Under that are
counters per status and the results table: file, status, matched rules, total messages, and a
link to the record. Open a row to see the count of every message type. Past runs are listed at the
bottom of the Auto tagging page and can be opened again later.

File statuses:

| Status | Meaning |
|---|---|
| Matched, saved (`MATCHED_SAVED`) | a new record was created |
| Matched, existing record updated (`MATCHED_EXISTING_UPDATED`) | the same content (SHA-256 of the decompressed file) is already a record of this category; that record got the new statistics and rule tags instead |
| Matched (`MATCHED`) | evaluate-only run: it would have been saved |
| Not matched (`NOT_MATCHED`) | decoded and counted, no rule matched |
| Not a pcap (`NOT_A_PCAP`) | empty, or not a pcap/pcapng once any gzip layer is removed |
| Failed (`FAILED`) | unreadable or corrupt input, or saving failed; the error is shown |

Decoding problems (unknown opcodes, messages that do not match their definition) never fail a
file. They are counted and shown with its statistics.

## What a saved record looks like

- **Ports**: the service-side ports of the connections where ICD messages were decoded. A TCP server
  port is taken from the connection's SYN. Without a SYN, and for UDP, the lower port of the pair is
  taken as the server port.
- **Time range**: it starts at the first message that counted for a condition of a matched rule (so
  the record begins where the interesting traffic begins) and ends at the end of the capture.
- **Tags**: every matched rule name is also added as a tag (created if missing), so the usual tag
  filters work.
- **Statistics** (record card → *Message statistics*): ICD id, total messages, count per message type,
  parse errors and unknown opcodes, matched rules, the run and the date. They describe the whole file.
- The capture is stored **decompressed**, under its name without `.gz`, so it plays like any record.

The records list (Player page) can filter by **matched rule**, and by **message type M ≥ N**
(choose a category with an ICD first). The server answers these filters from an index of the
records' statistics.

## Rules

A rule has a name and one or more **conditions**. A file matches the rule when **all** conditions
are true (AND). A run can use several rules; a file is saved when it matches **at least one**
(OR), and it keeps the names of every rule it matched. Rules are saved per category.

Conditions are checked on the counts after the **whole** file is decoded:

- **Count condition**: message type M appears at least N times.
- **Field condition**: at least N messages of type M satisfy **all** of a list of field predicates.
  Example: "≥ 10 × Track where `targets[*].kind = AIR` AND `targetCount > 5`".

A predicate is `field path · operator · value(s)`. The operators depend on the field's type:

| Field type | Operators | Notes |
|---|---|---|
| Integer (any width, and bits of a bitfield) | `=` `≠` `>` `≥` `<` `≤` `between` `in list` `not in list` | exact, including UINT64 above 2⁶³ |
| Float (FLOAT32/64) | the same | `=`, `≠`, `in`, `not in` treat values within `auto-tag.float-tolerance` (absolute, default `1e-6`) as equal; `<`, `>`, `between` are exact |
| Enum | `=` `≠` `in list` `not in list` | values are chosen by name; the rule stores the name and the number, and matches by number |
| Bool | `=` | |
| String | `=` `≠` `contains` `starts with` `in list` `not in list` | case-sensitive |

- `between` includes both ends. Lists are typed with commas between values.
- Values are checked when the rule is saved: the path must exist and be a single value, the
  operator must fit the type, numbers must fit the field (for example 0…255 for a UINT8, 0…7 for a
  3-bit field), enum names must exist, and every count must be at least 1.

### Arrays: "any element"

A path that goes through an array is written with `[*]`, for example `targets[*].kind`. Such a
predicate is true when **any element** satisfies it. This holds for every operator, so
`targets[*].kind ≠ AIR` means "some target is not AIR".

Each predicate is checked **on its own**. Two predicates on the same array do **not** have to match
the same element. `targets[*].kind = AIR` AND `targets[*].pos.alt > 1000` matches a message with an
AIR target and a target above 1000, which may be two different targets.

### When the ICD changes

Rules remember the ICD they were saved with. If the category now uses another ICD, the rules page
warns about it. A rule whose message type or field no longer exists is listed as an error and
cannot be used in a run until it is edited.

## Gzipped files

Any input may be gzipped, in any mix with plain files. Gzip is recognized by its first two bytes
(`1F 8B`), never by the name, and the file is decompressed as a stream. After decompression the
content must start with a pcap or pcapng magic number; otherwise the file is *Not a pcap*. A
damaged gzip stream fails the file. The regular **Upload** page accepts gzipped pcaps the same
way.

## Configuration

```yaml
archive-s3:              # the organization's archive: a different S3 from s3:, only ever read
  endpoint: "http://archive.internal:9000"   # empty = AWS
  region: us-east-1
  bucket: recordings     # empty = the archive sources are switched off
  access-key: "..."      # empty keys = the AWS default credential chain
  secret-key: "..."
  path-style: true
  root-prefix: ""        # the archive browser starts here and cannot go above it
auto-tag:
  max-files-per-run: 500 # a selection above this is refused before the run starts
  workers: 4             # files processed in parallel within one run
  max-concurrent-jobs: 1 # runs at the same time; the others wait in a queue
  float-tolerance: 0.000001
```

Every key has an environment variable: `ARCHIVE_S3_BUCKET`, `ARCHIVE_S3_SECRET_KEY`,
`AUTO_TAG_WORKERS`, and so on.

- **Read-only archive.** The application only sends List, Head and Get requests to the archive.
  Its client has no method that writes or deletes. Paths from the UI are checked so they cannot
  leave `root-prefix`. The credentials never reach the browser.
- **Disk and memory.** Each worker holds at most one decompressed file on local disk (under
  `work-dir/tagjobs/`) and one decoded script in memory. With 4 workers and 10 MB pcaps that is a
  few tens of MB. Local uploads for a run are kept in the same folder until the run ends.
- **Clean-up.** A run's temp folder is deleted when it finishes, fails or is cancelled. On start-up
  the whole `work-dir/tagjobs/` folder is cleared, and a run that was not finished when the server
  stopped is marked *Failed* with the reason "interrupted". Runs are not resumed. A local upload
  that is never finished is failed after `upload.expire-minutes`.
- **Cancel** stops after the files in flight: they are abandoned, files not started are skipped,
  and the results so far are kept.
