# Backend plugins

Backend plugins (AIS, ADS-B, CoT, ...) live here as Maven modules, one per plugin
(REQUIREMENTS PLG-006). Each depends only on `swoc2-plugin-api` - never on `swoc2-app` or on
another plugin. See ADR 0019 for the isolation rules.

To add one:
1. New module `backend/plugins/<name>-plugin`, artifact `swoc2-plugin-<name>`, depending on
   `swoc2-plugin-api` only; add it to `<modules>` in `backend/pom.xml`.
2. Implement `io.swoc2.pluginapi.Swoc2Plugin` and register it in
   `src/main/resources/META-INF/services/io.swoc2.pluginapi.Swoc2Plugin`.
3. Add it to `swoc2-app/pom.xml` with `<scope>runtime</scope>` (so core code cannot import it).

Plugins:
- `example-plugin` - P0 isolation proof (off by default; `SWOC2_PLUGINS_ENABLED=example`).
