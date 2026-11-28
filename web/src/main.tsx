// Manrope — інтерфейс і текст, Geologica — заголовки й цифри. Змінні шрифти лише з віссю ваги (wght.css):
// браузер вантажить тільки потрібні підмножини (кирилиця й латиниця) за unicode-range.
import '@fontsource-variable/manrope/wght.css';
import '@fontsource-variable/geologica/wght.css';
import './styles/index.css';
import { startTransition, StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { prefetchPublicMeta } from './api/meta';
import { dropAuthorizedCopies } from './api/public-api-cache';
import { queryClient } from './api/queryClient';
import { App } from './app/App';
import { applyAppStyles } from './app/app-styles';
import { whenRouterReady } from './app/router';

/**
 * Сайт оновили, поки вкладка була відкрита: частини коду з новими хешами є, старих уже немає, і перехід на
 * сторінку, яку ще не відкривали, падає на завантаженні частини. Vite кидає `vite:preloadError` — один раз
 * перезавантажуємо сторінку, щоб узяти свіжий index.html. Позначка в sessionStorage не дає зациклитись.
 */
const RELOADED_KEY = 'rozklad.reloadedAfterUpdate';
window.addEventListener('vite:preloadError', (event) => {
  if (sessionStorage.getItem(RELOADED_KEY)) return;
  sessionStorage.setItem(RELOADED_KEY, '1');
  event.preventDefault();
  window.location.reload();
});
window.addEventListener('load', () => {
  // сторінка завантажилась нормально — наступне оновлення сайту знову може перезавантажити її раз
  window.setTimeout(() => sessionStorage.removeItem(RELOADED_KEY), 10_000);
});

// попередні збірки PWA зберігали в кеші запити персоналу разом із токеном — прибираємо їх (копії гостя лишаються)
void dropAuthorizedCopies();

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

/**
 * Старт: мок-API (лише dev), запит meta паралельно з кодом сторінки (meta потрібна кожній сторінці; після
 * мок-API, щоб у dev запит не пішов повз нього), далі рендер, щойно роутер має код першої сторінки, а стилі
 * застосунку завантажені. Доти видно статичну оболонку з index.html, і React замінює її одразу готовою сторінкою.
 */
async function start(): Promise<void> {
  try {
    await startMocksIfEnabled();
  } catch (error: unknown) {
    // без мок-API сторінки пішли б до справжнього бекенду — кажемо про це прямо, а не мовчки
    console.error('Не вдалося запустити мок-API (MSW)', error);
    root.textContent = 'Не вдалося запустити мок-API (MSW). Подробиці — у консолі браузера.';
    return;
  }
  void prefetchPublicMeta(queryClient);
  await Promise.all([applyAppStyles(), whenRouterReady()]);
  // перший рендер — перехідом: React будує сторінку частинами по кілька мілісекунд і між ними віддає потік
  // браузеру, а не тримає його одним довгим завданням (на слабкому телефоні — сотні мілісекунд без відгуку).
  // Оболонка з index.html лишається на екрані, доки сторінка не готова повністю
  const app = createRoot(root);
  startTransition(() => {
    app.render(
      <StrictMode>
        <App />
      </StrictMode>,
    );
  });
}

void start();
