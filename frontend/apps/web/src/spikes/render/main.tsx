import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

import 'ol/ol.css';
import './spike.css';
import { RenderSpikePage } from './RenderSpikePage';

// Entry of the Spike A benchmark page (ROADMAP P0 item 7). Behind login like the rest of the SPA.
const container = document.getElementById('root');
if (!container) {
  throw new Error('Root element #root not found');
}

createRoot(container).render(
  <StrictMode>
    <RenderSpikePage />
  </StrictMode>,
);
