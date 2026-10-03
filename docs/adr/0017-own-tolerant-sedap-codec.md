# 0017 - Own tolerant, schema-driven SEDAP-Express codec; reference library as test oracle

Date: 2026-10-03
Status: Accepted (2026-10-03, PR #5 merged; Leon: implement strictly per ICD, see Q-011)

## Context

ARCHITECTURE §11.1 planned that "the codec wraps the reference library" `io.github.uniity-team:
sedapexpress` and adds a tolerant pre-parser where the library is too strict. Spike C integrated
the library (v1.4.8, = our ICD revision) and checked it against every ICD sample and against
generated messages for every type, COMMAND type and GRAPHIC shape. Findings:

- **Runtime side effects:** a static initializer prints a banner to `System.out`; the library
  sets the JUL *global* logger to `Level.ALL` and logs every empty optional field at INFO and
  every invalid one at SEVERE (with untrusted network input that is a log-flooding vector); some
  failures go to `e.printStackTrace()`. `CompressUtils` shares one static `Inflater`/`Deflater`
  across threads (not thread-safe).
- **Not tolerant in the way SDX-001 requires:** invalid or unknown fields are logged and dropped,
  not kept raw; trailing fields beyond the schema are lost, so a received message cannot be
  forwarded unchanged.
- **Rejects ICD-conformant input:** `GRAPHICTYPE_MATCHER` is `^[0-9]$`, so every GRAPHIC with the
  ICD's two-digit type (`08`, `0A`, including the ICD's own samples) fails to parse. Camera mode
  (COMMAND 36) only accepts `DL/IR/LI`, the ICD says `DayLight|InfraRed|LightIntensifier`.
  KEYEXCHANGE writes KeyLengthDHKEM into the KeyLengthSharedSecret field (copy-paste bug).
- Mutable message classes with `char[]` SIDCs and per-type getter names that do not follow the
  ICD field names.

## Decision

- `swoc2-sedap` contains its **own codec**, driven by declarative schemas
  (`io.swoc2.sedap.schema`): one `MessageSchema` per ICD message type, field kinds/units/ranges/
  code tables, and per-variant parameter lists for GRAPHIC shapes and COMMAND types. The same
  COMMAND schemas are the "declarative COMMAND schema" for the tasking wizard (TSK-002/003).
- Decoding is tolerant (`SedapDecoder` never throws): invalid values stay raw with a warning,
  missing required fields are warnings, trailing fields are kept, compressed messages (ICD §3.3)
  are detected and inflated with a size cap. Encoding re-emits received fields byte-for-byte and
  formats built fields canonically. Generation (`SedapMessageBuilder`) is strict.
- The reference library stays a **test-scoped dependency** and serves as a conformance oracle:
  every ICD sample and every generated message is fed to it and all values both sides expose must
  agree. Its known defects are listed in the test, which asserts that they still exist, so a fixed
  upstream release is noticed.
- Its transitive dependencies the tests do not touch (jssc + native-lib-loader, Paho MQTT,
  Protobuf, Gson) are excluded. BouncyCastle stays (used by its MAC/crypto utilities, which the
  tests may use for SDX-013 later).

## Consequences

- No reference-library code on the runtime classpath, and none of its side effects in the server.
- We own the parser, so ICD changes must be applied to the schemas by hand; the conformance test
  against the library is the safety net for interpretation differences.
- SEDAP crypto (SDX-013, P4): MAC/encryption are not in our codec yet. When it lands, decide
  whether to call the library's `MACUtils`/`EncryptionUtils` at runtime (then it moves to compile
  scope, with its logging side effects contained) or implement them on BouncyCastle/JCA directly.
- Transports (SDX-002, P1) are built on Spring/Netty-style infrastructure in SWOC2, not on the
  library's `SEDAPExpressTCPClient` etc., for the same reasons.
- The library defects above affect **interoperability**: partners using the library cannot parse
  ICD-conformant GRAPHIC messages. Tracked as OPEN_QUESTIONS Q-011; to be reported upstream.
