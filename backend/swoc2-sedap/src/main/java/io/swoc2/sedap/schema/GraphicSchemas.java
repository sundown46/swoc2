package io.swoc2.sedap.schema;

import static io.swoc2.sedap.schema.FieldKind.ANGLE;
import static io.swoc2.sedap.schema.FieldKind.COORDINATE;
import static io.swoc2.sedap.schema.FieldKind.COORDINATE_LIST;
import static io.swoc2.sedap.schema.FieldKind.DOUBLE;
import static io.swoc2.sedap.schema.FieldKind.LATITUDE;
import static io.swoc2.sedap.schema.FieldKind.LONGITUDE;
import static io.swoc2.sedap.schema.FieldSpec.of;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** GRAPHIC shapes and their type-dependent parameters (ICD §6.7 table "GraphicType"). */
public final class GraphicSchemas {

    private GraphicSchemas() {}

    private static final Map<Integer, VariantSpec> VARIANTS = new LinkedHashMap<>();

    private static void shape(int code, String name, FieldSpec... params) {
        VARIANTS.put(code, new VariantSpec(code, name, List.of(params), null, false));
    }

    private static FieldSpec lat(String name) {
        return of(name, LATITUDE).unit("deg").range(-90, 90).mandatory();
    }

    private static FieldSpec lon(String name) {
        return of(name, LONGITUDE).unit("deg").range(-180, 180).mandatory();
    }

    private static FieldSpec m(String name) {
        return of(name, DOUBLE).unit("m");
    }

    private static FieldSpec deg(String name) {
        return of(name, ANGLE).unit("deg");
    }

    static {
        shape(0x00, "Point", lat("Lat"), lon("Lon"), m("Alt"));
        shape(0x01, "Path", of("Path", COORDINATE_LIST).mandatory());
        shape(0x02, "Polygon", of("Polygon", COORDINATE_LIST).mandatory());
        // Rectangle: the corner is one "lat,lon,alt" element (commas), unlike Square (ICD §6.7).
        shape(0x03, "Rectangle", of("Corner", COORDINATE).mandatory(), m("Width"), m("Length"), deg("Rotation"));
        shape(0x04, "Square", lat("Lat"), lon("Lon"), m("Alt"), m("Width"), deg("Rotation"));
        shape(
                0x05,
                "Circle",
                lat("CenterLat"),
                lon("CenterLon"),
                m("CenterAlt"),
                m("Radius"),
                deg("StartAngle"),
                deg("EndAngle"));
        shape(
                0x06,
                "Ellipse",
                lat("CenterLat"),
                lon("CenterLon"),
                m("CenterAlt"),
                m("RadiusX"),
                m("RadiusY"),
                deg("Rotation"));
        shape(
                0x07,
                "Block",
                lat("Lat"),
                lon("Lon"),
                m("Alt"),
                m("Width"),
                m("Length"),
                m("Height"),
                deg("RotX"),
                deg("RotY"),
                deg("RotZ"));
        shape(0x08, "Sphere", lat("Lat"), lon("Lon"), m("Alt"), m("Radius"));
        shape(
                0x09,
                "Ellipsoid",
                lat("CenterLat"),
                lon("CenterLon"),
                m("CenterAlt"),
                m("RadiusX"),
                m("RadiusY"),
                m("RadiusZ"),
                deg("RotX"),
                deg("RotY"),
                deg("RotZ"));
        shape(
                0x0A,
                "SensorFieldOfView",
                deg("Azimuth"),
                of("Elevation", DOUBLE).unit("deg").range(-90, 90),
                of("Area", COORDINATE_LIST));
        shape(
                0x0B,
                "WeaponFieldOfFire",
                deg("Azimuth"),
                of("Elevation", DOUBLE).unit("deg").range(-90, 90),
                of("Area", COORDINATE_LIST));
    }

    public static Map<Integer, VariantSpec> variants() {
        return VARIANTS;
    }

    /** Code table for the GraphicType field. */
    static Map<Integer, String> names() {
        Map<Integer, String> names = new LinkedHashMap<>();
        VARIANTS.forEach((code, spec) -> names.put(code, spec.name()));
        return names;
    }
}
