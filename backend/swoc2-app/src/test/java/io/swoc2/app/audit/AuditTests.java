package io.swoc2.app.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Audit log (AUTH-005, ADM-006) against the real database. */
@SpringBootTest(properties = "swoc2.plugins.enabled=example")
class AuditTests {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private AuditLog auditLog;

    @Autowired
    private JdbcClient jdbc;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor user(String name, String role) {
        return oidcLogin()
                .idToken(t -> t.claim("preferred_username", name))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Test
    void adminActionIsAuditedWithReadableActorAndBeforeAfter() throws Exception {
        mvc.perform(post("/api/plugins/example/disable")
                        .with(user("admin1", "ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk());

        mvc.perform(get("/api/audit")
                        .param("action", "plugin.")
                        .param("actor", "admin1")
                        .with(user("admin1", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("plugin.disable"))
                .andExpect(jsonPath("$[0].actor").value("admin1"))
                .andExpect(jsonPath("$[0].targetId").value("example"))
                .andExpect(jsonPath("$[0].before.state").value("ENABLED"))
                .andExpect(jsonPath("$[0].after.state").value("DISABLED"));
    }

    @Test
    void outsideARequestTheActorIsSystem() {
        auditLog.record("test.system", "test", "1", null, Map.of("x", 1));

        String actor = jdbc.sql("SELECT actor FROM audit_event WHERE action = 'test.system' ORDER BY id DESC LIMIT 1")
                .query(String.class)
                .single();
        assertThat(actor).isEqualTo("system");
    }

    @Test
    void auditLogIsAppendOnly() {
        auditLog.record("test.immutable", "test", "1", null, null);

        assertThatThrownBy(() ->
                        jdbc.sql("UPDATE audit_event SET actor = 'mallory'").update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM audit_event").update()).hasMessageContaining("append-only");
    }

    @Test
    void onlyAdminsCanReadTheAuditLog() throws Exception {
        mvc.perform(get("/api/audit").with(user("op", "OPERATOR"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/audit")).andExpect(status().isUnauthorized());
    }

    @Test
    void queryValidatesLimitAndPaginates() throws Exception {
        for (int i = 0; i < 3; i++) {
            auditLog.record("test.page", "test", String.valueOf(i), null, null);
        }
        mvc.perform(get("/api/audit").param("limit", "0").with(user("a", "ADMIN")))
                .andExpect(status().isBadRequest());

        String body = mvc.perform(get("/api/audit")
                        .param("action", "test.page")
                        .param("limit", "2")
                        .with(user("a", "ADMIN")))
                .andExpect(jsonPath("$.length()").value(2))
                .andReturn()
                .getResponse()
                .getContentAsString();
        long lastId = tools.jackson.databind.json.JsonMapper.builder()
                .build()
                .readTree(body)
                .get(1)
                .path("id")
                .asLong();
        mvc.perform(get("/api/audit")
                        .param("action", "test.page")
                        .param("beforeId", String.valueOf(lastId))
                        .with(user("a", "ADMIN")))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void likeWildcardsInTheActionFilterAreEscaped() throws Exception {
        auditLog.record("test_wild", "test", "1", null, null);
        mvc.perform(get("/api/audit").param("action", "test%").with(user("a", "ADMIN")))
                .andExpect(jsonPath("$.length()").value(0));
    }
}
