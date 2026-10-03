package io.swoc2.app.picture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.swoc2.domain.picture.Contact;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Picture REST API with roles, persistence and audit (PIC-002/003/005, AUTH-005). */
@SpringBootTest
class PictureApiTests {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private PictureStore store;

    @Autowired
    private JdbcClient jdbc;

    private MockMvc mvc;

    private static RequestPostProcessor user(String name, String role) {
        return oidcLogin()
                .idToken(t -> t.claim("preferred_username", name))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    private Contact contact(String track) {
        return store.upsert(PictureStoreTest.update(track, 53.5, 8.1, "SFSPCLFF-------", "Source"), Instant.now());
    }

    @Test
    void operatorOverrideIsSharedPersistedAndAudited() throws Exception {
        Contact c = contact("api-1");

        mvc.perform(put("/api/picture/contacts/" + c.id() + "/override")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed\",\"identity\":\"HOSTILE\"}")
                        .with(user("operator1", "OPERATOR"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed"))
                .andExpect(jsonPath("$.identity").value("HOSTILE"));

        mvc.perform(get("/api/picture/contacts/" + c.id()).with(user("viewer1", "VIEWER")))
                .andExpect(jsonPath("$.name").value("Renamed"))
                .andExpect(jsonPath("$.overridden.name").value("Renamed"));
        Integer rows = jdbc.sql("SELECT count(*) FROM picture_override WHERE source_track_id = 'api-1'")
                .query(Integer.class)
                .single();
        assertThat(rows).isEqualTo(1);
        mvc.perform(get("/api/audit").param("action", "contact.override").with(user("admin1", "ADMIN")))
                .andExpect(jsonPath("$[0].actor").value("operator1"))
                .andExpect(jsonPath("$[0].after.name").value("Renamed"));

        mvc.perform(delete("/api/picture/contacts/" + c.id() + "/override")
                        .with(user("operator1", "OPERATOR"))
                        .with(csrf()))
                .andExpect(jsonPath("$.name").value("Source"));
    }

    @Test
    void viewersCannotEditAndBadInputIsExplained() throws Exception {
        Contact c = contact("api-2");
        mvc.perform(put("/api/picture/contacts/" + c.id() + "/override")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\"}")
                        .with(user("viewer1", "VIEWER"))
                        .with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/picture/contacts/" + c.id() + "/override")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sidc\":\"NOPE\"}")
                        .with(user("operator1", "OPERATOR"))
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("sidc must be a 15-character letter SIDC"));
        mvc.perform(get("/api/picture/contacts/00000000-0000-0000-0000-000000000000")
                        .with(user("v", "VIEWER")))
                .andExpect(status().isNotFound());
    }

    @Test
    void wipeNeedsConfirmationAdminRoleAndIsAudited() throws Exception {
        contact("wipe-1");
        mvc.perform(post("/api/picture/wipe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirm\":\"WIPE\"}")
                        .with(user("operator1", "OPERATOR"))
                        .with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/picture/wipe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(user("admin1", "ADMIN"))
                        .with(csrf()))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/picture/wipe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirm\":\"WIPE\"}")
                        .with(user("admin1", "ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk());
        assertThat(store.size()).isZero();
        mvc.perform(get("/api/audit").param("action", "picture.wipe").with(user("admin1", "ADMIN")))
                .andExpect(jsonPath("$[0].details.removedContacts").isNumber());
    }

    @Test
    void summaryReportsCountsAndClassification() throws Exception {
        contact("sum-1");
        mvc.perform(get("/api/picture/summary").with(user("viewer1", "VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.highestClassification").isString());
    }
}
