package io.swoc2.sedap.schema;

import static io.swoc2.sedap.schema.FieldKind.ANGLE;
import static io.swoc2.sedap.schema.FieldKind.BASE64;
import static io.swoc2.sedap.schema.FieldKind.BOOLEAN;
import static io.swoc2.sedap.schema.FieldKind.CODE;
import static io.swoc2.sedap.schema.FieldKind.DOUBLE;
import static io.swoc2.sedap.schema.FieldKind.DOUBLE_LIST;
import static io.swoc2.sedap.schema.FieldKind.ENCODING;
import static io.swoc2.sedap.schema.FieldKind.HEX;
import static io.swoc2.sedap.schema.FieldKind.HEX_TIME;
import static io.swoc2.sedap.schema.FieldKind.INTEGER;
import static io.swoc2.sedap.schema.FieldKind.LATITUDE;
import static io.swoc2.sedap.schema.FieldKind.LEVEL_LIST;
import static io.swoc2.sedap.schema.FieldKind.LONGITUDE;
import static io.swoc2.sedap.schema.FieldKind.SIDC;
import static io.swoc2.sedap.schema.FieldKind.SOURCE_CHARS;
import static io.swoc2.sedap.schema.FieldKind.TEXT;
import static io.swoc2.sedap.schema.FieldSpec.of;

import io.swoc2.sedap.MessageType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Schemas for all 15 message types of ICD v1.4.8 §6. Field names follow the ICD exactly, so a
 * field can be looked up by the name an operator sees in the ICD. Where the ICD is ambiguous the
 * interpretation is noted inline and in {@code docs/icd/NOTES.md}.
 */
public final class SedapSchemas {

    private SedapSchemas() {}

    /** ICD §2: untyped/text fields are limited to 256 bytes unless stated otherwise. */
    public static final int DEFAULT_TEXT_MAX_BYTES = 256;

    // --- Code tables -------------------------------------------------------------------------

    /** ICD §6.4 FreqAgility. */
    public static final Map<Integer, String> FREQ_AGILITY = Map.of(
            0x00,
            "Stable Fixed",
            0x01,
            "Agile",
            0x02,
            "Periodic",
            0x03,
            "Hopper",
            0x04,
            "Batch Hopper",
            0x05,
            "Unknown");

    /** ICD §6.4 PRFAgility. */
    public static final Map<Integer, String> PRF_AGILITY = Map.of(
            0x00,
            "Fixed periodic",
            0x01,
            "Staggered",
            0x02,
            "Jittered",
            0x03,
            "Wobbulated",
            0x04,
            "Sliding",
            0x05,
            "Dwell switch",
            0x06,
            "Unknown PRF",
            0x07,
            "CW");

    /** ICD §6.4 Function. */
    public static final Map<Integer, String> EMISSION_FUNCTION = Map.ofEntries(
            Map.entry(0x00, "Unknown"),
            Map.entry(0x01, "ESM Beacon/Transponder"),
            Map.entry(0x02, "ESM Navigation"),
            Map.entry(0x03, "ESM Voice Communication"),
            Map.entry(0x04, "ESM Data Communication"),
            Map.entry(0x05, "ESM Radar"),
            Map.entry(0x06, "ESM IFF/ADS-B"),
            Map.entry(0x07, "ESM Guidance"),
            Map.entry(0x08, "ESM Weapon"),
            Map.entry(0x09, "ESM Jammer"),
            Map.entry(0x0A, "ESM Natural"),
            Map.entry(0x0B, "ACOUSTIC Object"),
            Map.entry(0x0C, "ACOUSTIC Submarine"),
            Map.entry(0x0D, "ACOUSTIC Variable Depth Sonar"),
            Map.entry(0x0E, "ACOUSTIC Array Sonar"),
            Map.entry(0x0F, "ACOUSTIC Active Sonar"),
            Map.entry(0x10, "ACOUSTIC Torpedo Sonar"),
            Map.entry(0x11, "ACOUSTIC Sono Buoy"),
            Map.entry(0x12, "ACOUSTIC Decoy Signal"),
            Map.entry(0x13, "ACOUSTIC Hit Noise"),
            Map.entry(0x14, "ACOUSTIC Propeller Noise"),
            Map.entry(0x15, "ACOUSTIC Underwater Telephone"),
            Map.entry(0x16, "ACOUSTIC Communication"),
            Map.entry(0x17, "ACOUSTIC Noise"),
            Map.entry(0x18, "LASER Range Finder"),
            Map.entry(0x19, "LASER Designator"),
            Map.entry(0x1A, "LASER Beam Rider"),
            Map.entry(0x1B, "LASER Dazzler"),
            Map.entry(0x1C, "LASER Lidar"),
            Map.entry(0x1D, "LASER Weapon"),
            Map.entry(0x1E, "VISUAL Object"));

    /** ICD §6.6 TEXT Type. */
    public static final Map<Integer, String> TEXT_TYPE =
            Map.of(0x00, "Undefined", 0x01, "Alert", 0x02, "Warning", 0x03, "Notice", 0x04, "Chat");

    /** ICD §6.8 CmdFlag. */
    public static final Map<Integer, String> COMMAND_FLAG =
            Map.of(0x00, "Add", 0x01, "Replace (last)", 0x02, "Cancel (last)", 0x03, "Cancel all");

    /** ICD §6.9 TecStatus. */
    public static final Map<Integer, String> TEC_STATUS =
            Map.of(0, "Off/Absent", 1, "Initializing", 2, "Degraded", 3, "Operational", 4, "Fault");

    /** ICD §6.9 OpsStatus. */
    public static final Map<Integer, String> OPS_STATUS = Map.of(
            0, "Not operational",
            1, "Degraded",
            2, "Operational",
            3, "Operational (semi-autonomous)",
            4, "Operational (autonomous)");

    /** ICD §6.9 CmdState. */
    public static final Map<Integer, String> COMMAND_STATE = Map.of(
            0x00, "Undefined",
            0x01, "Executed successfully",
            0x02, "Partially successfully executed",
            0x03, "Not successfully executed",
            0x04, "Execution not possible (yet)",
            0x05, "Will be executed at timestamp");

    /** ICD §6.12 GENERIC ContentType - textual, so a TEXT field with these allowed values. */
    public static final List<String> GENERIC_CONTENT_TYPES = List.of("SEDAP", "ASCII", "NMEA", "XML", "JSON", "BINARY");

    /** ICD §6.15 AlgorithmType. */
    public static final Map<Integer, String> KEYEXCHANGE_ALGORITHM = Map.of(
            0, "DH",
            1, "ECDH",
            2, "Kyber-512",
            3, "Kyber-768",
            4, "Kyber-1024",
            5, "FrodoKEM-640 (AES)",
            6, "FrodoKEM-976 (AES)",
            7, "FrodoKEM-1344 (AES)");

    // --- Reusable field groups ---------------------------------------------------------------

    private static FieldSpec lat(String name) {
        return of(name, LATITUDE).unit("deg").range(-90, 90);
    }

    private static FieldSpec lon(String name) {
        return of(name, LONGITUDE).unit("deg").range(-180, 180);
    }

    private static FieldSpec metres(String name) {
        return of(name, DOUBLE).unit("m");
    }

    private static FieldSpec angle(String name) {
        return of(name, ANGLE).unit("deg");
    }

    /** ICD §6.2/§6.3: kinematics and dimensions shared by CONTACT and POINT. */
    private static List<FieldSpec> positionKinematicsDimensions() {
        return List.of(
                lat("Latitude").mandatory(),
                lon("Longitude").mandatory(),
                metres("Altitude"),
                metres("relX-Distance").mandatory(),
                metres("relY-Distance").mandatory(),
                metres("relZ-Distance").mandatory(),
                of("SpeedOverGround", DOUBLE).unit("m/s"),
                angle("CourseOverGround"),
                angle("Heading"),
                of("Roll", DOUBLE).unit("deg").range(-180, 180),
                of("Pitch", DOUBLE).unit("deg").range(-90, 90),
                metres("Width"),
                metres("Length"),
                metres("Height"));
    }

    private static List<FieldSpec> concat(List<FieldSpec> a, List<FieldSpec> b) {
        return java.util.stream.Stream.concat(a.stream(), b.stream()).toList();
    }

    private static final Map<MessageType, MessageSchema> SCHEMAS = new EnumMap<>(MessageType.class);

    private static void register(MessageSchema schema) {
        SCHEMAS.put(schema.type(), schema);
    }

    static {
        // ICD §6.1 OWNUNIT
        register(new MessageSchema(
                MessageType.OWNUNIT,
                "§6.1",
                List.of(
                        lat("Latitude").mandatory(),
                        lon("Longitude").mandatory(),
                        metres("Altitude"),
                        of("SpeedOverGround", DOUBLE).unit("m/s"),
                        angle("CourseOverGround"),
                        angle("Heading"),
                        of("Roll", DOUBLE).unit("deg").range(-180, 180),
                        of("Pitch", DOUBLE).unit("deg").range(-90, 90),
                        of("Name", TEXT).maxBytes(64),
                        of("SIDC", SIDC)),
                null,
                null));

        // ICD §6.2 CONTACT. Lat/Lon and relX/Y/Z are each "(M)"; the rule is: exactly one of the
        // two position variants is required. The codec checks that rule, not the individual flags.
        register(new MessageSchema(
                MessageType.CONTACT,
                "§6.2",
                concat(
                        concat(
                                List.of(of("ContactID", TEXT).mandatory(), of("DeleteFlag", BOOLEAN)),
                                positionKinematicsDimensions()),
                        List.of(
                                of("Name", TEXT).maxBytes(64),
                                of("Source", SOURCE_CHARS),
                                of("SIDC", SIDC),
                                of("MMSI", TEXT).maxBytes(16),
                                of("ICAO", TEXT).maxBytes(16),
                                // MediaData: "preferred length <= 65000 bytes when using UDP" - not a
                                // hard limit, so no maxBytes here (TCP has none, ICD §4).
                                of("MediaData", BASE64),
                                of("Comment", BASE64).maxBytes(8192 * 4 / 3 + 4))),
                null,
                null));

        // ICD §6.3 POINT, same position rule as CONTACT.
        register(new MessageSchema(
                MessageType.POINT,
                "§6.3",
                concat(
                        concat(
                                List.of(of("PointID", TEXT).mandatory(), of("DeleteFlag", BOOLEAN)),
                                positionKinematicsDimensions()),
                        List.of(
                                of("Name", TEXT).maxBytes(64),
                                of("SIDC", SIDC),
                                of("MediaData", BASE64),
                                of("Comment", BASE64).maxBytes(8192 * 4 / 3 + 4))),
                null,
                null));

        // ICD §6.4 EMISSION
        register(new MessageSchema(
                MessageType.EMISSION,
                "§6.4",
                List.of(
                        of("EmissionID", TEXT).mandatory(),
                        of("DeleteFlag", BOOLEAN),
                        lat("SensorLatitude").mandatory(),
                        lon("SensorLongitude").mandatory(),
                        metres("SensorAltitude"),
                        lat("EmitterLatitude"),
                        lon("EmitterLongitude"),
                        metres("EmitterAltitude"),
                        angle("Bearing").mandatory(),
                        of("Frequencies", DOUBLE_LIST).unit("Hz"),
                        of("Bandwidth", DOUBLE).unit("Hz"),
                        of("Power", DOUBLE).unit("dB(A)"),
                        of("FreqAgility", CODE).codes(FREQ_AGILITY),
                        of("PRFAgility", CODE).codes(PRF_AGILITY),
                        of("Function", CODE).codes(EMISSION_FUNCTION),
                        of("SpotNumber", TEXT),
                        of("SIDC", SIDC),
                        of("Comment", BASE64).maxBytes(65000 * 4 / 3 + 4)),
                null,
                null));

        // ICD §6.5 METEO (temperatures in deg C, pressure in hPa, visibility in km as stated in
        // the ICD - converting to SI is the domain mapper's job, not the codec's).
        register(new MessageSchema(
                MessageType.METEO,
                "§6.5",
                List.of(
                        of("SpeedThroughWater", DOUBLE).unit("m/s"),
                        of("WaterSpeed", DOUBLE).unit("m/s"),
                        angle("WaterDirection"),
                        of("WaterTemperature", DOUBLE).unit("degC"),
                        metres("WaterDepth"),
                        of("AirTemperature", DOUBLE).unit("degC"),
                        of("DewPoint", DOUBLE).unit("degC"),
                        of("HumidityRel", DOUBLE).unit("%").range(0, 100),
                        of("Pressure", DOUBLE).unit("hPa"),
                        of("WindSpeed", DOUBLE).unit("m/s"),
                        angle("WindDirection"),
                        of("Visibility", DOUBLE).unit("km"),
                        metres("CloudHeight"),
                        of("CloudCover", DOUBLE).unit("%").range(0, 100),
                        of("Reference", TEXT)),
                null,
                null));

        // ICD §6.6 TEXT
        register(new MessageSchema(
                MessageType.TEXT,
                "§6.6",
                List.of(
                        of("Recipient", TEXT),
                        of("Type", CODE).codes(TEXT_TYPE),
                        of("Encoding", ENCODING),
                        of("Text", TEXT).mandatory().maxBytes(65000 * 4 / 3 + 4),
                        of("Reference", TEXT)),
                null,
                null));

        // ICD §6.7 GRAPHIC - shape parameters in GraphicSchemas.
        register(new MessageSchema(
                MessageType.GRAPHIC,
                "§6.7",
                List.of(
                        of("GraphicID", TEXT).mandatory(),
                        of("DeleteFlag", BOOLEAN),
                        of("GraphicType", CODE).mandatory().codes(GraphicSchemas.names()),
                        of("LineWidth", DOUBLE).range(1, Double.MAX_VALUE),
                        of("LineColor", HEX).maxBytes(8),
                        of("FillColor", HEX).maxBytes(8),
                        of("TextColor", HEX).maxBytes(8),
                        of("Encoding", ENCODING),
                        // Annotation: max 32 bytes plain; BASE64 of 32 bytes is 44 characters.
                        of("Annotation", TEXT).maxBytes(44)),
                "GraphicType",
                GraphicSchemas.variants()));

        // ICD §6.8 COMMAND - per-type parameters in CommandSchemas. CmdType is "(M)", but the
        // "cancel all" flag (03) is sent without it (ICD §6.8 sample 3), so it is not marked
        // required here; the codec checks "CmdType or CmdFlag=03" instead.
        register(new MessageSchema(
                MessageType.COMMAND,
                "§6.8",
                List.of(
                        of("Recipient", TEXT).mandatory(),
                        of("CmdID", HEX).maxBytes(4),
                        of("CmdFlag", CODE).mandatory().codes(COMMAND_FLAG),
                        of("CmdExTime", HEX_TIME),
                        of("CmdType", CODE).codes(CommandSchemas.names())),
                "CmdType",
                CommandSchemas.variants()));

        // ICD §6.9 STATUS
        register(new MessageSchema(
                MessageType.STATUS,
                "§6.9",
                List.of(
                        of("TecStatus", INTEGER).codes(TEC_STATUS).range(0, 9),
                        of("OpsStatus", INTEGER).codes(OPS_STATUS).range(0, 9),
                        of("AmmunitionLevels", LEVEL_LIST),
                        of("FuelLevels", LEVEL_LIST),
                        of("BatteryLevels", LEVEL_LIST),
                        of("StorageLevels", LEVEL_LIST),
                        of("CmdID", HEX).maxBytes(4),
                        of("CmdState", CODE).codes(COMMAND_STATE),
                        of("IP/Host", BASE64).maxBytes(88),
                        // Media: "list of video stream/image URLs" as one BASE64 field; how the
                        // list is separated inside is undefined (docs/icd/NOTES.md).
                        of("Media", BASE64).maxBytes(4096 * 4 / 3 + 4),
                        of("Text", BASE64),
                        // CmdState 05 = "Will be executed at ;<timestamp>": read as one extra
                        // trailing timestamp field (docs/icd/NOTES.md).
                        of("ExecutionTime", HEX_TIME)),
                null,
                null));

        // ICD §6.10 ACKNOWLEDGE
        register(new MessageSchema(
                MessageType.ACKNOWLEDGE,
                "§6.10",
                List.of(
                        of("Recipient", TEXT).mandatory(),
                        of("TypeOfMessage", TEXT).mandatory(),
                        of("NumberOfMessage", HEX).mandatory().maxBytes(2)),
                null,
                null));

        // ICD §6.11 RESEND
        register(new MessageSchema(
                MessageType.RESEND,
                "§6.11",
                List.of(
                        of("Recipient", TEXT).mandatory(),
                        of("NameOfMissingMessage", TEXT).mandatory(),
                        of("NumberOfMissingMessage", HEX).mandatory().maxBytes(2)),
                null,
                null));

        // ICD §6.12 GENERIC
        register(new MessageSchema(
                MessageType.GENERIC,
                "§6.12",
                List.of(
                        of("ContentType", TEXT).options(GENERIC_CONTENT_TYPES.toArray(String[]::new)),
                        of("Encoding", ENCODING),
                        of("Content", TEXT).maxBytes(8192 * 4 / 3 + 4)),
                null,
                null));

        // ICD §6.13 HEARTBEAT ("single recipient, list, or empty")
        register(new MessageSchema(
                MessageType.HEARTBEAT, "§6.13", List.of(of("Recipient", FieldKind.TEXT_LIST)), null, null));

        // ICD §6.14 TIMESYNC
        register(new MessageSchema(MessageType.TIMESYNC, "§6.14", List.of(of("Timestamp", HEX_TIME)), null, null));

        // ICD §6.15 KEYEXCHANGE ((M*) fields depend on phase/algorithm: not marked required).
        register(new MessageSchema(
                MessageType.KEYEXCHANGE,
                "§6.15",
                List.of(
                        of("Recipient", TEXT),
                        of("AlgorithmType", INTEGER).mandatory().codes(KEYEXCHANGE_ALGORITHM),
                        of("Phase", INTEGER).mandatory().range(0, 2),
                        of("KeyLengthSharedSecret", INTEGER).codes(Map.of(128, "128 bit", 256, "256 bit")),
                        of("KeyLengthKEM", INTEGER).codes(Map.of(1024, "1024 bit", 2048, "2048 bit", 4096, "4096 bit")),
                        of("Prime", HEX).maxBytes(8192),
                        of("NaturalNumber", HEX).maxBytes(8192),
                        of("InitialisationVector", HEX).maxBytes(64),
                        of("PublicVariable/Key", BASE64).maxBytes(65536)),
                null,
                null));
    }

    /** Schema for a message type; every {@link MessageType} has one. */
    public static MessageSchema schemaFor(MessageType type) {
        return SCHEMAS.get(type);
    }
}
