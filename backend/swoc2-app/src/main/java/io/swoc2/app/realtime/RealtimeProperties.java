package io.swoc2.app.realtime;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Realtime tuning (env vars {@code SWOC2_RT_*}, see {@code .env.example}). Defaults follow
 * {@code docs/realtime-protocol.md}.
 *
 * @param heartbeat idle heartbeat interval on WS/SSE
 * @param pollHold maximum hold time of a long-poll request
 * @param sessionTtl how long a session survives with no transport attached
 * @param bufferSize replay buffer length per session (envelopes)
 * @param maxSessionsPerUser older sessions of the same user are closed beyond this
 * @param batchInterval delta batching interval (2 Hz default, ARCHITECTURE §6)
 * @param demoContacts number of synthetic contacts in the {@code demo} topic
 */
@ConfigurationProperties("swoc2.realtime")
record RealtimeProperties(
        @DefaultValue("10s") Duration heartbeat,
        @DefaultValue("25s") Duration pollHold,
        @DefaultValue("60s") Duration sessionTtl,
        @DefaultValue("1000") int bufferSize,
        @DefaultValue("10") int maxSessionsPerUser,
        @DefaultValue("500ms") Duration batchInterval,
        @DefaultValue("500") int demoContacts) {}
