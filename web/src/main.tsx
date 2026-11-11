// Manrope — інтерфейс і текст, Geologica — заголовки й цифри. Змінні шрифти лише з віссю ваги (wght.css):
// браузер вантажить тільки потрібні підмножини (кирилиця й латиниця) за unicode-range.
import '@fontsource-variable/manrope/wght.css';
import '@fontsource-variable/geologica/wght.css';
import './styles/index.css';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './app/App';

const container = document.getElementById('root');
if (!container) throw new Error('Не знайдено елемент #root');
const root = container;

/**
 * Мок-API (src/mocks) лише в dev з VITE_USE_MOCKS=1 (`npm run dev:mock`, e2e). У production-збірці умова
 * стає `false`, і динамічний імпорт MSW разом з мок-даними вирізається з бандла.
 */
async function startMocksIfEnabled(): Promise<void> {
  if (import.meta.env.DEV && import.meta.env.VITE_USE_MOCKS === '1') {
    const { startMockWorker } = await import('./mocks/browser');
    await startMockWorker();
  }
}

startMocksIfEnabled().then(
  () => {
    createRoot(root).render(
      <StrictMode>
        <App />
      </StrictMode>,
    );
  },
  (error: unknown) => {
    // без мок-API сторінки пішли б до справжнього бекенду — кажемо про це прямо, а не мовчки
    console.error('Не вдалося запустити мок-API (MSW)', error);
    root.textContent = 'Не вдалося запустити мок-API (MSW). Подробиці — у консолі браузера.';
  },
);
