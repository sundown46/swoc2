package io.swoc2.app.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OAuth2LoginRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** HTTP transports (session, long-poll, upstream send) incl. auth, CSRF and session ownership. */
@SpringBootTest(properties = {"swoc2.realtime.poll-hold=700ms", "swoc2.realtime.demo-contacts=5"})
class RealtimeHttpTests {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final OAuth2LoginRequestPostProcessor alice = oauth2Login().attributes(a -> a.put("sub", "alice"));
    private final OAuth2LoginRequestPostProcessor bob = oauth2Login().attributes(a -> a.put("sub", "bob"));

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    private String createSession() throws Exception {
        String body = mvc.perform(post("/rt/session").with(alice).with(csrf()))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return mapper.readTree(body).path("sessionId").asString();
    }

    private JsonNode poll(String session, long after) throws Exception {
        MvcResult started = mvc.perform(get("/rt/poll")
                        .param("session", session)
                        .param("after", String.valueOf(after))
                        .with(alice))
                .andReturn();
        String body = mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return mapper.readTree(body);
    }

    @Test
    void longPollDeliversHelloThenSnapshotAfterSubscribe() throws Exception {
        String session = createSession();

        JsonNode first = poll(session, 0);
        assertThat(first.get(0).path("type").asString()).isEqualTo("hello");
        assertThat(first.get(0).path("seq").asLong()).isEqualTo(1);

        mvc.perform(post("/rt/send")
                        .param("session", session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"v\":1,\"type\":\"subscribe\",\"payload\":{\"topic\":\"demo\"}}")
                        .with(alice)
                        .with(csrf()))
                .andExpect(status().isAccepted());

        JsonNode second = poll(session, 1);
        assertThat(second.get(0).path("type").asString()).isEqualTo("snapshot");
        assertThat(second.get(0).path("seq").asLong()).isEqualTo(2);
        assertThat(second.get(0).path("payload").path("items")).hasSize(5);
    }

    @Test
    void foreignSessionLooksUnknown() throws Exception {
        String session = createSession();

        mvc.perform(get("/rt/poll").param("session", session).with(bob)).andExpect(status().isNotFound());
        mvc.perform(get("/rt/poll").param("session", "nope").with(alice)).andExpect(status().isNotFound());
    }

    @Test
    void sendWithoutCsrfIsRejected() throws Exception {
        String session = createSession();

        mvc.perform(post("/rt/send")
                        .param("session", session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"v\":1,\"type\":\"ping\"}")
                        .with(alice))
                .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticatedGets401() throws Exception {
        mvc.perform(get("/rt/poll").param("session", "x")).andExpect(status().is(401));
    }

    @Test
    void malformedUpstreamIsDroppedNotFatal() throws Exception {
        String session = createSession();

        mvc.perform(post("/rt/send")
                        .param("session", session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"v\":9},{\"nonsense\":true},{\"v\":1,\"type\":\"ping\"}]")
                        .with(alice)
                        .with(csrf()))
                .andExpect(status().isAccepted());
        mvc.perform(post("/rt/send")
                        .param("session", session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json")
                        .with(alice)
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void negativeAfterIsBadRequest() throws Exception {
        String session = createSession();

        mvc.perform(get("/rt/poll")
                        .param("session", session)
                        .param("after", "-1")
                        .with(alice))
                .andExpect(status().isBadRequest());
    }
}
