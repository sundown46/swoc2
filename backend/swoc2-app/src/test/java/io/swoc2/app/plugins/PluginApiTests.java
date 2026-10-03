package io.swoc2.app.plugins;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OAuth2LoginRequestPostProcessor;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The real example plugin (ROADMAP P0 item 10 acceptance "a crashing example plugin is
 * contained"), discovered via ServiceLoader from the runtime classpath and driven over REST.
 */
@SpringBootTest(properties = {"swoc2.plugins.enabled=example", "swoc2.plugins.call-timeout=300ms"})
@DirtiesContext
class PluginApiTests {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;
    private final OAuth2LoginRequestPostProcessor viewer =
            oauth2Login().authorities(new SimpleGrantedAuthority("ROLE_VIEWER"));
    private final OAuth2LoginRequestPostProcessor admin =
            oauth2Login().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        // Fresh state per test: disable + enable resets the failure counter (the context is shared).
        mvc.perform(post("/api/plugins/example/disable").with(admin).with(csrf()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/plugins/example/enable").with(admin).with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    void healthListsTheExamplePlugin() throws Exception {
        mvc.perform(get("/api/plugins").with(viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='example')].state").value("ENABLED"));
    }

    @Test
    void normalEndpointWorks() throws Exception {
        mvc.perform(get("/api/plugins/example/endpoints/hello").with(viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Hello from the example plugin"));
    }

    @Test
    void crashingPluginIsContainedAndDisabledAfterThreeFailures() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/plugins/example/endpoints/crash").with(viewer))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.type").value("https://swoc2.example/problems/plugin-failed"))
                    .andExpect(jsonPath("$.detail")
                            .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Exception"))));
        }
        mvc.perform(get("/api/plugins/example/endpoints/hello").with(viewer))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/plugins").with(viewer))
                .andExpect(jsonPath("$[?(@.id=='example')].state").value("FAILED"));

        // The rest of the application is unaffected.
        mvc.perform(get("/config.json")).andExpect(status().isOk());

        // An admin can bring it back.
        mvc.perform(post("/api/plugins/example/enable").with(admin).with(csrf()))
                .andExpect(jsonPath("$.state").value("ENABLED"));
        mvc.perform(get("/api/plugins/example/endpoints/hello").with(viewer)).andExpect(status().isOk());
    }

    @Test
    void hangingEndpointTimesOut() throws Exception {
        mvc.perform(get("/api/plugins/example/endpoints/hang").with(viewer)).andExpect(status().isGatewayTimeout());
    }

    @Test
    void onlyAdminsCanSwitchPlugins() throws Exception {
        mvc.perform(post("/api/plugins/example/disable").with(viewer).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/plugins/example/disable").with(admin).with(csrf()))
                .andExpect(jsonPath("$.state").value("DISABLED"));
        mvc.perform(get("/api/plugins/example/endpoints/hello").with(viewer))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void unknownPluginOrEndpointIs404() throws Exception {
        mvc.perform(get("/api/plugins/nope/endpoints/hello").with(viewer)).andExpect(status().isNotFound());
        mvc.perform(get("/api/plugins/example/endpoints/nope").with(viewer)).andExpect(status().isNotFound());
        mvc.perform(get("/api/plugins/example/endpoints/..%2Fsecret").with(viewer))
                .andExpect(status().is4xxClientError());
    }
}
