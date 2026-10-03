import { definePlugin, SDK_VERSION, type PluginContext } from '@swoc2/plugin-sdk';
import { useState } from 'react';

/**
 * Example plugin (ROADMAP P0 item 10): a panel that can crash itself while rendering, and a
 * toolbar button whose handler throws. The core must contain both (PLG-003). Depends only on
 * `@swoc2/plugin-sdk`.
 */
function ExamplePanel({ context }: { readonly context: PluginContext }) {
  const [clicks, setClicks] = useState(0);
  const [crash, setCrash] = useState(false);
  if (crash) {
    throw new Error('Deliberate render crash in the example plugin');
  }
  return (
    <div>
      <p>Hello from the example plugin ({context.pluginId}).</p>
      <p>
        <button
          type="button"
          onClick={() => {
            setClicks((c) => c + 1);
          }}
        >
          Clicked {clicks}×
        </button>{' '}
        <button
          type="button"
          onClick={() => {
            setCrash(true);
          }}
        >
          Crash this panel
        </button>
      </p>
    </div>
  );
}

export default definePlugin({
  manifest: { id: 'example', name: 'Example plugin', version: '0.1.0', sdkVersion: SDK_VERSION },
  contributes: {
    panels: [{ id: 'example.panel', title: 'Example', component: ExamplePanel }],
    toolbar: [
      {
        id: 'example.hello',
        label: 'Say hello',
        onClick: (context) => {
          context.notify('Hello from the example plugin');
        },
      },
      {
        id: 'example.throw',
        label: 'Throw in handler',
        onClick: () => {
          throw new Error('Deliberate handler crash in the example plugin');
        },
      },
    ],
  },
  activate: (context) => {
    context.log.info('Example plugin activated');
  },
});
