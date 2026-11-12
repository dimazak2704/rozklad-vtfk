/**
 * Запасна оболонка для переходів між сторінками (PWA). Сторінки йдуть у мережу першими (NetworkFirst) і
 * не лежать у precache, бо index.html посилається на частини коду з хешем у назві: після оновлення сайту
 * старі частини з сервера зникають, і збережений старий index.html дав би порожню сторінку з 404.
 * Коли мережі немає, а саме цієї сторінки в кеші ще немає, плагін віддає будь-яку збережену сторінку:
 * оболонка SPA однакова для всіх маршрутів, а маршрут розбере React Router.
 */

/** Кеш сторінок, той самий, що в `runtimeCaching` (vite.config.ts). */
export const PAGES_CACHE = 'rozklad-pages';

interface HandlerDidErrorParam {
  request?: Request;
}

/**
 * Плагін Workbox для `runtimeCaching[].options.plugins`. workbox-build переносить його у sw.js як текст
 * (toString), тож функція самодостатня: лише глобальний `caches`, назва кешу записана прямо в ній.
 */
export const offlineShellPlugin = {
  handlerDidError: async (_param: HandlerDidErrorParam): Promise<Response | undefined> => {
    const cache = await caches.open('rozklad-pages');
    const [anyPage] = await cache.keys();
    return anyPage ? cache.match(anyPage) : undefined;
  },
};
