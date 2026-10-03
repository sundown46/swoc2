package io.swoc2.app.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** API-001: the OpenAPI description is generated and covers the REST endpoints. */
@SpringBootTest
class OpenApiTests {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    void openApiDescriptionListsTheEndpoints() throws Exception {
        mvc.perform(get("/api/openapi.json").with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_VIEWER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("SWOC2 API"))
                .andExpect(jsonPath("$.paths['/api/settings/instance']").exists())
                .andExpect(jsonPath("$.paths['/api/audit']").exists())
                .andExpect(jsonPath("$.paths['/api/plugins']").exists())
                .andExpect(jsonPath("$.paths['/rt/session']").exists());
    }

    @Test
    void swaggerUiIsForAdminsOnly() throws Exception {
        mvc.perform(get("/api/docs").with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_VIEWER"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/docs").with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void openApiNeedsLogin() throws Exception {
        mvc.perform(get("/api/openapi.json")).andExpect(status().isUnauthorized());
    }
}
