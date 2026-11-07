// Fira Sans: стандартні українські форми кирилиці, табличні цифри; Condensed — для цифр пар, табличок, сітки
import '@fontsource/fira-sans/400.css';
import '@fontsource/fira-sans/500.css';
import '@fontsource/fira-sans/600.css';
import '@fontsource/fira-sans/700.css';
import '@fontsource/fira-sans-condensed/600.css';
import '@fontsource/fira-sans-condensed/700.css';
import '@fontsource/fira-sans-condensed/800.css';
import './styles/index.css';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './app/App';

const container = document.getElementById('root');
if (!container) throw new Error('Не знайдено елемент #root');

createRoot(container).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
