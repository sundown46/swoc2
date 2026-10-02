package io.swoc2.app.diag;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Wires the {@code /diag} page and its WebSocket probe. The page itself is a separate entry of
 * the SPA build ({@code diag.html}, see {@code frontend/apps/web/vite.config.ts}) so it loads
 * without the main app, without login and without any app state - it must work on exactly the
 * locked-down machines where the main app might not.
 */
@Configuration
@EnableWebSocket
class DiagWebConfig implements WebMvcConfigurer, WebSocketConfigurer {

    /** Path of the WebSocket echo probe; also opened up in {@code SecurityConfig}. */
    static final String WS_PROBE_PATH = "/api/diag/ws";

    @Bean
    DiagEchoWebSocketHandler diagEchoWebSocketHandler() {
        return new DiagEchoWebSocketHandler();
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // No trailing slash: the page's relative asset URLs ("./assets/...") must resolve next to
        // index.html, which is what both "/diag" and "{base}/diag" behind a proxy give.
        registry.addViewController("/diag").setViewName("forward:/diag.html");
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Same-origin only by default: the browser sends the page's Origin, and a
        // cross-site page has no business probing this instance.
        registry.addHandler(diagEchoWebSocketHandler(), WS_PROBE_PATH);
    }
}
