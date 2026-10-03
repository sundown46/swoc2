package io.swoc2.domain.picture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class IdentityTest {

    @Test
    void identityAndDimensionFromSidc() {
        assertThat(Identity.fromSidc("SFSPCLFF-------")).isEqualTo(Identity.FRIEND);
        assertThat(Identity.fromSidc("shgp-----------")).isEqualTo(Identity.HOSTILE);
        assertThat(Identity.fromSidc(null)).isEqualTo(Identity.UNKNOWN);
        assertThat(Identity.fromSidc("S?")).isEqualTo(Identity.UNKNOWN);
        assertThat(Dimension.fromSidc("SFSPCLFF-------")).isEqualTo(Dimension.SEA_SURFACE);
        assertThat(Dimension.fromSidc("SFAPMF---------")).isEqualTo(Dimension.AIR);
        assertThat(Dimension.fromSidc("x")).isEqualTo(Dimension.UNKNOWN);
    }

    @Test
    void sourceKeyValidates() {
        assertThat(new SourceKey("c1", null, "100").sourceSystemId()).isEmpty();
        assertThatThrownBy(() -> new SourceKey(" ", "s", "1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SourceKey("c", "s", "x".repeat(129))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void overrideValidates() {
        assertThat(ContactOverride.NONE.isEmpty()).isTrue();
        assertThatThrownBy(() -> new ContactOverride("", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ContactOverride(null, null, null, "SFS"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
