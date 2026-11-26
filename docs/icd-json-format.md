# ICD JSON format

An ICD (interface control document) describes the binary messages a service exchanges. PCAP Player
uses it to cut a record's byte stream into messages, decode their fields, and fix time fields during
playback.

An ICD can be written in two equivalent ways:

- **JSON**, uploaded through the UI or the API. This document describes the JSON format.
- **Annotated Java classes** in `backend/src/main/java/com/pcapplayer/icd/versions/<version>/`. See
  [Java annotations](#java-annotations) at the end.

A third way, for message classes written with the legacy `iaf.ofek.oryekarot.general.io` annotations
(`@Ordinal`, `@Bits`, `@Unsigned` ...), is JSON in the annotation style: `"format": "annotations"`, the
classes and their fields with one property per annotation. It is described in
[skills/icd-from-annotations/reference/annotations.md](../skills/icd-from-annotations/reference/annotations.md),
and loads into the same model.

Both are loaded into the same internal model and checked by the same rules. A test checks that the
example at the end of this page loads to exactly the same model as the Java sample ICD.

The JSON Schema is
[`backend/src/main/resources/icd/icd.schema.json`](../backend/src/main/resources/icd/icd.schema.json).
Point your editor at it for completion and inline errors. In VS Code, add this to `settings.json`:

```json
"json.schemas": [{ "fileMatch": ["*.icd.json"], "url": "./backend/src/main/resources/icd/icd.schema.json" }]
```

The server does not need the schema. It checks every rule itself when an ICD is uploaded, and reports
each problem with its JSON path, for example:

```
$.messages[1].fields[4]: lengthField 'count' must name an integer field before this one
$.structs.Target.fields[2].type: must be one of INT8, UINT8, ... (got "UINT12")
```

---

## Top level

| Property | Required | Meaning |
|---|---|---|
| `id` | yes | Stable identifier, 1-100 letters, digits, `.`, `_`, `-`. Categories and time-fix profiles refer to it, so keep it when you fix a typo in the ICD, and use a new id for a new version (`radar-v2`). |
| `name` | yes | Display name. |
| `description` | no | Free text. |
| `byteOrder` | no | `BIG_ENDIAN` (default) or `LITTLE_ENDIAN`. The default for every field. |
| `header` | yes | The header every message starts with. See [Header](#header). |
| `enums` | no | Enums by name, used by fields with `"enum": "<name>"`. |
| `structs` | no | Nested structs by name, used by fields with `"struct": "<name>"`. A struct may use other structs, but not itself. |
| `messages` | yes | The message types. See [Messages](#messages). |
| `$schema` | no | Ignored. Lets editors find the schema. |

Unknown properties are errors, so a typo such as `"lenght"` is caught.

## Header

```json
"header": {
  "lengthIncludesHeader": true,
  "lengthUnit": "BYTES",
  "maxMessageBytes": 4096,
  "fields": [
    { "name": "opcode", "type": "UINT16", "role": "opcode" },
    { "name": "length", "type": "UINT16", "role": "length" }
  ]
}
```

| Property | Default | Meaning |
|---|---|---|
| `fields` | required | The header fields, in the same format as message fields. The header must have a fixed size, so it cannot contain an array whose count comes from a field. |
| `lengthIncludesHeader` | `true` | `true`: the length field counts the whole message. `false`: only the body after the header. |
| `lengthUnit` | `BYTES` | `BYTES`, `WORDS16` (×2) or `WORDS32` (×4). |
| `maxMessageBytes` | `65535` | A message longer than this is treated as corrupt data, and the decoder resynchronizes. |

Exactly one header field has `"role": "opcode"`. Its value selects the message type. Exactly one has
`"role": "length"`. Both must be top-level integer fields. Header fields are part of every message and
are addressed as `header.<name>`, for example `header.sendTime`.

## Messages

```json
{ "opcode": "0x0002", "name": "Track", "byteOrder": "LITTLE_ENDIAN", "fields": [ ... ] }
```

| Property | Required | Meaning |
|---|---|---|
| `opcode` | yes | The opcode value. Write it as a number (`2`) or a string (`"2"`, `"0x0002"`). Unique per ICD. |
| `name` | yes | Unique per ICD. |
| `byteOrder` | no | Overrides the ICD's byte order for the message body. The header always uses the ICD's byte order and its own field overrides. |
| `fields` | yes | The fields after the header, in order. |

If a message's length is greater than its fields need, the extra bytes at the end are kept and not
decoded. If the length is smaller, the message cannot be decoded and its bytes are sent unchanged.

## Fields

Every field has a `name` and **exactly one** of `type`, `enum`, `struct`, `string`, `bitfield` or `padding`:

```json
{ "name": "speed",    "type": "UINT16" }
{ "name": "status",   "enum": "StatusCode" }
{ "name": "pos",      "struct": "Position" }
{ "name": "unit",     "string": { "length": 8, "encoding": "US-ASCII" } }
{ "name": "flags",    "bitfield": { "type": "UINT8", "bitOrder": "MSB_FIRST", "bits": [ { "name": "ready", "bits": 1 } ] } }
{ "name": "reserved", "padding": 2 }
```

Properties every field can have:

| Property | Meaning |
|---|---|
| `name` | Letters, digits and `_`, not starting with a digit. Unique within its struct. |
| `order` | Position within the struct. Fields are laid out by `order`. Leave it out to use the position in the list (1, 2, 3 …). |
| `byteOrder` | Overrides the byte order for this field. A struct field's override applies to the fields inside it, unless they have their own. |
| `array` | Makes the field repeat. See [Arrays](#arrays). |
| `description` | Free text. |
| `role` | Header fields only: `opcode` or `length`. |

### Primitive types (`type`)

| Type | Bytes | Values |
|---|---|---|
| `INT8` / `UINT8` | 1 | −128…127 / 0…255 |
| `INT16` / `UINT16` | 2 | |
| `UINT24` | 3 | 0…16 777 215 (mostly for 24-bit bitfield containers) |
| `INT32` / `UINT32` | 4 | |
| `INT64` / `UINT64` | 8 | |
| `FLOAT32` / `FLOAT64` | 4 / 8 | IEEE 754 |
| `BOOL` | 1 | 0 = false, anything else = true |

### Enums (`enum`)

Defined once under `enums`, then used by name:

```json
"enums": {
  "StatusCode": { "type": "UINT8", "values": { "0": "OFF", "1": "ON", "0xFF": "FAULT" } }
}
```

`type` is the integer type the value is stored as. The keys of `values` are integers, in decimal or `0x`
hex, and may be negative for a signed type. A value that has no name still decodes, as the plain number.

### Nested structs (`struct`)

Defined once under `structs`, then used by name. A struct's fields follow the same rules as message
fields. Paths go through structs with a dot: `pos.lat`.

### Strings (`string`)

| Property | Default | Meaning |
|---|---|---|
| `length` | required | Size in bytes. |
| `encoding` | `US-ASCII` | Any Java charset name, for example `UTF-8` or `ISO-8859-1`. |

The text ends at the first zero byte. The rest of the bytes are padding.

### Bitfields (`bitfield`)

Several small unsigned fields packed in one integer:

| Property | Default | Meaning |
|---|---|---|
| `type` | required | The containing integer type. Its byte order is the field's byte order. |
| `bitOrder` | `MSB_FIRST` | `MSB_FIRST`: the first bit field takes the most significant bits. `LSB_FIRST`: the least significant. |
| `bits` | required | `[{ "name", "bits" }, ...]` in order. Together they take at most the container's size. Bits left over are ignored. |

Each bit field is addressed as `<field>.<bit name>`, for example `flags.ready`.

### Padding (`padding`)

Reserved bytes that are skipped: `{ "name": "reserved", "padding": 2 }`. Padding cannot be an array.
Give the total number of bytes instead.

## Arrays

Add `array` to any field (except padding) to repeat it. Give **one** of:

| Property | Meaning |
|---|---|
| `length` | A fixed number of elements. |
| `lengthField` | The integer field that holds the number of elements. It must come **before** the array. Write a plain name for a field in the same struct (`"count"`), `header.<name>` for a header field, or a dotted path from the start of the message body (`"info.count"`). |

Elements are addressed by index: `targets[3].pos.lat`. A time-fix profile selects a field inside an
array once, as `targets[*].seenGps`, and that field is then fixed in every element.

## Field paths

Paths name a field inside a message. They are used by time-fix profiles and the decode debug endpoint.

| Path | Field |
|---|---|
| `header.sendTime` | a header field |
| `timestampMs` | a top-level field of the message |
| `pos.lat` | a field of a nested struct |
| `flags.ready` | a bit of a bitfield |
| `targets[2].seenGps` | a field of the 3rd array element |
| `targets[*].seenGps` | the same field in every element (time-fix profiles) |

## Full example

This ICD uses every feature. It is the same ICD as the Java sample in
`backend/src/test/java/com/pcapplayer/icd/versions/sample/`.

```json
{
  "$schema": "https://pcap-player/icd.schema.json",
  "id": "sample-v1",
  "name": "Sample ICD v1",
  "byteOrder": "BIG_ENDIAN",
  "header": {
    "lengthIncludesHeader": true,
    "lengthUnit": "BYTES",
    "maxMessageBytes": 4096,
    "fields": [
      { "name": "opcode",   "type": "UINT16", "role": "opcode" },
      { "name": "length",   "type": "UINT16", "role": "length" },
      { "name": "sequence", "type": "UINT32" },
      { "name": "sendTime", "type": "UINT32", "description": "LTime: seconds since UTC midnight" }
    ]
  },
  "enums": {
    "StatusCode": { "type": "UINT8", "values": { "0": "OFF", "1": "STANDBY", "2": "OPERATIONAL", "255": "FAULT" } },
    "TargetKind": { "type": "INT8",  "values": { "-1": "UNKNOWN", "1": "AIR", "2": "SURFACE" } }
  },
  "structs": {
    "Position": {
      "fields": [
        { "name": "lat", "type": "FLOAT32" },
        { "name": "lon", "type": "FLOAT32" },
        { "name": "alt", "type": "INT16" }
      ]
    },
    "Target": {
      "fields": [
        { "name": "id",      "type": "UINT16" },
        { "name": "pos",     "struct": "Position" },
        { "name": "seenGps", "type": "UINT32", "description": "GPS: 25 µs ticks since UTC midnight" },
        { "name": "kind",    "enum": "TargetKind" }
      ]
    }
  },
  "messages": [
    {
      "opcode": "0x0001",
      "name": "Status",
      "fields": [
        { "name": "status",   "enum": "StatusCode" },
        { "name": "flags",    "bitfield": { "type": "UINT8", "bitOrder": "MSB_FIRST",
                                            "bits": [ { "name": "ready", "bits": 1 },
                                                      { "name": "mode",  "bits": 3 },
                                                      { "name": "spare", "bits": 4 } ] } },
        { "name": "reserved", "padding": 2 },
        { "name": "unitName", "string": { "length": 8, "encoding": "US-ASCII" } },
        { "name": "timestampMs", "type": "INT64", "description": "Regular time in milliseconds" },
        { "name": "counter",  "type": "UINT32", "byteOrder": "LITTLE_ENDIAN" },
        { "name": "healthy",  "type": "BOOL" }
      ]
    },
    {
      "opcode": "0x0002",
      "name": "Track",
      "byteOrder": "LITTLE_ENDIAN",
      "fields": [
        { "name": "gpsTime",      "type": "UINT64", "description": "GPS: 25 µs ticks since UTC midnight" },
        { "name": "epochSeconds", "type": "UINT32", "byteOrder": "BIG_ENDIAN", "description": "Regular time in seconds" },
        { "name": "ltimeExact",   "type": "FLOAT64", "description": "LTime with a fraction" },
        { "name": "targetCount",  "type": "UINT8" },
        { "name": "targets",      "struct": "Target", "array": { "lengthField": "targetCount" } },
        { "name": "samples",      "type": "INT16", "array": { "length": 3 } },
        { "name": "temperature",  "type": "INT8" }
      ]
    }
  ]
}
```

Layout of the `Track` message (opcode 2), after the 12-byte header:

| Offset | Size | Field | Notes |
|---|---|---|---|
| 0 | 8 | `gpsTime` | UINT64, little endian (from the message) |
| 8 | 4 | `epochSeconds` | UINT32, big endian (field override) |
| 12 | 8 | `ltimeExact` | FLOAT64 |
| 20 | 1 | `targetCount` | the count of `targets` |
| 21 | 17 × count | `targets[i]` | `id` 2, `pos` 10 (`lat` 4, `lon` 4, `alt` 2), `seenGps` 4, `kind` 1 |
| … | 6 | `samples[0..2]` | INT16 × 3 |
| … | 1 | `temperature` | INT8 |

## Java annotations

The same ICD as Java classes. Put each version in its own package under
`backend/src/main/java/com/pcapplayer/icd/versions/<version>/`, and add the holder class to
`com.pcapplayer.icd.loading.BuiltInIcds.HOLDERS`. Every class is a top-level class in its own file.

| Annotation | Goes on | JSON equivalent |
|---|---|---|
| `@Icd(id, name, byteOrder, header, messages)` | the holder class | top level |
| `@IcdHeader(lengthIncludesHeader, lengthUnit, maxMessageBytes)` | the header class | `header` |
| `@Opcode`, `@Length` | header fields | `"role"` |
| `@IcdMessage(opcode, name, byteOrder)` | each message class | `messages[]` |
| `@IcdField(order, type, byteOrder)` | every field | a field; `order` is required, and the Java declaration order is ignored |
| `@IcdArray(length \| lengthField)` | a Java array field | `array` |
| `@IcdString(length, encoding)` | a `String` field | `string` |
| `@IcdPadding(bytes)` | any field (`byte[]` reads well) | `padding` |
| `@IcdEnum(type, name)` / `@IcdEnumValue(value)` | a Java enum / its constants | `enums` |
| `@IcdBitfield(type, bitOrder)` / `@IcdBits(n)` | a class / its fields | `bitfield` |
| (none) | a field whose Java type is another class with `@IcdField` fields | `struct` (named after the class) |

`type` can be left out when the Java type says it: `byte`→INT8, `short`→INT16, `int`→INT32,
`long`→INT64, `float`→FLOAT32, `double`→FLOAT64, `boolean`→BOOL. Unsigned types always need `type`.
An enum's ICD name is the Java name without the `E` prefix (`EStatusCode` → `StatusCode`), unless
`@IcdEnum(name = ...)` says otherwise.

```java
@IcdMessage(opcode = 0x0002, name = "Track", byteOrder = EByteOrder.LITTLE_ENDIAN)
public class TrackMessage {

    @IcdField(order = 1, type = EFieldType.UINT64)
    long gpsTime;

    @IcdField(order = 4, type = EFieldType.UINT8)
    int targetCount;

    @IcdField(order = 5)
    @IcdArray(lengthField = "targetCount")
    Target[] targets;
    ...
}
```
