# Backend plugins

Backend plugins (AIS, ADS-B, CoT, ...) live here as Maven modules, one per plugin
(REQUIREMENTS PLG-006). Each depends only on `swoc2-plugin-api` - never on `swoc2-app` or on
another plugin.

Empty for now: the plugin SPI skeleton and the first trivial example plugin are ROADMAP P0 item
10, not part of the repo-skeleton change that added this placeholder.
