import { PluginShell } from './plugins/PluginShell';

/**
 * Root component. The 2D map core, panels, auth and the rest of the HMI (P1) replace this
 * placeholder; for now it hosts the plugin shell that proves plugin isolation (ROADMAP P0 item
 * 10).
 */
export function App() {
  return (
    <main>
      <h1>SWOC2</h1>
      <p>SEDAP Web Operated C2 - P0 foundation.</p>
      <PluginShell />
    </main>
  );
}
