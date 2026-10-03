package io.swoc2.sedap.schema;

import static io.swoc2.sedap.schema.FieldKind.ANGLE;
import static io.swoc2.sedap.schema.FieldKind.DOUBLE;
import static io.swoc2.sedap.schema.FieldKind.HEX_TIME;
import static io.swoc2.sedap.schema.FieldKind.LATITUDE;
import static io.swoc2.sedap.schema.FieldKind.LONGITUDE;
import static io.swoc2.sedap.schema.FieldKind.ON_OFF;
import static io.swoc2.sedap.schema.FieldKind.TEXT;
import static io.swoc2.sedap.schema.FieldSpec.of;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Declarative COMMAND schema (ARCHITECTURE §11.1, ROADMAP P0 item 9 "prototype the declarative
 * COMMAND schema"): every CmdType of ICD §6.8 with its parameters, units, ranges, required flags
 * and map-pick hints. The tasking wizard (TSK-002/003, P2) generates its forms from this, so the
 * full palette (SDX-007) needs no hand-written form per command. The codec uses the same
 * definitions to parse and generate the type-dependent tail.
 *
 * <p>Categories group the palette in the wizard. {@code confirm} marks commands that must not be
 * sent with a single click (weapons, self destruction, sanitize, parachute).
 */
public final class CommandSchemas {

    private CommandSchemas() {}

    public static final String SYSTEM = "System";
    public static final String MODE = "Mode";
    public static final String ENGINE = "Engine";
    public static final String MOVEMENT = "Movement";
    public static final String SENSORS = "Camera";
    public static final String ACTUATOR = "Actuator";
    public static final String WEAPON = "Weapon";
    public static final String CUSTOM = "Custom";

    private static final Map<Integer, VariantSpec> VARIANTS = new LinkedHashMap<>();

    private static void cmd(int code, String name, String category, FieldSpec... params) {
        VARIANTS.put(code, new VariantSpec(code, name, List.of(params), category, false));
    }

    private static void dangerous(int code, String name, String category, FieldSpec... params) {
        VARIANTS.put(code, new VariantSpec(code, name, List.of(params), category, true));
    }

    // Parameter building blocks. A "group" ties lat/lon/alt into one position input.
    private static FieldSpec lat(String name, String group) {
        return of(name, LATITUDE).unit("deg").range(-90, 90).mandatory().pick(PickKind.LATITUDE, group);
    }

    private static FieldSpec lon(String name, String group) {
        return of(name, LONGITUDE).unit("deg").range(-180, 180).mandatory().pick(PickKind.LONGITUDE, group);
    }

    private static FieldSpec alt(String name, String group) {
        return of(name, DOUBLE).unit("m").pick(PickKind.ALTITUDE, group);
    }

    private static FieldSpec metres(String name) {
        return of(name, DOUBLE).unit("m").range(0, Double.MAX_VALUE);
    }

    private static FieldSpec angle(String name) {
        return of(name, ANGLE).unit("deg");
    }

    /** Roll/pitch/elevation: signed, so not an {@link FieldKind#ANGLE} (which is a bearing). */
    private static FieldSpec signedAngle(String name, double limit) {
        return of(name, DOUBLE).unit("deg").range(-limit, limit);
    }

    private static FieldSpec percent(String name) {
        return of(name, DOUBLE).unit("%").range(0, 100);
    }

    private static FieldSpec time(String name, String description) {
        return of(name, HEX_TIME).describe(description);
    }

    private static FieldSpec id(String name) {
        return of(name, TEXT).mandatory().maxBytes(64);
    }

    private static FieldSpec target() {
        return of("TargetID", TEXT)
                .mandatory()
                .maxBytes(64)
                .pick(PickKind.OBJECT, null)
                .describe("Contact, point, emission or graphic to act on");
    }

    static {
        // ICD §6.8 CmdType table, in ICD order.
        cmd(0x00, "Power off", SYSTEM, time("PowerOnTime", "Power back on at (optional)"));
        cmd(0x01, "Restart", SYSTEM);
        cmd(0x02, "Standby", SYSTEM, time("WakeupTime", "Wake up at (optional)"));
        cmd(
                0x03,
                "Sync time",
                SYSTEM,
                of("NtpServer", TEXT).maxBytes(256).describe("IP or hostname of the NTP server"));
        cmd(0x04, "Calibrate gyro", SYSTEM);
        cmd(0x05, "Calibrate compass", SYSTEM);
        cmd(0x06, "Send status", SYSTEM);
        cmd(0x07, "Set manual mode", MODE);
        cmd(0x08, "Set semi-autonomous mode", MODE);
        cmd(0x09, "Set autonomous mode", MODE);
        cmd(0x0A, "Set failsafe mode", MODE);
        cmd(0x10, "Start engine", ENGINE);
        cmd(0x11, "Test engine", ENGINE);
        cmd(0x12, "Set engine power", ENGINE, percent("PowerLevel").mandatory());
        cmd(0x13, "Stop engine", ENGINE);
        cmd(0x14, "Stop movement", MOVEMENT);
        cmd(
                0x15,
                "Toggle lights",
                SYSTEM,
                of("Status", ON_OFF).mandatory(),
                percent("BrightnessLevel"),
                of("StrobePeriod", DOUBLE).unit("s").range(0, Double.MAX_VALUE));
        dangerous(0x16, "Deploy parachute", SYSTEM);
        cmd(0x20, "Set heading", MOVEMENT, angle("HeadingAngle").mandatory());
        cmd(0x21, "Set altitude", MOVEMENT, of("Altitude", DOUBLE).unit("m").mandatory());
        cmd(
                0x22,
                "Set speed",
                MOVEMENT,
                of("Speed", DOUBLE).unit("m/s").range(0, Double.MAX_VALUE).mandatory());
        cmd(
                0x23,
                "Rotate",
                MOVEMENT,
                angle("HeadingAngle"),
                signedAngle("RollAngle", 180),
                signedAngle("PitchAngle", 90));
        cmd(
                0x24,
                "Move to",
                MOVEMENT,
                lat("Lat", "target"),
                lon("Lon", "target"),
                alt("Alt", "target"),
                metres("Tolerance"),
                time("ArrivalTime", "Arrive at (optional)"));
        cmd(
                0x25,
                "Follow contact",
                MOVEMENT,
                of("ContactID", TEXT).mandatory().maxBytes(64).pick(PickKind.CONTACT, null));
        cmd(0x26, "Return home", MOVEMENT, metres("Tolerance"), time("ArrivalTime", "Arrive at (optional)"));
        cmd(
                0x27,
                "Set home location",
                MOVEMENT,
                lat("Lat", "home"),
                lon("Lon", "home"),
                alt("Alt", "home"),
                metres("Tolerance"));
        cmd(0x28, "Take off", MOVEMENT, lat("Lat", "site"), lon("Lon", "site"), angle("Direction"));
        cmd(0x29, "Land", MOVEMENT, lat("Lat", "site"), lon("Lon", "site"), angle("Direction"));
        cmd(0x2A, "Submerge", MOVEMENT, metres("Depth").mandatory());
        cmd(0x2B, "Surface", MOVEMENT);
        cmd(0x2C, "Dock", MOVEMENT, lat("Lat", "site"), lon("Lon", "site"), angle("Direction"));
        cmd(
                0x30,
                "Loiter/Orbiting",
                MOVEMENT,
                lat("CenterLat", "center"),
                lon("CenterLon", "center"),
                alt("Alt", "center"),
                metres("Radius"));
        cmd(0x31, "Scan", SENSORS, lat("Lat", "target"), lon("Lon", "target"), alt("Alt", "target"));
        cmd(
                0x32,
                "Scan area",
                SENSORS,
                lat("Lat1", "corner1"),
                lon("Lon1", "corner1"),
                lat("Lat2", "corner2"),
                lon("Lon2", "corner2"),
                of("Alt", DOUBLE).unit("m"),
                angle("RotationAngle"));
        cmd(0x33, "Take photo", SENSORS, id("CameraID"));
        cmd(
                0x34,
                "Record video",
                SENSORS,
                id("CameraID"),
                of("Recording", ON_OFF).mandatory(),
                // Integer seconds: the reference library parses it as an integer (ICD gives no unit).
                of("Duration", FieldKind.INTEGER).unit("s").range(0, Integer.MAX_VALUE));
        cmd(
                0x35,
                "Stream video",
                SENSORS,
                id("CameraID"),
                of("Streaming", ON_OFF).mandatory());
        cmd(
                0x36,
                "Set camera parameters",
                SENSORS,
                id("CameraID"),
                percent("Zoom"),
                // ICD spelling first (sent by default); the reference library only knows DL/IR/LI,
                // so those are accepted too (docs/icd/NOTES.md, OPEN_QUESTIONS Q-011).
                of("Mode", TEXT).options("DayLight", "InfraRed", "LightIntensifier", "DL", "IR", "LI"));
        cmd(0x37, "Set orientation of camera", SENSORS, id("CameraID"), angle("Azimuth"), signedAngle("Elevation", 90));
        cmd(0x40, "Actuator check", ACTUATOR, id("ActuatorID"));
        cmd(
                0x41,
                "Set orientation of actuator",
                ACTUATOR,
                id("ActuatorID"),
                angle("Azimuth"),
                signedAngle("Elevation", 90));
        cmd(0x42, "Actuator pick up object", ACTUATOR, id("ActuatorID"));
        cmd(0x43, "Actuator release object", ACTUATOR, id("ActuatorID"));
        dangerous(0x50, "Pre-arm check", WEAPON, id("WeaponID"));
        dangerous(0x51, "Arm", WEAPON, id("WeaponID"));
        dangerous(0x52, "Disarm", WEAPON, id("WeaponID"));
        dangerous(
                0x53,
                "Set orientation of weapon",
                WEAPON,
                id("WeaponID"),
                angle("Azimuth"),
                signedAngle("Elevation", 90));
        dangerous(0x54, "StartEngagement", WEAPON, id("WeaponID"), target());
        dangerous(0x55, "HoldEngagement", WEAPON, id("WeaponID"), target());
        dangerous(0x56, "StopEngagement", WEAPON, id("WeaponID"), target());
        dangerous(0xEE, "Sanitize system", SYSTEM);
        dangerous(0xEF, "Self destruction", SYSTEM);
        cmd(
                0xFF,
                "Generic Action",
                CUSTOM,
                of("KindOfAction", TEXT).mandatory().describe("Platform-specific action, needs a custom connector"));
    }

    /** All COMMAND types by CmdType code, in ICD order. */
    public static Map<Integer, VariantSpec> variants() {
        return VARIANTS;
    }

    /** Code table for the CmdType field. */
    static Map<Integer, String> names() {
        Map<Integer, String> names = new LinkedHashMap<>();
        VARIANTS.forEach((code, spec) -> names.put(code, spec.name()));
        return names;
    }
}
