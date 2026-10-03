import {
  SDK_VERSION,
  type PluginContext,
  type SwocPlugin,
  type ToolbarContribution,
} from '@swoc2/plugin-sdk';

/**
 * Frontend plugin host (ARCHITECTURE §8.1, PLG-003): validates plugins, activates them inside a
 * try/catch, tracks which are enabled (they can be switched off at runtime), and runs toolbar
 * handlers behind an exception barrier. Rendering isolation is {@link PluginBoundary}'s job.
 *
 * A plain store with subscribe/getSnapshot so React reads it via useSyncExternalStore and the
 * logic stays testable without components (CLAUDE.md "no business logic in components").
 */

export interface HostedPlugin {
  readonly plugin: SwocPlugin;
  readonly context: PluginContext;
  readonly enabled: boolean;
  /** Why the plugin is off, if it was switched off automatically or refused. */
  readonly problem: string | null;
}

export interface PluginHostSnapshot {
  readonly plugins: readonly HostedPlugin[];
  readonly notifications: readonly string[];
}

const ID = /^[a-z][a-z0-9-]{1,40}$/;

export class PluginHost {
  private snapshot: PluginHostSnapshot = { plugins: [], notifications: [] };
  private readonly listeners = new Set<() => void>();

  constructor(plugins: readonly SwocPlugin[]) {
    const seen = new Set<string>();
    const hosted: HostedPlugin[] = [];
    for (const plugin of plugins) {
      const problem = this.validate(plugin, seen);
      if (problem) {
        console.error(`Plugin not loaded: ${problem}`);
        continue;
      }
      seen.add(plugin.manifest.id);
      hosted.push({
        plugin,
        context: this.contextFor(plugin.manifest.id),
        enabled: false,
        problem: null,
      });
    }
    this.snapshot = { ...this.snapshot, plugins: hosted };
    for (const h of hosted) this.enable(h.plugin.manifest.id);
  }

  private validate(plugin: SwocPlugin, seen: Set<string>): string | null {
    const m = (plugin as Partial<SwocPlugin>).manifest;
    if (!m || typeof m.id !== 'string' || !ID.test(m.id)) return 'invalid or missing manifest id';
    if (seen.has(m.id)) return `duplicate plugin id ${m.id}`;
    if (m.sdkVersion !== SDK_VERSION) {
      return `${m.id} was built for SDK ${m.sdkVersion}, this core is ${SDK_VERSION}`;
    }
    return null;
  }

  private contextFor(pluginId: string): PluginContext {
    const prefix = `[plugin ${pluginId}]`;
    return {
      pluginId,
      log: {
        info: (message, ...details) => {
          console.info(prefix, message, ...details);
        },
        warn: (message, ...details) => {
          console.warn(prefix, message, ...details);
        },
        error: (message, ...details) => {
          console.error(prefix, message, ...details);
        },
      },
      notify: (message) => {
        this.notify(`${pluginId}: ${message}`);
      },
    };
  }

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  getSnapshot = (): PluginHostSnapshot => this.snapshot;

  enable(id: string): void {
    const hosted = this.find(id);
    if (!hosted || hosted.enabled) return;
    try {
      hosted.plugin.activate?.(hosted.context);
      this.patch(id, { enabled: true, problem: null });
    } catch (error) {
      console.error(`Plugin ${id} failed to activate`, error);
      this.patch(id, { enabled: false, problem: `activation failed: ${errorText(error)}` });
    }
  }

  disable(id: string, problem: string | null = null): void {
    if (this.find(id)) this.patch(id, { enabled: false, problem });
  }

  /** Runs a toolbar handler behind an exception barrier (sync throws and rejected promises). */
  runToolbar(id: string, item: ToolbarContribution): void {
    const hosted = this.find(id);
    if (!hosted?.enabled) return;
    const report = (error: unknown) => {
      console.error(`Plugin ${id} toolbar action ${item.id} failed`, error);
      this.notify(`Plugin "${hosted.plugin.manifest.name}" failed: ${errorText(error)}`);
    };
    try {
      const result = item.onClick(hosted.context);
      if (result instanceof Promise) result.catch(report);
    } catch (error) {
      report(error);
    }
  }

  notify(message: string): void {
    this.snapshot = {
      ...this.snapshot,
      notifications: [message, ...this.snapshot.notifications].slice(0, 5),
    };
    this.emit();
  }

  private find(id: string): HostedPlugin | undefined {
    return this.snapshot.plugins.find((p) => p.plugin.manifest.id === id);
  }

  private patch(id: string, change: Partial<HostedPlugin>): void {
    this.snapshot = {
      ...this.snapshot,
      plugins: this.snapshot.plugins.map((p) =>
        p.plugin.manifest.id === id ? { ...p, ...change } : p,
      ),
    };
    this.emit();
  }

  private emit(): void {
    for (const listener of this.listeners) listener();
  }
}

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
