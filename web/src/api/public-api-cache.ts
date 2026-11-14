/**
 * Кеш публічних відповідей API у Service Worker (PWA) `rozklad-public-api` (`runtimeCaching` у vite.config.ts):
 * що туди потрапляє і як прибрати те, чого там бути не повинно.
 *
 * Розклад і заміни персонал просить з токеном (архів). Cache Storage зберігає запит разом із заголовками, тож
 * у кеші лишилися б і токен (після виходу його видно в DevTools на спільному комп'ютері), і архів. Прибрати
 * такий запис workbox-expiration не може: видаляє за адресою, а з `Vary: Authorization` адреса без токена
 * його не знаходить. Тому запит з токеном Service Worker не бере зовсім — він іде в мережу, як без PWA.
 */

/** Кеш публічних відповідей API, той самий, що в `runtimeCaching` (vite.config.ts). */
export const PUBLIC_API_CACHE = 'rozklad-public-api';

interface RouteMatchParam {
  request: Request;
  url: URL;
  sameOrigin: boolean;
}

/**
 * `urlPattern` для кешу публічних відповідей: meta, довідники, дзвінки, розклад і заміни — лише запити без
 * заголовка `Authorization`. Запит з токеном маршрут не бере, тож Service Worker його не обробляє й не зберігає.
 *
 * workbox-build переносить функцію у sw.js як текст (toString), тож вона самодостатня: жодних посилань
 * на змінні модуля.
 */
export const isCachedPublicApiRequest = ({ request, url, sameOrigin }: RouteMatchParam): boolean =>
  sameOrigin &&
  /^\/api\/public\/(meta|groups|teachers|rooms|bells|schedule|changes)(\/|$)/.test(url.pathname) &&
  !request.headers.has('Authorization');

/**
 * Прибирає з кешу відповіді на запити з токеном: їх зберігали попередні збірки. Копії гостя не чіпає, тож офлайн
 * працює, як і раніше. Викликається на кожному старті застосунку (дешево; після оновлення сайту старий воркер ще
 * може встигнути зберегти запис, поки новий стає на його місце), при виході й коли сервер не прийняв токен.
 * Без Cache Storage (сайт по HTTP у локальній мережі, тести) нічого не робить; збій сховища теж не заважає.
 */
export async function dropAuthorizedCopies(): Promise<void> {
  if (typeof caches === 'undefined') return;
  try {
    if (!(await caches.has(PUBLIC_API_CACHE))) return;
    const cache = await caches.open(PUBLIC_API_CACHE);
    const requests = await cache.keys();
    // ключ — сам збережений запит: з `Vary: Authorization` збігається лише він, копія гостя за тією ж адресою лишається
    await Promise.all(requests.filter((request) => request.headers.has('Authorization')).map((request) => cache.delete(request)));
  } catch {
    // сховище недоступне (приватний режим, квота) — прибирати нічого
  }
}
