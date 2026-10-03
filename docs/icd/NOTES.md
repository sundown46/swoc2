# SEDAP-Express ICD - interpretation notes

Where `SEDAP-Express-ICD-for-AI-v1.4.8.md` is ambiguous, this records how SWOC2's codec
(`backend/swoc2-sedap`, ADR 0017) interprets it (CLAUDE.md "When unsure": most tolerant
reasonable parser, warn, keep the raw value). Each note names the test that pins the behaviour.

## General

- **Charset:** lines are decoded as ISO-8859-1 (ICD §1 "ASCII = ISO-8859-1"). BASE64 text
  content is decoded with a caller-chosen charset; the ICD does not say which one inside BASE64.
- **Line terminator:** `\n`; a trailing `\r` is tolerated (`RoundTripTest` sends `\r\n`).
- **Trailing semicolons** are optional on input and never emitted (ICD §2).
- **Unknown/invalid values:** kept raw with a warning, value `null` (`ToleranceTest`). Latitude/
  longitude outside their range are dropped (value `null`), other out-of-range numbers are kept
  with a warning. Bearings outside [0, 360) are kept; normalisation happens in the domain mapping
  (CLAUDE.md principle 5), not in the codec.
- **Decimal comma:** a number with one `,` and no `.` (`53,32`) is accepted with a warning.
- **Codes** (`TEXT` Type, `CmdType`, ...) are hex; one digit (`4`) is accepted silently, as the
  ICD's own REST sample (§7) uses it.
- **Flags:** `TRUE`/`FALSE` case-insensitive; `1`/`0` accepted with a warning.
- **Timestamps:** hex Unix milliseconds. Values before 1990 or after 2200 get a warning
  ("implausible") but are kept: some ICD samples use small values (`0013E45956BE`, 1972).
  Generated timestamps always have 12 hex digits, like every ICD sample.
- **Header:** `Number` above `7F` is kept with a warning. `Acknowledgement=TRUE` without a Number
  is a warning (ICD §5 requires a Number for acknowledgement).
- **Message name** is matched case-insensitively with a warning for non-upper-case.
- **Fields beyond the schema** are kept (`extraFields`) and re-emitted on forwarding, with a
  warning. Exception: after an unknown COMMAND/GRAPHIC type, the parameters are kept without an
  extra warning (the type itself already warned).
- **Compression (§3.3):** if the first token is not a message name and the line is BASE64,
  raw-deflate inflation is tried (output capped at 1 MiB); success marks the result `compressed`.

## Per message

- **CONTACT/POINT (§6.2/§6.3):** both Lat/Lon and relX/Y/Z are marked `(M)`; the rule applied is
  "Lat/Lon **or** relX/relY". relZ is optional in practice (the POINT sample sends `0`). Both
  given: warning, absolute is used. A delete (`DeleteFlag=TRUE`) needs only the ID.
- **CONTACT MMSI/ICAO:** kept as text (MMSI can have leading zeros; ICAO is hex but samples are
  not normative). Validation happens in master data (MD-00x).
- **TEXT (§6.6):** if `Encoding=BASE64`, the Text is validated as BASE64 but kept encoded.
- **GRAPHIC (§6.7):** `GraphicType` is written with two hex digits (`08`, `0A`), as in the ICD
  samples. Rectangle's corner is one `lat,lon,alt` element (commas), unlike Square (semicolons) -
  taken literally from the ICD table. Path/Polygon coordinates may omit the altitude (ICD sample
  2: `54.23,12.86#...`). Colours are kept as hex strings.
- **COMMAND (§6.8):** `CmdType` may be missing when `CmdFlag=03` (cancel all, ICD sample 3).
  "Unix timestamp" parameters (PowerOn, Wakeup, ArrivalTime) are hex like all ICD timestamps
  (sample 4 line 4: `019D25300FC0`). Record video `Duration` is read as integer seconds (the ICD
  gives no unit; sample: `3600`). Camera `Mode` accepts the ICD spelling `DayLight|InfraRed|
  LightIntensifier` and the reference library's `DL|IR|LI`; SWOC2 sends the ICD spelling (see Q-011).
- **STATUS (§6.9):** level lists are `name#level#name#level`; a trailing `#` is tolerated.
  `CmdState=05` "Will be executed at `;<timestamp>`" is read as one extra trailing field
  (`ExecutionTime`). `Media` is one BASE64 field; how multiple URLs are separated inside it is not
  defined (open, decide with the first real sender).
- **HEARTBEAT (§6.13):** `Recipient` "single recipient, list, or empty" is read as a `#` list.
- **KEYEXCHANGE (§6.15):** both ICD samples appear to omit one field (AlgorithmType or Phase):
  `FE2A;0;128;1024;...` gives Phase=128. Not realigned; decoded literally with warnings
  (`icd-samples.txt`). KeyLength fields are validated against 128/256 and 1024/2048/4096.

## Malformed samples in the ICD itself

Pinned in `backend/swoc2-sedap/src/test/resources/icd-samples.txt` with their exact warnings:

- §3.3 compression sample `TEXT;53;...;S;TRUE;;;;1;NONE;"..."` has one `;` too many: Encoding
  reads as `1`, Text as `NONE`.
- §7 REST POST sample `TEXT;2E;...;S;;3;This is a chat message!;E4F1` is missing header and
  content fields; it decodes with warnings and the wrong field alignment.
- §7 REST POST OWNUNIT sample starts with a space (in the JSON string) and seems to be shifted by
  one field (`33.3;-0.15` lands in Pitch/Name); it decodes without a detectable error.

## Reference library v1.4.8 deviations from the ICD

Asserted in `ReferenceLibraryConformanceTest.KNOWN_LIBRARY_DEFECTS`:

- GRAPHIC: `GRAPHICTYPE_MATCHER = ^[0-9]$` rejects two-digit types, so the library cannot parse
  ICD-conformant GRAPHIC messages (including the ICD's own samples) or shapes `0A`/`0B` at all.
- COMMAND 36: camera mode parsed via `CameraMode.valueOf` with values `DL/IR/LI` only.
- KEYEXCHANGE: KeyLengthDHKEM is stored into `keyLengthSharedSecret`.
