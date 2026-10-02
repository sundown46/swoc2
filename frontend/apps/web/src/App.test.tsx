import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { App } from './App';

describe('App', () => {
  it('renders the SWOC2 heading', () => {
    render(<App />);
    expect(screen.getByRole('heading', { name: 'SWOC2' })).toBeInTheDocument();
  });
});
