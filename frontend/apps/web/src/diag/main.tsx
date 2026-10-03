import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

import './diag.css';
import { DiagPage } from './DiagPage';

// Entry point of the standalone /diag page (GEN-010); deliberately independent of the main app.
const container = document.getElementById('root');
if (!container) {
  throw new Error('Root element #root not found');
}

createRoot(container).render(
  <StrictMode>
    <DiagPage />
  </StrictMode>,
);
