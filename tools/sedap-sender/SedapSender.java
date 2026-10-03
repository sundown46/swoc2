// SEDAP-Express test sender (dev tool, not part of the product; CLAUDE.md "/tools").
// Single file, no dependencies, Java 17+ - runs directly:
//
//   java SedapSender.java --mode server --port 50001                  # SWOC2 "TCP client" connects here
//   java SedapSender.java --mode client --host 127.0.0.1 --port 50002 # connects to a SWOC2 "TCP server"
//   java SedapSender.java --mode udp --host 127.0.0.1 --port 50003    # UDP datagrams to SWOC2 "UDP unicast"
//   java SedapSender.java --mode stdout | mosquitto_pub -l -t UNIITY-X/SIM1/CONTACT   # MQTT via pipe
//
// Options: --contacts N (default 50), --rate S (updates per second per contact, default 1),
//          --sender ID (default SIM1), --lat/--lon centre (default 54.0/8.0), --garbage (mix in
//          malformed lines to watch tolerance and the debug console), --relative (some contacts
//          with relative X/Y positions to the OWNUNIT).
//
// Sends one OWNUNIT ("SIM-SHIP"), N CONTACTs moving on random courses, a HEARTBEAT every second
// and a TEXT chat message every 30 s, all per ICD v1.4.8 (numbering per type 00-7F, hex time).

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

public class SedapSender {

    static final String[] SIDCS = {
        "SFSPCLFF-------", "SHSPCLFF-------", "SNSPXF---------", "SUSP-----------",
        "SFAPMF---------", "SHAPMFF--------", "SNAPCF---------", "SFGPUCI--------",
        "SHGPEVATL------", "SUUPSN---------"
    };

    record Track(String id, double[] pos, double course, double speed, String sidc, String name, boolean relative) {}

    static final Map<String, Integer> numbers = new HashMap<>();

    static String next(String type) {
        int n = numbers.merge(type, 1, (a, b) -> (a + b) & 0x7F);
        return String.format("%02X", n);
    }

    static String hdr(String type, String sender, char classification) {
        return type + ";" + next(type) + ";" + String.format("%012X", System.currentTimeMillis()) + ";" + sender + ";" + classification + ";;;";
    }

    static String num(double v) {
        return String.format(Locale.ROOT, "%.6f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                boolean flag = i + 1 >= args.length || args[i + 1].startsWith("--");
                opt.put(args[i].substring(2), flag ? "true" : args[++i]);
            }
        }
        String mode = opt.getOrDefault("mode", "server");
        int port = Integer.parseInt(opt.getOrDefault("port", "50001"));
        int count = Integer.parseInt(opt.getOrDefault("contacts", "50"));
        double rate = Double.parseDouble(opt.getOrDefault("rate", "1"));
        String sender = opt.getOrDefault("sender", "SIM1");
        double lat0 = Double.parseDouble(opt.getOrDefault("lat", "54.0"));
        double lon0 = Double.parseDouble(opt.getOrDefault("lon", "8.0"));
        boolean garbage = opt.containsKey("garbage");
        boolean relative = opt.containsKey("relative");

        Random rnd = new Random(7);
        List<Track> tracks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            tracks.add(new Track(
                    "T" + (1000 + i),
                    new double[] {lat0 + (rnd.nextDouble() - 0.5) * 1.5, lon0 + (rnd.nextDouble() - 0.5) * 2.5},
                    rnd.nextDouble() * 360,
                    3 + rnd.nextDouble() * 25,
                    SIDCS[i % SIDCS.length],
                    "Track " + (1000 + i),
                    relative && i % 5 == 0));
        }
        double[] own = {lat0, lon0};

        while (true) {
            try (Output output = open(mode, opt.getOrDefault("host", "127.0.0.1"), port)) {
                long tick = 0;
                long periodMs = Math.max(50, (long) (1000 / rate));
                while (true) {
                    StringBuilder batch = new StringBuilder();
                    own[0] += 2.0 / 111_320;
                    batch.append(hdr("OWNUNIT", sender, 'U')).append(num(own[0])).append(';').append(num(own[1]))
                            .append(";0;2;0;0;;;SIM-SHIP;SFSPCLFF-------\n");
                    for (Track t : tracks) {
                        double dt = periodMs / 1000.0;
                        double rad = Math.toRadians(t.course());
                        t.pos()[0] += t.speed() * dt * Math.cos(rad) / 111_320;
                        t.pos()[1] += t.speed() * dt * Math.sin(rad) / (111_320 * Math.cos(Math.toRadians(t.pos()[0])));
                        batch.append(hdr("CONTACT", sender, 'U')).append(t.id()).append(";FALSE;");
                        if (t.relative()) {
                            double y = (t.pos()[0] - own[0]) * 111_320;
                            double x = (t.pos()[1] - own[1]) * 111_320 * Math.cos(Math.toRadians(own[0]));
                            batch.append(";;;").append(num(x)).append(';').append(num(y)).append(";0;");
                        } else {
                            batch.append(num(t.pos()[0])).append(';').append(num(t.pos()[1])).append(";0;;;;");
                        }
                        batch.append(num(t.speed())).append(';').append(num(t.course()))
                                .append(";;;;;;;").append(t.name()).append(";R;").append(t.sidc()).append('\n');
                    }
                    if (tick % Math.max(1, (long) rate) == 0) {
                        batch.append(hdr("HEARTBEAT", sender, 'U')).append('\n');
                    }
                    if (tick % Math.max(1, (long) (30 * rate)) == 0) {
                        batch.append(hdr("TEXT", sender, 'U')).append(";04;NONE;Test chat message ").append(tick).append('\n');
                    }
                    if (garbage && tick % 5 == 0) {
                        batch.append("GARBAGE LINE ").append(tick).append('\n');
                        batch.append(hdr("CONTACT", sender, 'U')).append("BAD;FALSE;95.0;8.0;;;;;;;;;;;;;bad latitude\n");
                    }
                    output.write(batch.toString());
                    tick++;
                    Thread.sleep(periodMs);
                }
            } catch (IOException e) {
                System.err.println("Disconnected (" + e.getMessage() + "), retrying in 2 s");
                Thread.sleep(2000);
            }
        }
    }

    /** Where the generated lines go. */
    interface Output extends AutoCloseable {
        void write(String lines) throws IOException;

        @Override
        void close() throws IOException;
    }

    static Output open(String mode, String host, int port) throws IOException {
        if (mode.equals("stdout")) {
            return new Output() {
                public void write(String lines) {
                    System.out.print(lines);
                    System.out.flush();
                }

                public void close() {}
            };
        }
        if (mode.equals("udp")) {
            java.net.DatagramSocket socket = new java.net.DatagramSocket();
            java.net.InetSocketAddress target = new java.net.InetSocketAddress(host, port);
            System.err.println("Sending UDP to " + target);
            return new Output() {
                public void write(String lines) throws IOException {
                    // Several messages per datagram are allowed (ICD §4); keep datagrams well below 64 KiB.
                    StringBuilder chunk = new StringBuilder();
                    for (String line : lines.split("\n")) {
                        if (chunk.length() + line.length() > 8000) {
                            send(chunk);
                            chunk.setLength(0);
                        }
                        chunk.append(line).append('\n');
                    }
                    send(chunk);
                }

                private void send(StringBuilder chunk) throws IOException {
                    if (chunk.length() > 0) {
                        byte[] b = chunk.toString().getBytes(StandardCharsets.ISO_8859_1);
                        socket.send(new java.net.DatagramPacket(b, b.length, target));
                    }
                }

                public void close() {
                    socket.close();
                }
            };
        }
        Socket socket = connect(mode, host, port);
        System.err.println("Connected: " + socket.getRemoteSocketAddress());
        OutputStream out = socket.getOutputStream();
        return new Output() {
            public void write(String lines) throws IOException {
                out.write(lines.getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
            }

            public void close() throws IOException {
                socket.close();
            }
        };
    }

    static ServerSocket server;

    static Socket connect(String mode, String host, int port) throws IOException {
        if (mode.equals("client")) {
            return new Socket(host, port);
        }
        if (server == null) {
            server = new ServerSocket(port);
            System.err.println("Listening on port " + port + " - add a SWOC2 connection 'SEDAP-Express TCP client' to this host/port");
        }
        return server.accept();
    }
}
