package io.swoc2.sedap.codec;

import static org.assertj.core.api.Assertions.assertThat;

import io.swoc2.sedap.MessageType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Typed values decoded from ICD samples (ICD §6), checked field by field. */
class DecodedValuesTest {

    private final SedapDecoder decoder = new SedapDecoder();

    private SedapMessage decode(String line) {
        DecodeResult result = decoder.decode(line);
        assertThat(result.rejected()).isFalse();
        return result.message();
    }

    @Test
    void contact() {
        SedapMessage m = decode(
                "CONTACT;5E;0191C643A8AF;83C5;R;;;100;FALSE;53.32;8.11;0;;;;120;275;;;;;;;FGS Bayern;AR;SFSPCLFF-------;;;;VXNlIENIMjI=");

        assertThat(m.type()).isEqualTo(MessageType.CONTACT);
        assertThat(m.header())
                .isEqualTo(new Header(0x5E, Instant.ofEpochMilli(0x0191C643A8AFL), "83C5", 'R', false, null));
        assertThat(m.text("ContactID")).contains("100");
        assertThat(m.value("DeleteFlag", Boolean.class)).contains(false);
        assertThat(m.number("Latitude")).contains(53.32);
        assertThat(m.number("Longitude")).contains(8.11);
        assertThat(m.number("Altitude")).contains(0.0);
        assertThat(m.number("SpeedOverGround")).contains(120.0);
        assertThat(m.number("CourseOverGround")).contains(275.0);
        assertThat(m.number("Heading")).isEmpty();
        assertThat(m.text("Name")).contains("FGS Bayern");
        assertThat(m.text("Source")).contains("AR");
        assertThat(m.text("SIDC")).contains("SFSPCLFF-------");
        assertThat(m.value("Comment", Base64Data.class).orElseThrow().text(StandardCharsets.ISO_8859_1))
                .isEqualTo("Use CH22");
    }

    @Test
    void graphicPath() {
        SedapMessage m = decode(
                "GRAPHIC;78;0195238E45AD;910E;U;;;A327;;01;1;80808000;;FFFF0000;;Transit;54.23,12.86#54.30,12.9#54.55,13.30");

        assertThat(m.variant().name()).isEqualTo("Path");
        assertThat(m.text("Annotation")).contains("Transit");
        assertThat(m.value("Path", List.class).orElseThrow())
                .containsExactly(
                        new Coordinate(54.23, 12.86, null),
                        new Coordinate(54.30, 12.9, null),
                        new Coordinate(54.55, 13.30, null));
    }

    @Test
    void commandMoveToWithArrivalTime() {
        SedapMessage m =
                decode("COMMAND;2C;0195238E25AD;E4B3;C;TRUE;;ORKA;0331;00;;24;53.4397;8.2262;50;5;019D25300FC0");

        assertThat(m.header().acknowledgement()).isTrue();
        assertThat(m.text("Recipient")).contains("ORKA");
        assertThat(m.text("CmdID")).contains("0331");
        assertThat(m.value("CmdFlag", Integer.class)).contains(0x00);
        assertThat(m.variant().name()).isEqualTo("Move to");
        assertThat(m.number("Lat")).contains(53.4397);
        assertThat(m.number("Lon")).contains(8.2262);
        assertThat(m.number("Alt")).contains(50.0);
        assertThat(m.number("Tolerance")).contains(5.0);
        assertThat(m.value("ArrivalTime", Instant.class)).contains(Instant.ofEpochMilli(0x019D25300FC0L));
    }

    @Test
    void commandRecordVideoWithExecutionTime() {
        SedapMessage m = decode("COMMAND;2C;0195238E25AD;E4B3;C;TRUE;;ORKA;0331;01;019D75300FFF;34;CAM1;ON;3600");

        assertThat(m.variant().name()).isEqualTo("Record video");
        assertThat(m.value("CmdExTime", Instant.class)).contains(Instant.ofEpochMilli(0x019D75300FFFL));
        assertThat(m.text("CameraID")).contains("CAM1");
        assertThat(m.value("Recording", Boolean.class)).contains(true);
        assertThat(m.value("Duration", Integer.class)).contains(3600);
    }

    @Test
    void statusLevelsAndBase64() {
        SedapMessage m = decode(
                "STATUS;15;0195238E25AD;75DA;U;;;4;2;MLG#20;;Accu1#50;;443D;01;MTAuMC4wLjEzMg==;;RnVsbHkgb3BlcmF0aW9uYWw=");

        assertThat(m.value("TecStatus", Integer.class)).contains(4);
        assertThat(m.value("AmmunitionLevels", List.class).orElseThrow()).containsExactly(new Level("MLG", 20));
        assertThat(m.value("BatteryLevels", List.class).orElseThrow()).containsExactly(new Level("Accu1", 50));
        assertThat(m.value("IP/Host", Base64Data.class).orElseThrow().text(StandardCharsets.ISO_8859_1))
                .isEqualTo("10.0.0.132");
        assertThat(m.value("Text", Base64Data.class).orElseThrow().text(StandardCharsets.ISO_8859_1))
                .isEqualTo("Fully operational");
    }

    @Test
    void emissionFrequencyList() {
        SedapMessage m = decode(
                "EMISSION;5E;0195238E15AD;66A3;R;;;100;;53.32;8.11;0;;;;20;8725000.0#8735000.0;20000;3;00;02;06;10233;;U0EtOA==");

        assertThat(m.value("Frequencies", List.class).orElseThrow()).containsExactly(8725000.0, 8735000.0);
        assertThat(m.value("Function", Integer.class)).contains(0x06);
        assertThat(m.number("Bearing")).contains(20.0);
    }

    @Test
    void textBase64Chat() {
        SedapMessage m = decode("TEXT;7B;0195238E285B;324E;U;;;ORKA;04;BASE64;IlRoaXMgaXMgYSBjaGF0IG1lc3NhZ2UhIg==");

        assertThat(m.value("Type", Integer.class)).contains(0x04);
        assertThat(m.text("Encoding")).contains("BASE64");
        assertThat(m.text("Text")).contains("IlRoaXMgaXMgYSBjaGF0IG1lc3NhZ2UhIg==");
    }

    @Test
    void compressedSampleFromIcd() {
        // ICD §3.3: deflate + BASE64 of the whole message incl. header.
        DecodeResult result =
                decoder.decode("C3GNCLE2NbY2MLQ0NTK2MHQ1MnSyNjYycbUOtg4JCnW1BgJDaz9/P1drpZCMzGIFIErMU0jMSS0qUVQCAA==");

        assertThat(result.compressed()).isTrue();
        assertThat(result.message().type()).isEqualTo(MessageType.TEXT);
        assertThat(result.raw()).isEqualTo("TEXT;53;01952381E21B;324E;S;TRUE;;;;1;NONE;\"This is an alert!\"");
    }

    @Test
    void heartbeatWithoutHeader() {
        SedapMessage m = decode("HEARTBEAT");

        assertThat(m.header()).isEqualTo(Header.EMPTY);
        assertThat(SedapEncoder.encode(m)).isEqualTo("HEARTBEAT");
    }
}
