---
name: icd-from-annotations
description: Converts Java message classes annotated with the legacy binary annotations (@BinaryData, @Ordinal, @Bits, @Unsigned, @Size, @LengthEncoded, @FixedPoint, @Enumerable ...) from the iaf.ofek.oryekarot.general.io library into an ICD JSON file for PCAP Player ("format": "annotations"). Use it when someone gives you annotated ICD classes (or asks to describe GwMessenger / legacy messages for PCAP Player) and wants the ICD JSON.
---

# ICD JSON from annotated Java classes

You turn annotated Java classes into one JSON file. The JSON **copies the annotations**: every
annotation becomes one JSON property with the same name (first letter lower-case), and its parameters
become that property's value. You do **not** calculate sizes, offsets or bit positions; PCAP Player
does that when it loads the file.

## What you need from the user

1. The `.java` files: the header class, the message classes, and every class and enum they use.
2. A config that the annotations do not contain. It says which class is the header, which header
   fields hold the opcode and the length, and the opcode of each message class:

   ```json
   {
     "id": "gw-messenger-v1",
     "name": "GwMessenger v1",
     "header": { "class": "Header", "opcodeField": "msgType", "lengthField": "msgLength", "lengthIncludesHeader": true },
     "messages": [ { "opcode": "0x01", "class": "Msg" } ]
   }
   ```

If the config or a class is missing, **ask for it**. Never guess an opcode or a header field.

## Steps

1. **Start the JSON** with `"format": "annotations"`, then copy every property of the config as it is.
2. **List the classes to convert.** Start with the header class and the message classes. For each
   field whose type (without `[]`) is not a Java built-in type (`byte short int long float double
   boolean char String` and `Byte Short Integer Long Float Double Boolean Character`), add that class
   or enum to the list. Repeat until nothing new is added.
3. **Write each class** under `"classes"` → `"<ClassName>"`:
   - `@BinaryData` → `"binaryData": true`; `@Validated` → `"validated": true`;
     `@Charset(value = "ISO-8859-8", isReversed = true)` → `"charset": {"value": "ISO-8859-8", "isReversed": true}`.
   - The class's Javadoc, on one line → `"description"`.
   - `"fields"`: one object per field, **in the order they appear in the source**. Do not sort them.
4. **Write each field** (see the table below). Leave a field out when it is `static`, `transient`,
   has `@Transient`, or has no `@Ordinal`. Tell the user about every field without `@Ordinal` that
   you left out.
   - `"name"`: the field name. `"type"`: the Java type **exactly as written** (`int`, `double`,
     `String`, `Track[]`, `byte[]`, `EMode`).
   - `"ordinal"`: the number in `@Ordinal(...)`.
   - One property per annotation, from the table.
   - The field's Javadoc, on one line → `"description"`.
5. **Write each enum** under `"enums"` → `"<EnumName>"`:
   - A plain enum: `"constants": ["OFF", "STANDBY", "ACTIVE"]` (the names in declaration order).
   - An enum that `implements IntValued`: `"intValued": {"LOW": 10, "HIGH": 20}` (each name with the
     number in its parentheses).
   - Its Javadoc → `"description"`. Leave `"enums"` out if there are none.
6. **Check your output**: save it and run `python3 scripts/check_icd.py OUTPUT.icd.json`. Fix every
   `ERROR` line and run it again until it prints `OK`.
7. **Answer** with the JSON file, then a short list of what the user must know: every `NOTE` line from
   the checker, and every field you left out.

## Annotation → JSON

| Annotation in Java | Property in the field's JSON |
|---|---|
| `@Ordinal(3)` | `"ordinal": 3` |
| `@Unsigned` | `"unsigned": true` |
| `@Bits(value = 5, bitfield = BitfieldLength.BITS8)` | `"bits": {"value": 5, "bitfield": "BITS8"}` |
| `@Bit` | `"bit": true` |
| `@Size(8)` | `"size": 8` |
| `@Enumerable(byte.class)` | `"enumerable": "byte"` |
| `@Enumerable` (no parameter, together with `@Bits`) | nothing: write the `bits` only |
| `@FixedPoint(lsb = 0.1, type = int.class)` | `"fixedPoint": {"lsb": 0.1, "type": "int"}` |
| `@ValidRange(minValue = 0, maxValue = 360)` | `"validRange": {"minValue": 0, "maxValue": 360}` |
| `@InvalidityValue(200)` | `"invalidityValue": 200` |
| `@LenghtEncoder(isLengthInBytes = true)` | `"lengthEncoder": {"isLengthInBytes": true}` |
| `@LengthEncoded(encodingField = "numOfReports")` | `"lengthEncoded": {"encodingField": "numOfReports"}` |
| `@LengthEncoded(value = 8, unsigned = true)` | `"lengthEncoded": {"value": 8, "unsigned": true}` |
| `@BinaryInternal` | `"binaryInternal": true` |
| `@ExistenceEncoded(method = "m", params = "p", isAbsolutePath = true)` | `"existenceEncoded": {"method": "m", "params": "p", "isAbsolutePath": true}` |
| `@Custom(ReadString.class)` | `"custom": "ReadString"` |
| `@Encoded(codec = TimeCodec.class, underlyingType = int.class)` | `"encoded": {"codec": "TimeCodec", "underlyingType": "int"}` |

Write only the parameters that the Java code sets. Write numbers as numbers, `true`/`false` as JSON booleans.

## Clean-ups (the code is not always consistent)

- **Bit chunk name**: always `BITS8`, `BITS16`, `BITS24` or `BITS32`. The code may say `BIT8`,
  `BIT32`, `bitfieldLength = ...` or `BitfieldLength.BITS8`: write only `"BITS8"` etc.
- **Class literals**: `int.class` → `"int"`, `ReadString.class` → `"ReadString"` (no `.class`, no package).
- **Parameter names** in any case (`MaxValue`, `maxValue`) → the name in the table (`maxValue`).
- **`@LenghtEncoder` and `@LengthEncoder`** are the same annotation → `"lengthEncoder"`.
- **Strings**: remove spaces at the start and end: `encodingField = "numOfReports "` → `"numOfReports"`.
- An annotation that is not in the table (and is not `@Override`, `@Deprecated`, `@SuppressWarnings`):
  leave it out and tell the user its name.

## Do not

- Do not compute sizes, offsets or bit positions, and do not add fields that are not in the code.
- Do not change the order of the fields, and do not renumber ordinals (gaps are fine).
- Do not drop a field that is read by code (`existenceEncoded`, `custom`, `encoded`): keep it, and
  tell the user that PCAP Player decodes the message only up to that field.
- Do not invent opcodes, ids or header fields: they come from the config.

## Example

Input, `Msg.java` (and the `Header` class and config shown above):

```java
/** The example from the annotation document. */
@BinaryData
public class Msg {

    public static final int sizeof = BinaryDataSerializer.sizeof(new Msg());

    @Ordinal(1)
    @Unsigned
    @Bits(value = 5, bitfield = BitfieldLength.BITS8)
    public int a;

    @Ordinal(2)
    @Bits(value = 3, bitfield = BitfieldLength.BITS8)
    public int b;

    @Ordinal(3)
    @Size(5)
    public String name;
}
```

Output, the `Msg` entry under `"classes"` (`sizeof` is static, so it is left out):

```json
"Msg": {
  "binaryData": true,
  "description": "The example from the annotation document.",
  "fields": [
    { "ordinal": 1, "name": "a", "type": "int", "unsigned": true, "bits": { "value": 5, "bitfield": "BITS8" } },
    { "ordinal": 2, "name": "b", "type": "int", "bits": { "value": 3, "bitfield": "BITS8" } },
    { "ordinal": 3, "name": "name", "type": "String", "size": 5 }
  ]
}
```

More complete examples, each with its input files and the exact expected output:

| Folder | Shows |
|---|---|
| `examples/01-basic` | `@Bits`, `@Size` String, static fields, the header |
| `examples/02-arrays` | nested classes, `@LenghtEncoder` + `@LengthEncoded`, `@Size` arrays, a length written before the array, `@FixedPoint` |
| `examples/03-enums-and-checks` | enums (plain and `IntValued`), `@Enumerable`, `@ValidRange`, `@InvalidityValue`, `@Transient`, `@Charset`, `@Bit`, `BIT8` → `BITS8` |
| `examples/04-read-by-code` | `@ExistenceEncoded` and `@Custom`, and the notes to give the user |

To compare your output with an example: `python3 scripts/check_icd.py OUT.icd.json --expected examples/02-arrays/expected.icd.json`.

What each annotation means on the wire, and how PCAP Player reads it: `reference/annotations.md`.
