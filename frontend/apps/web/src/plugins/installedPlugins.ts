import examplePlugin from '@swoc2/plugin-example';
import type { SwocPlugin } from '@swoc2/plugin-sdk';

/**
 * Build-time plugin registration (PLG-004, P1): every plugin in `frontend/plugins/*` that should
 * ship is listed here. Runtime loading of ESM bundles follows in P4.
 */
export const installedPlugins: readonly SwocPlugin[] = [examplePlugin];
