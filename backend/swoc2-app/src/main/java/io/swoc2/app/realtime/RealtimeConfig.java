package io.swoc2.app.realtime;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import tools.jackson.databind.ObjectMapper;

/** Wires the realtime WebSocket endpoint and properties. Same-origin handshakes only (§1). */
@Configuration
@EnableWebSocket
@EnableConfigurationProperties(RealtimeProperties.class)
class RealtimeConfig implements WebSocketConfigurer {

    static final String WS_PATH = "/rt/ws";

    private final RealtimeSessionRegistry registry;
    private final RealtimeDispatcher dispatcher;
    private final ObjectMapper mapper;

    RealtimeConfig(RealtimeSessionRegistry registry, RealtimeDispatcher dispatcher, ObjectMapper mapper) {
        this.registry = registry;
        this.dispatcher = dispatcher;
        this.mapper = mapper;
    }

    @Bean
    RealtimeWebSocketHandler realtimeWebSocketHandler() {
        return new RealtimeWebSocketHandler(registry, dispatcher, mapper);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry handlers) {
        handlers.addHandler(realtimeWebSocketHandler(), WS_PATH);
    }
}
