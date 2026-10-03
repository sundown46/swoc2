# SEDAP-Express test sender

Dev tool (not part of the product): plays a moving SEDAP-Express scenario (one OWNUNIT, N
contacts, HEARTBEAT, TEXT) over TCP, per ICD v1.4.8. Single Java file, no dependencies, Java 17+.

```bash
# SWOC2 connects to the sender (connection type "SEDAP-Express TCP client"):
java tools/sedap-sender/SedapSender.java --mode server --port 50001 --contacts 200

# The sender connects to SWOC2 (connection type "SEDAP-Express TCP server" on port 50002):
java tools/sedap-sender/SedapSender.java --mode client --host 127.0.0.1 --port 50002
```

Options: `--contacts N`, `--rate S` (updates per second), `--sender ID`, `--lat`/`--lon` (centre),
`--garbage` (mix in malformed lines), `--relative` (every 5th contact with relative X/Y position).
UDP and MQTT modes follow with those transports (P1 M3b).
