import { Component, type ErrorInfo, type ReactNode } from 'react';

interface Props {
  readonly pluginName: string;
  readonly contributionId: string;
  readonly onDisable: () => void;
  readonly children: ReactNode;
}

interface State {
  readonly error: Error | null;
}

/**
 * Error boundary around every plugin contribution (ARCHITECTURE §8.1, PLG-003): a crash while
 * rendering shows a "plugin failed" placeholder with Reload and Disable, and nothing outside this
 * box is affected. The error is logged, never shown as a stack trace.
 */
export class PluginBoundary extends Component<Props, State> {
  override state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  override componentDidCatch(error: Error, info: ErrorInfo): void {
    console.error(
      `Plugin "${this.props.pluginName}" (${this.props.contributionId}) crashed`,
      error,
      info.componentStack,
    );
  }

  override render(): ReactNode {
    if (!this.state.error) return this.props.children;
    return (
      <div role="alert" className="plugin-failed">
        <p>
          Plugin “{this.props.pluginName}” failed: {this.state.error.message}
        </p>
        <button
          type="button"
          onClick={() => {
            this.setState({ error: null });
          }}
        >
          Reload
        </button>{' '}
        <button type="button" onClick={this.props.onDisable}>
          Disable plugin
        </button>
      </div>
    );
  }
}
