import { definePlugin, SDK_VERSION, type SwocPlugin } from '@swoc2/plugin-sdk';
import { fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import examplePlugin from '@swoc2/plugin-example';

import { PluginHost } from './pluginHost';
import { PluginShell } from './PluginShell';

function Healthy() {
  return <p>healthy panel content</p>;
}

const healthyPlugin = definePlugin({
  manifest: { id: 'healthy', name: 'Healthy', version: '1', sdkVersion: SDK_VERSION },
  contributes: { panels: [{ id: 'healthy.panel', title: 'Healthy', component: Healthy }] },
});

beforeEach(() => {
  vi.spyOn(console, 'error').mockImplementation(() => undefined);
  vi.spyOn(console, 'info').mockImplementation(() => undefined);
});
afterEach(() => {
  vi.restoreAllMocks();
});

describe('plugin isolation (PLG-003)', () => {
  it('contains a render crash in one panel; the rest keeps working', () => {
    render(<PluginShell host={new PluginHost([examplePlugin, healthyPlugin])} />);

    fireEvent.click(screen.getByRole('button', { name: 'Crash this panel' }));

    expect(screen.getByRole('alert')).toHaveTextContent('Plugin “Example plugin” failed');
    expect(screen.getByText('healthy panel content')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Say hello' })).toBeInTheDocument();
  });

  it('reload re-mounts the crashed panel', () => {
    render(<PluginShell host={new PluginHost([examplePlugin])} />);
    fireEvent.click(screen.getByRole('button', { name: 'Crash this panel' }));

    fireEvent.click(screen.getByRole('button', { name: 'Reload' }));

    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByText(/Hello from the example plugin/)).toBeInTheDocument();
  });

  it('a crashed plugin can be disabled; its contributions disappear', () => {
    render(<PluginShell host={new PluginHost([examplePlugin, healthyPlugin])} />);
    fireEvent.click(screen.getByRole('button', { name: 'Crash this panel' }));

    fireEvent.click(screen.getByRole('button', { name: 'Disable plugin' }));

    expect(screen.queryByRole('button', { name: 'Say hello' })).not.toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByText(/disabled after a crash/)).toBeInTheDocument();
    expect(screen.getByText('healthy panel content')).toBeInTheDocument();
  });

  it('contains a throwing toolbar handler and tells the user', () => {
    render(<PluginShell host={new PluginHost([examplePlugin])} />);

    fireEvent.click(screen.getByRole('button', { name: 'Throw in handler' }));

    expect(screen.getByLabelText('Notifications')).toHaveTextContent(
      'failed: Deliberate handler crash',
    );
    fireEvent.click(screen.getByRole('button', { name: 'Say hello' }));
    expect(screen.getByLabelText('Notifications')).toHaveTextContent(
      'example: Hello from the example plugin',
    );
  });

  it('refuses plugins with the wrong SDK version, bad ids or duplicates', () => {
    const wrongSdk = {
      ...healthyPlugin,
      manifest: { ...healthyPlugin.manifest, id: 'old', sdkVersion: '0.0' },
    };
    const badId = { ...healthyPlugin, manifest: { ...healthyPlugin.manifest, id: 'Bad Id' } };
    const host = new PluginHost([healthyPlugin, wrongSdk, badId, healthyPlugin, {} as SwocPlugin]);

    expect(host.getSnapshot().plugins.map((p) => p.plugin.manifest.id)).toEqual(['healthy']);
  });

  it('a plugin whose activate() throws stays off with a reason', () => {
    const broken = definePlugin({
      manifest: { id: 'broken', name: 'Broken', version: '1', sdkVersion: SDK_VERSION },
      contributes: {},
      activate: () => {
        throw new Error('no thanks');
      },
    });
    const host = new PluginHost([broken]);

    expect(host.getSnapshot().plugins[0]).toMatchObject({
      enabled: false,
      problem: 'activation failed: no thanks',
    });
  });
});
