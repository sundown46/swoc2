import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

import './realtime.css';
import { RealtimeSpikePage } from './RealtimeSpikePage';

// Entry of the Spike B page (ROADMAP P0 item 8). Behind login like the rest of the SPA.
const container = document.getElementById('root');
if (!container) {
  throw new Error('Root element #root not found');
}

createRoot(container).render(
  <StrictMode>
    <RealtimeSpikePage />
  </StrictMode>,
);
