# Plan: ICD message parsing and time-field fixing during playback

Status: **draft, waiting for approval.** Nothing has been implemented yet.

This plan describes how the feature in the request fits into the code as it is today. It also lists the decisions
I made where the request leaves room, and the questions I need answered (section 9).

---

## 1. How the existing code works (the parts this feature touches)

| Concern | Where it lives today | What matters for this feature |
|---|---|---|
| Reading pcaps | `pcapProcessing/capture` (`CaptureReaders`, `Pcap4jCaptureReader` → `RawFrame`) | Reused as is. |
| IP fragmentation | `pcapProcessing/decode/Ipv4Reassembler`, `PacketStream` → `L4Packet` | Already done before any of our code runs. Not touched. |
| TCP stream reassembly | `pcapProcessing/flow/TcpStreamReassembler` (retransmissions, reordering, gaps), `extract/TcpMessageCollector` (connections) | Produces **chunks**: the bytes that became readable at one moment. Each chunk is one `ScriptStep.Send`. |
| UDP | `extract/UdpMessageCollector` + `flow/UdpDeduplicator` | Each (IP-reassembled, de-duplicated) datagram is one `ScriptStep.Send`. |
| The thing that is replayed | `pcapProcessing/script/ReplayScript` = list of `ScriptStep` (`Send`, `Reconnect`, `Gap`) with offsets from range start | **A "packet" in the request = one `Send` step.** These are the units whose sizes, boundaries and timing must stay unchanged. |
| Script cache | `extract/CachingExtractor` (Caffeine, keyed by sha256 + filter) | The `byte[]` of a `Send` is **shared** between concurrent replays and later replays. Patching must never write into it. Each patched step is copied first (section 6.4). |
| Preparing a replay | `recordManagement/service/RecordScriptSource.prepare` (called from `ReplaySession.playScript` and prefetched for the next playlist item) | This is where the category is known. The ICD and the profile are attached to the prepared script here. |
| Send loop | `playback/ItemPlayer.play` / `run` → `Transport.send(bytes)` | The only place where "now" is known. The value computation and the byte write happen right before `transport.send`. |
| Categories | `recordManagement/model/Category(id, name, mode, createdAtMs)`, `TaxonomyService`, `CatalogRoutes`, `CatalogPage.tsx` | Gets an optional `icdId`. |
| Persistence | `CatalogRepository` over `BlobStore` (S3 or filesystem), JSON objects per item | New object kinds: `icds/{id}.json` and `timefix/{categoryId}.json`. |

**Consequence of the script model.** The decoder does not work on raw pcap packets. It works on the same
`Send` steps that playback sends, which are already IP-reassembled, TCP-reassembled and de-duplicated.
So the byte-location map points at `(step index, offset in step)`. That is what playback sends, and it keeps the
existing fragmentation handling in a single place. The offline decoder (debug endpoint, later tagging) uses the
same extraction path, so offline results match exactly what playback does.

**Flows inside one script.** One TCP replay connection can carry several recorded connections when they
overlapped in time (`TcpMessageCollector.groupOverlapping`). One UDP replay can carry datagrams from several
source ports. So `ScriptStep.Send` needs to say which recorded flow it came from:

- `ScriptStep.Send(offsetNanos, data)` becomes `Send(offsetNanos, data, flowId)`.
- `ReplayScript` gets `List<FlowInfo> flows` (`flowId`, protocol, src ip:port, dst ip:port). Each TCP *connection*
  is its own flow, so a reconnect on the same 5-tuple starts a fresh reassembly. Each UDP 5-tuple is a flow.
- The existing code only reads `offsetNanos` and `data`, so the change is local. `Histogram` and tests are updated.

---

## 2. New packages and classes

All new backend code is under `com.pcapplayer.icd` (a new top-level role, like `playback`). The decoder does not
depend on playback, and playback depends on the decoder.

```
icd/
  model/            IcdDefinition, HeaderDef, MessageDef, FieldDef, EFieldType, EByteOrder, EBitOrder,
                    ELengthUnit, EnumDef, IcdTree (UI view), IcdModelValidator (shared checks for both loaders)
  annotations/      @Icd, @IcdHeader, @Opcode, @Length, @IcdMessage, @IcdField, @IcdBits, @IcdBitfield,
                    @IcdArray, @IcdString, @IcdPadding, @IcdEnum, @IcdEnumValue
  loading/          AnnotationIcdLoader, JsonIcdLoader, IcdLoadException (list of {jsonPath, message}),
                    BuiltInIcds (explicit list of annotated ICD holder classes), IcdRegistry (built-in + JSON)
  versions/sample/  the sample ICD used by tests (Java), + test resource sample-icd.json
  decode/           MessageDecoder (bytes → field tree), FlowFramer (per-flow application reassembly),
                    ScriptDecoder (whole script → Iterator<DecodedMessage>), DecodedMessage, FieldValue,
                    ByteSpan, DecodeStats (counts per problem kind), RateLimitedLog
  analysis/         MessageAnalyzer (onMessage/onEnd), MessageCountAnalyzer, DecodeReport (debug endpoint)
  timefix/          ETimeKind, TimeCodec (kind + data type ↔ Instant), TimeAnchoring, FieldWriter
                    (encode + write into step bytes, endianness, bitfields), PatchPlan, MessagePatch, FieldPatch,
                    TimeFixPlanner (profile + decoded messages → PatchPlan), PostPatchHook (checksum extension point)
  profile/          TimeFixProfile, MessageTimeFix, TimeFieldRef, TimeFixProfileValidator
```

Changes to existing code:

| Where | Change |
|---|---|
| `pcapProcessing/script/ScriptStep`, `ReplayScript`, `extract/*Collector` | `flowId` on `Send`, flow table on the script. |
| `recordManagement/model/Category` | `+ String icdId` (nullable). Old JSON without it loads as `null`, because Jackson already ignores unknown or missing properties. |
| `recordManagement/repository/CatalogRepository` | Load/put/delete `icds/*.json` and `timefix/*.json`. Deleting a category also deletes its profile. |
| `recordManagement/service/` | New `IcdService` (upload, list, delete JSON ICDs, category mapping, tree) and `TimeFixService` (profile get/save + validation, decode debug). Wired in `CatalogService`. |
| `RecordScriptSource.prepare` | If the category has an ICD and a profile with at least one message, build the `PatchPlan` and return it in `Prepared`. Otherwise `null`. |
| `playback/model/ScriptSource.Prepared` | `+ PatchPlan patchPlan` (nullable). |
| `playback/ItemPlayer` | Before `transport.send`, if there is a plan: `bytes = patcher.bytesFor(stepIndex, now)`. With no plan, the code path is the same as today. |
| `web/routes/IcdRoutes` (new), `CatalogRoutes` | New endpoints (section 7). |
| `Main` | Builds `IcdRegistry` and passes a `java.time.Clock` (`Clock.systemUTC()`) down to playback. |
| Frontend | `api/client.ts`, `api/types.ts`, catalog page (ICD column + ICD list card), new `pages/timefix/TimeFixPage.tsx` and `components/timefix/*`, `en.ts`/`he.ts`. |

---

## 3. ICD model (stage 1)

```java
record IcdDefinition(String id, String name, EByteOrder byteOrder, HeaderDef header, List<MessageDef> messages)
record HeaderDef(List<FieldDef> fields, String opcodePath, String lengthPath,
                 boolean lengthIncludesHeader, ELengthUnit lengthUnit /* BYTES */, int maxMessageBytes)
record MessageDef(long opcode, String name, EByteOrder byteOrder /* null = inherit */, List<FieldDef> fields)
record FieldDef(String name, EFieldKind kind, int order, EByteOrder byteOrder /* null = inherit */,
                EFieldType primitive,           // PRIMITIVE, ENUM (underlying), BITFIELD container
                List<FieldDef> children,        // STRUCT members, BITFIELD bits
                Integer bits,                   // a bit inside a BITFIELD
                EBitOrder bitOrder,             // BITFIELD: MSB_FIRST | LSB_FIRST
                ArrayDef array,                 // fixed length or lengthPath
                StringDef string,               // length, encoding (default US-ASCII), null padded
                EnumDef enumDef,                // value → name
                Integer paddingBytes)
```

- `EFieldKind`: `PRIMITIVE, STRUCT, ARRAY, STRING, ENUM, BITFIELD, PADDING`. An array's element is a `FieldDef`, so
  arrays of structs, primitives and enums all work.
- **Bitfields.** A `BITFIELD` field has a container type (for example `UINT16`) and an ordered list of bit fields,
  each N bits, packed in `bitOrder`. The bits must fit in the container. Path: `flags.ready`. Bit fields are
  numeric, so they can be time fields (rare, but the writer supports it).
- **Paths.** `header.time`, `body.targets[3].pos.lat`. Header fields live under `header.`. The message's own
  fields are top-level: `targets[3].pos.lat` (the request's example), with no `body.` prefix.
- **Sizes.** Every field has a fixed size except arrays whose count comes from a field, and structs that contain one.
  The length field gives the total. If the ICD covers fewer bytes than the length, the remaining bytes are kept as
  "unparsed trailing bytes" (counted, not an error). If the ICD needs more bytes than the length, that is a parse
  error.
- **Validation** (shared by both loaders, with a path such as `messages[2].fields[4].array.lengthField`):
  - unique opcodes, unique names, unique `order` per struct
  - `lengthField` refers to an *earlier* integer field
  - bits fit their container
  - the opcode and length fields exist in the header and are integers
  - enum values fit their type
  - no recursive structs

### Annotations (2a)

```java
@Icd(id = "sample-v1", name = "Sample ICD v1", byteOrder = BIG_ENDIAN,
     header = SampleHeader.class, messages = {StatusMessage.class, TrackMessage.class})
public final class SampleIcd {}

@IcdHeader(lengthIncludesHeader = true)
public class SampleHeader {
    @IcdField(order = 1, type = UINT16) @Opcode int opcode;
    @IcdField(order = 2, type = UINT16) @Length int length;
    @IcdField(order = 3, type = UINT32) long ltime;
}

@IcdMessage(opcode = 0x10, name = "Track")
public class TrackMessage {
    @IcdField(order = 1, type = UINT8) int count;
    @IcdField(order = 2) @IcdArray(lengthField = "count") Target[] targets;  // nested struct from the Java type
    @IcdField(order = 3) @IcdPadding(bytes = 2) Void reserved;
    @IcdField(order = 4, type = UINT16, byteOrder = LITTLE_ENDIAN) int localCounter;
    ...
}
```

- `@IcdField(order, type, byteOrder)` is on every field. `type` can be left out when the Java type says what the
  field is: an annotated struct class, an enum with `@IcdEnum`, or a `@IcdBitfield` class.
- `@IcdEnum(type = UINT8)` goes on a Java enum, with `@IcdEnumValue(3)` on each constant.
- `@IcdBitfield(type = UINT16, bitOrder = MSB_FIRST)` goes on a small class whose fields carry `@IcdBits(n)` and
  `@IcdField(order)`.
- The Java field types are only documentation. Decoding never instantiates these classes; only the model is used.
- **Registration**: `BuiltInIcds.CLASSES = List.of(SampleIcd.class)`. You add a line per version. This needs no
  classpath scanning and no new library. Each version goes in its own package `com.pcapplayer.icd.versions.<name>`.
  The sample ICD is registered only in tests (`versions/sample` is under `src/test`), so it does not show up in
  production. **See question Q6.**

### JSON format (2b)

The structure mirrors the model one-to-one: `id`, `name`, `byteOrder`, `header {lengthIncludesHeader, fields[]}`,
`messages[] {opcode, name, byteOrder?, fields[]}`, and `structs {name: {fields[]}}` for reusable nested types.
A field looks like `{"name", "order", "type", "byteOrder"?, "array"?: {"length"|"lengthField"}, "string"?:
{"length","encoding"}, "enum"?: {"type","values":{"3":"READY"}}, "bits"?: [...], "padding"?: n, "role"?:
"opcode"|"length"}`. Opcodes can be written as a number or a `"0x10"` string.

- `backend/src/main/resources/icd/icd.schema.json` (JSON Schema draft 2020-12) is for editors and IDE validation.
- `docs/icd-json-format.md` explains every element and includes the full sample ICD in JSON.
- `JsonIcdLoader` parses with Jackson's tree model. It checks structure and types by hand, then runs the shared
  model validation. Every error is reported with its JSON path, and all errors are returned at once. **See Q1**
  about schema validation.
- Storage: uploaded JSON ICDs are stored in the blob store at `icds/{id}.json`, next to records and categories,
  so they work on S3 and on the filesystem. There is also an optional config folder (`icd.dir`) that is read at
  startup for ICDs shipped with the deployment.
  - API: list, get the tree, upload (validated; an invalid file is rejected with the error list), delete. Deleting
    an ICD that a category uses is refused.
  - Annotation ICDs are listed as read-only (`source: "JAVA"`).

### Category → ICD (2c)

- `Category.icdId` holds the mapping. One category uses exactly one ICD; an ICD can be used by several
  categories (**see Q2**).
- The mapping is set with `PUT /api/categories/{id}/icd` and shown in the catalog UI.
- With no ICD set, playback works exactly as today.

---

## 4. Decoder (stage 2)

**Input.** `ScriptDecoder.decode(ReplayScript script, long rangeStartNanos, IcdDefinition icd)` returns
`Iterator<DecodedMessage>`. It does not know about playback. The offline entry point is
`OfflineDecoder.decode(Path pcap, ExtractionFilter filter, icd)`: it runs the existing `MessageExtractor` and then
`ScriptDecoder`. That is how the debug endpoint works, and later how tagging will work.

**Application-level reassembly** (`FlowFramer`, one per flow) works through the steps in order:

1. Append the step's bytes to the flow's buffer. Remember where each step's bytes start, so that any message byte
   can be mapped back to `(stepIndex, offsetInStep)`.
2. While the buffer holds a whole header: read the opcode and the length.
   - If the opcode is known and the length is plausible (at least the header, at most `maxMessageBytes`), wait until
     `length` bytes are buffered, across as many steps as needed. Then decode the message and emit it.
   - If the opcode is unknown or the length is invalid, **resync**. Drop bytes up to the start of the next step in
     this flow (messages usually start at a packet boundary) and try again there. The dropped bytes are sent
     unchanged; they are only not parsed. Each case is counted in `DecodeStats` and logged with a rate limit.
3. A TCP `Gap` step in the flow (missing bytes) or a new connection clears the flow's buffer. A partial message
   at that point is counted as truncated and passes through.
4. At the end of the script, any partial message is counted as `TRUNCATED` and passes through.

The same framer is used for UDP: a message may span datagrams of the same 5-tuple. **See Q3.**

**`DecodedMessage`**: `opcode`, `name`, `flowId` (plus a `FlowInfo` view), `captureTsNanos` (of the step holding
its first byte), `firstStep`, `lastStep`, `fields: LinkedHashMap<String path, FieldValue>`, and
`locations: Map<path, List<ByteSpan>>` with `ByteSpan(stepIndex, offsetInStep, length)`. One span is enough unless
the field crosses a step boundary.

**`FieldValue`**: `type`, plus `long`/`double`/`String`/`boolean` as fits, and the enum name when there is one.
`UINT64` is kept as a raw long and printed unsigned.

**Robustness.** Every decode runs inside try/catch per message. A failure becomes a counted `PARSE_ERROR` and its
bytes pass through. Nothing in the decoder can stop playback, and plan building is wrapped again in
`RecordScriptSource` (on failure: log once and play without fixing).

**Analyzers (section 7 of the request).**
- `interface MessageAnalyzer { void onMessage(DecodedMessage m); void onEnd(DecodeStats stats); }`
- `ScriptDecoder.run(script, icd, analyzers...)` feeds every analyzer.
- `MessageCountAnalyzer` counts per opcode.
- The debug endpoint `GET /api/records/{id}/decode?limit=N&opcode=X` uses a `CollectingAnalyzer`, the count
  analyzer and the stats.

---

## 5. Time conversion (stage 3)

`ETimeKind`: `REGULAR_SECONDS`, `REGULAR_MILLIS`, `LTIME`, `GPS`. All the math is in **nanoseconds** (`long`),
which covers ±292 years at full precision. The `TimeCodec` methods are static and pure, and are unit tested.

| Kind | decode (raw value → instant) | encode (instant → raw value) |
|---|---|---|
| `REGULAR_SECONDS` | `v × 1e9` ns since the epoch | `epochNanos / 1e9` |
| `REGULAR_MILLIS` | `v × 1e6` | `epochNanos / 1e6` |
| `LTIME` | anchor(`v × 1e9` ns of day) | `nanosOfUtcDay / 1e9` |
| `GPS` | anchor(`v × 25 000` ns of day) | `nanosOfUtcDay / 25 000` |

- **Data types.** For integer types, the encoded value is rounded down with `Math.floorDiv`. For `FLOAT32`/`FLOAT64`,
  the same formula is computed in `double`, so the fraction is kept (for example LTime `3723.25`). Signed and
  unsigned values of every width are read and written according to the field's real type.
- **Anchoring LTime/GPS.** A time-of-day value has no date. The candidate instants are that time of day on the
  capture timestamp's UTC date, the day before and the day after. The one closest to the message's capture
  timestamp is used. So a value of `23:59:59.9` on a message captured at `00:00:00.1` resolves to the previous day.
- **Overflow.** If the encoded value does not fit the type, it is clamped to the type's min or max. The clamp is
  counted and logged with a rate limit. Example: `REGULAR_MILLIS` into `INT32`.
- **Midnight when replaying.** The encode step uses the day of `now + offset`, so wrap-around happens on its own.

**Algorithm at send time** (`MessagePatch.apply(nowNanos)`):
```
baseInstant  = decode(base raw value, base kind, base type, captureTs)      // precomputed before playback
for each fix: offset[i] = decode(field raw, kind, type, captureTs) − baseInstant  // precomputed
at send:      write(base,  encode(now, base kind, base type))
              write(field, encode(now + offset[i], field kind, field type))
              postPatchHook.afterPatch(message bytes view)   // no-op today; checksum extension point
```
All the decoding and offsets are computed **before playback starts**. At send time, only the encode and the byte
write remain, which is a few arithmetic operations per field.

**`FieldWriter`** writes the encoded value into the target steps' byte arrays using the field's `ByteSpan`s:
- It honours byte order.
- For bitfields it does read-modify-write of only the field's bits inside the container.
- A field split across two steps is written byte by byte over both spans.
- Bytes outside the field are never touched. Tests compare every other byte.

---

## 6. Playback integration (stage 5)

### 6.1 Where "now" comes from
`ItemPlayer` gets a `java.time.Clock` (the JDK abstraction, no new library). `now` is read right before
`transport.send` of the message's **first** step: `clock.instant()` converted to epoch nanos. On Linux with
Java 17 that has microsecond resolution. Tests inject a fixed or stepping clock and a fake transport (the existing
`FakeTransport`).

### 6.2 The patch plan is built ahead of time (the "lookahead")
`RecordScriptSource.prepare` already runs before the first send. For playlists it is also prefetched while the
previous item plays. So the cheapest and most predictable "parse ahead" is to decode the **whole** record there
and build a `PatchPlan`:

- `PatchPlan`: `MessagePatch[] byFirstStep` (sparse map from step index to the patches whose message starts there)
  plus the set of step indexes that are written to.
- Memory: one small object per *fixed* message. Messages of types without a profile entry are decoded once and
  then dropped.
- The plan is cached next to the script (key: script cache key + ICD id + profile version), so replaying the same
  record does not decode again.

This replaces a bounded lookahead queue on a second thread with something simpler: no extra thread, no
synchronization, and seeking works for free. **See Q4** if you specifically want a streaming lookahead (for
example for records too large to hold in memory, which the current design already does not support).

### 6.3 Send-time flow in `ItemPlayer`
```
Send step i:
  if plan == null or step i is not patched: transport.send(step.data)                 // same as today
  else:
     if a message starts at i: now = clock; patch every message starting here into copies of steps i..lastStep
     transport.send(copyOf(i))                                                         // copy made when patched
```
- Copies of later steps of a message that spans steps are kept in a small per-play map until sent.
- **Seek**: if a jump skips the first step of a message, the message's remaining steps are sent unpatched (counted).
  **See Q5.**

### 6.4 The cached script is never modified
The script's `byte[]` belongs to the extraction cache and can be shared by several replays at once. A patched step
is always a copy (`data.clone()`, then write). Only steps that carry a time field are copied.

### 6.5 Zero overhead without an ICD or profile
If there is no ICD, no profile, or the profile is empty, `patchPlan == null` and `ItemPlayer` runs the existing
code path. Nothing is decoded. A profile whose `icdId` no longer matches the category's ICD is also skipped: a
warning is written to the replay log (`warn.TIMEFIX_ICD_MISMATCH`) and the replay runs without fixing.

### 6.6 End-to-end test
The test builds a synthetic pcap with `testSupport/Frames`. It contains a message split over 3 TCP segments, two
messages in one segment, an unknown opcode, and a time field crossing a segment boundary. The test runs the real
`MessageExtractor` → `RecordScriptSource`-style preparation → `ItemPlayer.play`, with a stepping clock and
`FakeTransport`, and asserts the exact bytes sent.

---

## 7. Profile, persistence and API (stage 4)

```json
// timefix/{categoryId}.json
{ "categoryId": "…", "icdId": "sample-v1", "updatedAtMs": 0,
  "messages": [ { "opcode": 16,
                  "base":   { "path": "header.ltime",      "kind": "LTIME" },
                  "fields": [ { "path": "targets[*].gpsTime", "kind": "GPS" },
                              { "path": "sentAtMs",          "kind": "REGULAR_MILLIS" } ] } ] }
```

**Validation on save.**
- Errors (422 with the list):
  - a message has fields but no base
  - an opcode is not in the ICD
  - a path is not in the ICD
  - a field is not numeric
  - the same field is selected twice
  - the base is also listed as a field to fix
- Warnings (saved, and returned to the UI):
  - `REGULAR_MILLIS` on an integer type narrower than 64 bits
  - `REGULAR_SECONDS` on an integer type narrower than 32 bits
  - `GPS` on an integer type narrower than 32 bits (a day is 3,456,000,000 ticks, which needs a `UINT32`
    or something wider)
  - `LTIME` on `INT8`/`UINT8`
  - a time kind on a `BOOL` field
  - the stored `icdId` differs from the category's current ICD

**Endpoints** (all under `/api`; routes only parse requests, the services hold the rules):

| Method | Path | Purpose |
|---|---|---|
| GET | `/icds` | List ICDs: `{id, name, source: JAVA/JSON, messageCount, usedBy: [categoryId]}` |
| GET | `/icds/{id}/tree` | ICD structure as a tree for the UI |
| POST | `/icds` (raw JSON body) | Upload or replace a JSON ICD. 422 with `[{path, message}]` if invalid |
| DELETE | `/icds/{id}` | Delete a JSON ICD. 409 if a category uses it |
| PUT | `/categories/{id}/icd` | `{icdId: string or null}`. Sets the mapping |
| GET | `/categories/{id}/timefix` | `{profile, icdTree, warnings}` (the tree is included so the UI needs one call) |
| PUT | `/categories/{id}/timefix` | Save the profile; returns warnings |
| GET | `/records/{id}/decode?limit=N&opcode=X` | Debug: decoded messages, count per opcode, parse error stats |

New error and warning codes (`ICD_INVALID`, `ICD_NOT_FOUND`, `ICD_IN_USE`, `ICD_READ_ONLY`, `TIMEFIX_INVALID`,
`NO_ICD`, `warn.TIMEFIX_*`) are added to both `en.ts` and `he.ts`, as the project rules require.

---

## 8. UI (stage 6)

- **Catalog page, categories card.** Each category row gets an ICD dropdown (`none` + list) and a "Time fix"
  button. The button is enabled only when an ICD is set and opens `/catalog/categories/:id/timefix`.
- **Catalog page, new "ICDs" card.** Lists the ICDs with their source and usage, has an upload button for
  `.json` files (validation errors shown with their paths), and delete for JSON ICDs.
- **Time fix page.**
  - The ICD tree: messages, then fields. Structs and arrays expand. Each field row shows its name, type and size.
  - Each numeric field row has a **base** radio button (one per message), a **fix** checkbox, and a kind dropdown:
    Regular (s), Regular (ms), LTime, GPS.
  - Non-numeric rows show no controls.
  - Each message header shows a summary such as "base: header.ltime (LTime) + 2 fields" and its errors or warnings.
  - The page has Save and Discard buttons and warns about unsaved changes when you leave it.
- Same components and styles as the existing cards, with English and Hebrew strings. All calls go through
  `useAction().run`.

---

## 9. Open questions

**Q1 — JSON Schema validation library.** The rules say not to add a library without asking. Validating ICD JSON
*against the schema file* at runtime needs a library, for example `com.networknt:json-schema-validator`, if it is
on your internal mirror. My plan without it:
- The schema file is shipped for editors and IDEs.
- The loader checks everything by hand, with JSON paths in its errors.

The errors will be as good as or better than a validator's, but the schema and the loader logic have to be kept in
sync by hand. **OK to go without the library?**

**Q2 — Sharing an ICD between categories.** "Each category maps to exactly one ICD" is clear. Can two categories
use the **same** ICD version? I assume yes, because several services can speak the same ICD, and each category
still has its own profile. If you mean strictly one-to-one in both directions, I'll block mapping an ICD that
another category already uses.

**Q3 — UDP framing.** Can a UDP message be split across datagrams, or can a datagram hold several messages?
- If datagrams always hold whole messages, it is safer to treat every datagram on its own: frame from its start,
  and never carry bytes over to the next datagram. A lost datagram then cannot corrupt the next message.
- My default is the general stream framing from the request, plus a per-ICD option `udpDatagramIsBoundary`
  (default `true`). **Which do you want as the default?**

**Q4 — Lookahead.** I plan to decode the whole record in `prepare` (section 6.2) instead of a streaming bounded
buffer during playback. This meets "the patch plan is ready when the first packet is due" with less moving parts.
It is the same memory model the replay already uses, since the whole script is in memory. **OK?**

**Q5 — Seek.** If a seek skips the first packet of a message, the rest of that message is sent unpatched. The
alternative is to patch it using the time of its first *sent* packet. Which do you prefer?

**Q6 — Arrays in the profile.** For a time field inside an array (`targets[3].time`), the tree has no element
count (variable-length arrays). I plan to let the user select `targets[*].time`, meaning **every element**, with
the offset computed per element. Selecting one specific index would also be possible. **Is `[*]` what you want?**

**Q7 — Sample ICD in production.** Should the sample ICD be visible in the running app, for trying the feature
before your real ICDs exist, or only in tests? My default is only in tests, plus the same ICD as a JSON file in
`docs/` that you can upload.

**Q8 — Base field kind/type in the header.** If every message has its time in the header, you would currently
select it per message. Should the profile also have a "default for all messages" entry, meaning the same base for
every opcode unless a message overrides it? This is easy to add now and saves clicks. It is not in the request,
so it is off unless you want it.

---

## 10. Stages (each ends with `mvn -q package` / `npm run build`, a summary, and a stop for your review)

1. ICD model, annotations, annotation loader, JSON format, schema, loader and doc, sample ICD, loader tests
   (identical models, JSON errors).
2. `flowId` on `Send`, decoder, framer, analyzers, debug decode endpoint, decode and reassembly tests.
3. `TimeCodec`, anchoring, `FieldWriter`, `PatchPlan` building, conversion and patching tests.
4. Category → ICD mapping, ICD upload and list, profile persistence, validation and API, service tests.
5. Playback integration (`Clock`, send-time patching, copies, seek), end-to-end test.
6. UI: ICD card, mapping, Time fix page, i18n. README and docs updates (how to add an ICD in Java or JSON, how to
   configure time fixing, time kinds and formulas).
