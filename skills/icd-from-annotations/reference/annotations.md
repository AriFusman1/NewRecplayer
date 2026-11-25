# The annotation-style ICD JSON

This is the reference for `"format": "annotations"`: what each property means, and how PCAP Player
reads the bytes from it. It follows the legacy annotation mechanism of the
`iaf.ofek.oryekarot.general.io` library, where every message is a Java class and each field says with
annotations how it is encoded. PCAP Player loads this JSON into the same model as its plain ICD JSON
(`docs/icd-json-format.md`), so decoding, time fixing and the ICD tree work the same way.

## The file

```json
{
  "format": "annotations",
  "id": "gw-messenger-v1",
  "name": "GwMessenger v1",
  "byteOrder": "BIG_ENDIAN",
  "bitOrder": "MSB_FIRST",
  "header": { "class": "Header", "opcodeField": "msgType", "lengthField": "msgLength", "lengthIncludesHeader": true },
  "messages": [ { "opcode": "0x01", "class": "Msg" } ],
  "classes": { "Header": { "fields": [ ... ] }, "Msg": { "fields": [ ... ] } },
  "enums": { "EMode": { "constants": ["OFF", "ON"] } }
}
```

| Property | Required | Meaning |
|---|---|---|
| `format` | yes | `"annotations"`. Without it the file is read as the plain ICD JSON. |
| `id`, `name`, `description` | id, name | As in the plain format. Keep `id` when fixing the ICD; use a new one for a new version. |
| `byteOrder` | no | `BIG_ENDIAN` (default: the legacy code reads with Java's `DataInputStream`, which is big-endian) or `LITTLE_ENDIAN`. |
| `bitOrder` | no | Where the first `@Bits` field of a chunk sits: `MSB_FIRST` (default, the most significant bits) or `LSB_FIRST`. See [Assumptions](#assumptions-to-confirm). |
| `header` | yes | `class`: the header class. `opcodeField`, `lengthField`: its fields that hold the message type and length. `lengthIncludesHeader` (default true), `lengthUnit` (`BYTES`, `WORDS16`, `WORDS32`), `maxMessageBytes` as in the plain format. |
| `messages` | yes | `[{ "opcode": 1 or "0x01", "class": "Msg", "name": optional, default the class name }]`. |
| `classes` | yes | Class name → `{ binaryData, validated, charset, description, fields }`. |
| `enums` | no | Enum name → `{ "constants": [...] }` or `{ "intValued": {...} }`, and `description`. |

## Classes

| Property | Annotation | Meaning |
|---|---|---|
| `binaryData` | `@BinaryData` | The class can be sent in a byte stream. Informational. |
| `validated` | `@Validated` | The class's `validRange` rules are checked. PCAP Player always checks them. |
| `charset` | `@Charset(value, isReversed)` | The charset of every `String` in the class (default `US-ASCII`). `isReversed`: the text is stored last character first, as legacy systems store Hebrew; PCAP Player turns it back. |
| `fields` | | The fields, in source order. They are read by `ordinal`, smallest first. |

## Fields

Every field has `name`, `type` (the Java type as written) and `ordinal`.

| Property | Annotation | How PCAP Player reads it |
|---|---|---|
| `ordinal` | `@Ordinal(n)` | The read order: smallest first. Gaps and the starting number do not matter. Not a byte or bit position. |
| `type` | the Java type | `byte` 1 byte, `short` 2, `int` 4, `long` 8, `float` 4, `double` 8, `boolean` 1, all signed. `String` needs `size`. A class name is a nested class, an enum name an enum. `X[]` is an array of X. |
| `unsigned` | `@Unsigned` | Java has no unsigned types, so an unsigned value is kept in the next larger Java type and only half of it is on the wire: `@Unsigned long` is 4 bytes, `@Unsigned int` 2 bytes, `@Unsigned short` 1 byte. When an annotation gives the size (`enumerable`, `fixedPoint.type`, `lengthEncoded.value`), the value is read at that size, unsigned. |
| `bits` | `@Bits(value, bitfield)` | Consecutive `bits` fields (by ordinal) are cut from one chunk of `bitfield` bits (`BITS8`, `BITS16`, `BITS24`, `BITS32`). Their `value`s must add up to exactly the chunk. Each bit field keeps its own name, and is read as an unsigned number. |
| `bit` | `@Bit` | Like `bits` with `value` 1, inside the chunk that the `bits` fields before it opened. The Java field is `boolean`. |
| `size` | `@Size(n)` | A `String` of n bytes (the text ends at the first zero byte), or a fixed array of n elements. |
| `enumerable` | `@Enumerable(byte.class)` | An enum stored as an integer of that size. A plain enum's values are its constants' positions (0, 1, 2 ...); an `IntValued` enum's are in `intValued`. With `@Bits` the size comes from the bits, and the field has no `enumerable`. |
| `fixedPoint` | `@FixedPoint(lsb, type)` | An integer of `type`'s size; the real value is the integer × `lsb` (shown in decoded messages). |
| `validRange` | `@ValidRange(minValue, maxValue)` | The real value (after `lsb`) must be in the range; decoded messages mark it `OUT_OF_RANGE` otherwise. |
| `invalidityValue` | `@InvalidityValue(n)` | The stored integer n means "no valid value"; decoded messages mark it `INVALIDITY_VALUE`. The Java code's `<name>Validity` flag is `@Transient` and not in the JSON. |
| `lengthEncoder` | `@LenghtEncoder(isLengthInBytes)` | This integer field holds an array's length. `isLengthInBytes: true`: the length is in bytes, and the number of elements is that divided by the element size. False: it is the number of elements. |
| `lengthEncoded` | `@LengthEncoded(encodingField, value, unsigned)` | A variable-length array. With `encodingField`: that field (earlier, by ordinal) holds the length. Without it, and with `value`: the length is an integer of `value` bits (8, 16, 32; 24 unsigned only) written right before the array; PCAP Player shows it as `<array>Length`. With neither: the field just before the array holds the length. |
| `binaryInternal` | `@BinaryInternal` | A field that exists only for the encoding (such as a length). Decoded like any field. |
| `transient` | `@Transient` / `transient` | Not on the wire. The skill leaves such fields out; PCAP Player skips them if present. |
| `existenceEncoded` | `@ExistenceEncoded(method, params, isAbsolutePath)` | The field is there only when a Java method says so. PCAP Player cannot run it: the message is decoded up to this field, and the rest of its bytes are kept and sent unchanged. |
| `custom` | `@Custom(Class)` | Read by a `BinaryCustomizer` class. Same as `existenceEncoded`: decoded up to here. |
| `encoded` | `@Encoded(codec, underlyingType)` | Read by a codec class. Same as `existenceEncoded`: decoded up to here. |

## Paths

Decoded values and time-fix fields are addressed by path, as in the plain format: `header.msgType`,
`azimuth`, `tracks[2].range`. A `bits` field is addressed by its own name (`priority`, not
`bits_priority.priority`). An array's length written before it is `<array>Length`.

## Assumptions to confirm

These points are not stated in the annotation document. Each is one setting or one rule in the code,
so it is easy to change once confirmed with a real message:

1. **Bit order**: the first `bits` field of a chunk takes its most significant bits (`MSB_FIRST`).
   If the legacy code fills from bit 0 instead, set `"bitOrder": "LSB_FIRST"`.
2. **`@Unsigned` alone halves the Java type** (`@Unsigned int` = 2 bytes), as the document describes
   for `long` ("reads only half").
3. **`@LengthEncoded` without `encodingField`**: with `value`, an integer of `value` bits written right
   before the array; without `value`, the field just before the array.
4. **A `bits` field is unsigned** even without `@Unsigned` (`int b:3` holding 5, not -3).
