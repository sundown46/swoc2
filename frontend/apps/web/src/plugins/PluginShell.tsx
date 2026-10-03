import { useMemo, useSyncExternalStore } from 'react';

import { installedPlugins } from './installedPlugins';
import { PluginBoundary } from './PluginBoundary';
import { PluginHost } from './pluginHost';

/**
 * Minimal host UI for P0 (ROADMAP item 10): toolbar items and panels of enabled plugins, each
 * isolated, plus the list of installed plugins with an on/off switch. Replaced by the dockview
 * layout and the admin plugin page in P1/P2.
 */
export function PluginShell({ host: providedHost }: { readonly host?: PluginHost }) {
  const host = useMemo(() => providedHost ?? new PluginHost(installedPlugins), [providedHost]);
  const { plugins, notifications } = useSyncExternalStore(host.subscribe, host.getSnapshot);
  const enabled = plugins.filter((p) => p.enabled);

  return (
    <section className="plugin-shell" aria-label="Plugins">
      <div role="toolbar" aria-label="Plugin toolbar" className="plugin-toolbar">
        {enabled.flatMap((p) =>
          (p.plugin.contributes.toolbar ?? []).map((item) => (
            <button
              key={item.id}
              type="button"
              onClick={() => {
                host.runToolbar(p.plugin.manifest.id, item);
              }}
            >
              {item.label}
            </button>
          )),
        )}
      </div>
      {notifications.length > 0 && (
        <ul aria-label="Notifications" className="plugin-notifications">
          {notifications.map((n, i) => (
            <li key={`${String(i)}-${n}`}>{n}</li>
          ))}
        </ul>
      )}
      <div className="plugin-panels">
        {enabled.flatMap((p) =>
          (p.plugin.contributes.panels ?? []).map((panel) => {
            const Panel = panel.component;
            return (
              <article key={panel.id} className="plugin-panel" aria-label={panel.title}>
                <h3>{panel.title}</h3>
                <PluginBoundary
                  pluginName={p.plugin.manifest.name}
                  contributionId={panel.id}
                  onDisable={() => {
                    host.disable(p.plugin.manifest.id, 'disabled after a crash');
                  }}
                >
                  <Panel context={p.context} />
                </PluginBoundary>
              </article>
            );
          }),
        )}
      </div>
      <h3>Installed plugins</h3>
      <ul className="plugin-list">
        {plugins.map((p) => (
          <li key={p.plugin.manifest.id}>
            <label>
              <input
                type="checkbox"
                checked={p.enabled}
                onChange={(e) => {
                  if (e.target.checked) host.enable(p.plugin.manifest.id);
                  else host.disable(p.plugin.manifest.id);
                }}
              />{' '}
              {p.plugin.manifest.name} {p.plugin.manifest.version}
            </label>
            {p.problem && <span className="plugin-problem"> - {p.problem}</span>}
          </li>
        ))}
      </ul>
    </section>
  );
}
