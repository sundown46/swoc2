package io.swoc2.app.settings;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Instance settings API (ADM-003): validation, roles, persistence, audit. */
@SpringBootTest
class InstanceSettingsTests {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    private static final String VALID = """
            {"senderId":"OPS-1","ownPosition":{"latitude":53.5,"longitude":8.1,"altitude":12.0},
             "aging":{"staleAfter":"PT1M","deleteAfter":"PT10M"},
             "debugConsole":{"enabled":true,"roles":["admin","operator"]}}
            """;

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

    @Test
    void adminSavesSettingsEveryoneReadsThemAndTheChangeIsAudited() throws Exception {
        mvc.perform(put("/api/settings/instance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID)
                        .with(user("admin1", "ADMIN"))
                        .with(csrf()))
                .andExpect(status().isOk());

        mvc.perform(get("/api/settings/instance").with(user("viewer1", "VIEWER")))
                .andExpect(jsonPath("$.senderId").value("OPS-1"))
                .andExpect(jsonPath("$.ownPosition.latitude").value(53.5))
                .andExpect(jsonPath("$.aging.staleAfter").value("PT1M"));
        mvc.perform(get("/api/audit").param("action", "settings.update").with(user("admin1", "ADMIN")))
                .andExpect(jsonPath("$[0].actor").value("admin1"))
                .andExpect(jsonPath("$[0].after.senderId").value("OPS-1"));
    }

    @Test
    void nonAdminsCannotChangeSettings() throws Exception {
        mvc.perform(put("/api/settings/instance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID)
                        .with(user("op", "OPERATOR"))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidSettingsAreRejectedWithReadableProblem() throws Exception {
        String bad = """
                {"senderId":"bad;id","ownPosition":{"latitude":95,"longitude":8},
                 "aging":{"staleAfter":"PT10M","deleteAfter":"PT1M"},
                 "debugConsole":{"enabled":true,"roles":["root"]}}
                """;
        mvc.perform(put("/api/settings/instance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bad)
                        .with(user("admin1", "ADMIN"))
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://swoc2.example/problems/validation"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("senderId")))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("ownPosition.latitude")))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("aging")));
    }

    @Test
    void malformedJsonIsBadRequest() throws Exception {
        mvc.perform(put("/api/settings/instance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json")
                        .with(user("admin1", "ADMIN"))
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }
}
