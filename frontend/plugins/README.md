# Frontend plugins

Build-time frontend plugins (video dashboard, tasking, chat panel, AIS, ADS-B, CoT, 3D, Matrix
chat, tactical graphics, ...) live here as pnpm workspace packages, one directory per plugin
(REQUIREMENTS PLG-004/PLG-006). Each depends only on `@swoc2/plugin-sdk`. See ADR 0019.

To add one: create `frontend/plugins/<name>` with a `package.json` named `@swoc2/plugin-<name>`
that depends on `@swoc2/plugin-sdk`, export `definePlugin({...})` as default, add it as a
dependency of `apps/web` and list it in `apps/web/src/plugins/installedPlugins.ts`.

Plugins:
- `example` - P0 isolation proof: a panel that can crash itself, a throwing toolbar handler.
