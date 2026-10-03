# SEDAP-Express test sender

Dev tool (not part of the product): plays a moving SEDAP-Express scenario (one OWNUNIT, N
contacts, HEARTBEAT, TEXT) per ICD v1.4.8 over TCP, UDP or stdout (for MQTT). Single Java file,
no dependencies, Java 17+.

```bash
# SWOC2 connects to the sender (connection type "SEDAP-Express TCP client"):
java tools/sedap-sender/SedapSender.java --mode server --port 50001 --contacts 200

# The sender connects to SWOC2 (connection type "SEDAP-Express TCP server" on port 50002):
java tools/sedap-sender/SedapSender.java --mode client --host 127.0.0.1 --port 50002

# UDP datagrams (connection type "SEDAP-Express UDP unicast", local port 50003):
java tools/sedap-sender/SedapSender.java --mode udp --host 127.0.0.1 --port 50003

# MQTT through the dev Mosquitto (connection type "SEDAP-Express MQTT", broker localhost:5084):
java tools/sedap-sender/SedapSender.java --mode stdout | \
  docker exec -i swoc2-dev-mqtt-1 mosquitto_pub -l -t UNIITY-X/SIM1/CONTACT
```

Options: `--contacts N`, `--rate S` (updates per second), `--sender ID`, `--lat`/`--lon` (centre),
`--garbage` (mix in malformed lines), `--relative` (every 5th contact with relative X/Y position).
