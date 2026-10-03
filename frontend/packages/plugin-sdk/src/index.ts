import type { ComponentType } from 'react';

/**
 * Public frontend plugin SDK (PLG-001, ARCHITECTURE §8.1). Plugins import only from this package;
 * they never reach into core stores or components. Every contribution is rendered/called by the
 * core inside an isolation barrier (error boundary, try/catch), so a failing plugin shows a
 * "plugin failed" placeholder instead of breaking the app (PLG-003).
 *
 * Skeleton (ROADMAP P0 item 10): extension points `panels` and `toolbar`; the remaining ones
 * (routes, mapLayers, contextMenu, userSettings, adminPages, symbolProviders, mapTools,
 * notificationSources, coordinateFormats) are added with the P1/P2 features that need them,
 * without breaking existing plugins.
 */

/** SDK version this core implements. Plugins built for another version are not loaded. */
export const SDK_VERSION = '0.1';

export type Role = 'viewer' | 'operator' | 'commander' | 'admin';

export interface PluginManifest {
  /** Stable id, `[a-z][a-z0-9-]{1,40}`. */
  readonly id: string;
  readonly name: string;
  readonly version: string;
  /** The {@link SDK_VERSION} the plugin was built against. */
  readonly sdkVersion: string;
  /** If set, the plugin is only active for users with at least one of these roles. */
  readonly requiredRoles?: readonly Role[];
}

/** Core services offered to a plugin. Grows with P1 (picture, map, API client, settings, theme). */
export interface PluginContext {
  readonly pluginId: string;
  /** Logs attributed to the plugin (application log / debug console). */
  readonly log: {
    info(message: string, ...details: unknown[]): void;
    warn(message: string, ...details: unknown[]): void;
    error(message: string, ...details: unknown[]): void;
  };
  /** Shows a short in-app notification. */
  notify(message: string): void;
}

/** A dockable panel (P1: hosted by dockview). */
export interface PanelContribution {
  readonly id: string;
  readonly title: string;
  readonly component: ComponentType<{ readonly context: PluginContext }>;
}

/** A toolbar button. */
export interface ToolbarContribution {
  readonly id: string;
  readonly label: string;
  onClick(context: PluginContext): void | Promise<void>;
}

export interface PluginContributions {
  readonly panels?: readonly PanelContribution[];
  readonly toolbar?: readonly ToolbarContribution[];
}

export interface SwocPlugin {
  readonly manifest: PluginManifest;
  readonly contributes: PluginContributions;
  /** Called once when the plugin is enabled; runs inside a try/catch. */
  activate?(context: PluginContext): void;
}

/** Identity helper that gives plugin authors type checking. */
export function definePlugin(plugin: SwocPlugin): SwocPlugin {
  return plugin;
}
