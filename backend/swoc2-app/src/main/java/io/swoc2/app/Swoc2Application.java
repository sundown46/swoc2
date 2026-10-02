package io.swoc2.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

/**
 * Application entry point. Serving the SPA, the runtime {@code config.json} and the realtime
 * endpoints from this one process (ARCHITECTURE §2) is added in ROADMAP P0 item 4; this
 * skeleton only boots an empty Spring context so the multi-module build and CI pipeline
 * (GEN-006, NFR-004) are in place before feature work starts.
 */
@SpringBootApplication
@Modulithic(systemName = "SWOC2")
public class Swoc2Application {

    public static void main(String[] args) {
        SpringApplication.run(Swoc2Application.class, args);
    }
}
