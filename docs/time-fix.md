# Time fixing

When a record is replayed, the time fields inside its messages still hold the times of the recording.
Time fixing rewrites them while the record plays, so every message carries times around the moment it
is actually sent, and the gaps between its time fields stay as recorded.

It needs:

1. an ICD for the record's category (see [icd-json-format.md](icd-json-format.md)), and
2. a time-fix profile for that category: for each message type, one **base** field and the **fields
   to fix**, each with its **time kind**.

Categories without an ICD or profile, and message types the profile does not list, are sent exactly as
recorded.

## Time kinds

| Kind | Meaning | Unit |
|---|---|---|
| `REGULAR_SECONDS` | Unix epoch time, UTC | 1 s |
| `REGULAR_MILLIS` | Unix epoch time, UTC | 1 ms |
| `LTIME` | time since the last UTC midnight | 1 s |
| `GPS` | time since the last UTC midnight | 25 µs |

Formulas, with `t` an instant in nanoseconds since the epoch (UTC) and `v` the stored value:

| Kind | Value → instant | Instant → value |
|---|---|---|
| `REGULAR_SECONDS` | `t = v × 10⁹` | `v = t / 10⁹` |
| `REGULAR_MILLIS` | `t = v × 10⁶` | `v = t / 10⁶` |
| `LTIME` | `t = anchor(v × 10⁹)` | `v = (t mod 86 400 × 10⁹) / 10⁹` |
| `GPS` | `t = anchor(v × 25 000)` | `v = (t mod 86 400 × 10⁹) / 25 000` |

- **Data type.** Each field keeps its ICD type: any integer width, signed or unsigned, or a float.
  - Integer fields round down (`floor`).
  - `FLOAT32`/`FLOAT64` fields keep the fraction (an LTime of `3723.25`).
  - Bit fields hold an unsigned value of their width.
- **Anchoring.** LTime and GPS values hold a time of day without a date. `anchor` picks the instant with
  that time of day that is closest to the message's capture time: on the same UTC day, the day before,
  or the day after. So in a record that crosses midnight, `23:59:59.9` in a message captured at
  `00:00:00.1` belongs to the previous day.
- **Wrap-around.** When writing, LTime and GPS count from the UTC midnight of the new instant's own
  day. A field that lands after midnight starts again from 0, and one that lands before midnight gets
  a value near the end of the day.
- **Overflow.** A value that does not fit the field's type is set to the type's minimum or maximum.
  Each case is counted, and logged at most once per 10 s. Example: `REGULAR_MILLIS` in an `INT32` is
  always clamped.

## What happens to each message

Let `now` be the real wall-clock time (UTC) when the message's **first packet** is sent:

1. The base value and every field to fix are turned into instants, each with its own kind and type.
2. For each field to fix: `offset = fieldInstant − baseInstant`, at full precision.
3. The base is written as `now`, and each field as `now + offset`, each encoded in its own kind and
   type. Kinds can be mixed freely. For example, with an LTime base, a field stored as epoch
   milliseconds gets an epoch-milliseconds value.
4. The new bytes are written in place: same size, same byte order, and for bit fields only the field's
   bits. A field split across two packets is written into both. Packet sizes, packet boundaries and
   send times do not change.

Steps 1-2 are done before the replay starts: when the record is prepared (for a playlist, while the
previous item plays), its messages are decoded and the plan is built and cached. At send time only
step 3 and the byte writes remain, and only for packets that contain a time field. The other packets
are sent as recorded, without copying. The cached recording is never modified: patches go into copies
of the packets.

If playback jumps (seek) into the middle of a message, so that its first packet is skipped, the
message is patched when its first remaining packet is sent, with that moment as `now`.

The replay log shows `Time fixing with ICD … : N messages` when an item starts, and how many values
were clamped, if any. The server log has one line per plan with the decoding problems.

A field inside an array is selected once as `targets[*].field` and is fixed in every element, each
element with its own offset.

Messages that cannot be fixed are sent unchanged and counted:
- the base field is missing,
- a value is not a time (not a number, or out of range),
- the message could not be decoded.

## Checksums

No checksum is recalculated. If a message has a CRC or checksum, implement `PostPatchHook`
(`backend/.../icd/timefix/PostPatchHook.java`). It runs after a message's fields are rewritten and
before it is sent.
