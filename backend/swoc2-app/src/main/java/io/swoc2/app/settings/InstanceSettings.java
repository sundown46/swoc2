package io.swoc2.app.settings;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.Set;

/**
 * Instance-wide settings, stored as one validated JSON document.
 *
 * @param senderId our SEDAP-Express sender ID (ICD §5 "Sender", SDX-004); also used to drop our
 *     own messages on ingress (CON-005)
 * @param ownPosition position of this station, origin for range/bearing (MAP-012), or null
 * @param aging global aging defaults; connections can override them (PIC-004)
 * @param debugConsole who may open the debug console (DBG-002)
 */
public record InstanceSettings(
        @NotNull
        @Size(min = 1, max = 32)
        @Pattern(regexp = "[A-Za-z0-9_.:-]+", message = "letters, digits and _ . : - only")
        String senderId,

        @Valid OwnPosition ownPosition,
        @NotNull @Valid Aging aging,
        @NotNull @Valid DebugConsole debugConsole) {

    /** WGS84 degrees, altitude in metres (CLAUDE.md principle 5). */
    public record OwnPosition(
            @DecimalMin("-90") @DecimalMax("90") double latitude,
            @DecimalMin("-180") @DecimalMax("180") double longitude,
            Double altitude) {}

    /** Stale after {@code staleAfter}, removed after {@code deleteAfter} (MAP-019, PIC-004). */
    public record Aging(
            @NotNull Duration staleAfter, @NotNull Duration deleteAfter) {

        @AssertTrue(message = "staleAfter must be > 0 and shorter than deleteAfter, deleteAfter at most 7 days")
        public boolean isConsistent() {
            return staleAfter != null
                    && deleteAfter != null
                    && staleAfter.isPositive()
                    && staleAfter.compareTo(deleteAfter) < 0
                    && deleteAfter.compareTo(Duration.ofDays(7)) <= 0;
        }
    }

    /** Debug console availability (DBG-002): globally on/off, and which roles may use it. */
    public record DebugConsole(
            boolean enabled,
            @NotNull @Size(max = 4) Set<@Pattern(regexp = "viewer|operator|commander|admin") String> roles) {}

    /** Used until an admin saves settings for the first time. */
    public static InstanceSettings defaults() {
        return new InstanceSettings(
                "SWOC2",
                null,
                new Aging(Duration.ofMinutes(2), Duration.ofMinutes(30)),
                new DebugConsole(false, Set.of("admin")));
    }
}
