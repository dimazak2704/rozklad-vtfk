/**
 * Які переходи між сторінками Service Worker (PWA) зберігає в кеш сторінок `rozklad-pages`
 * (`runtimeCaching` у vite.config.ts). Ключ кешу — повна адреса сторінки.
 */

interface RouteMatchParam {
  request: Request;
  url: URL;
  sameOrigin: boolean;
}

/**
 * `urlPattern` для кешу сторінок. Завжди йдуть у мережу й не зберігаються: /api (ICS, файли розпоряджень),
 * сервіс входу /auth, повернення після входу /login/callback, /login/silent і сторінки з одноразовим
 * токеном у шляху /invite/<токен>, /password/<токен>. Збережена адреса з токеном лишалася б у Cache Storage
 * до 21 дня навіть після очищення історії, і на спільному комп'ютері хтось інший задав би за нею пароль.
 * Без урахування регістру й зайвих `/`, як маршрути React Router і маска журналу в nginx.conf.
 *
 * workbox-build переносить функцію у sw.js як текст (toString), тож вона самодостатня: жодних посилань
 * на змінні модуля. Заголовок Cache-Control тут не допоміг би: кеш Workbox його не читає.
 */
export const isCachedPageNavigation = ({ request, url, sameOrigin }: RouteMatchParam): boolean =>
  sameOrigin &&
  request.mode === 'navigate' &&
  !/^\/(api|auth)\//.test(url.pathname) &&
  !/^\/login\/(callback|silent)/.test(url.pathname) &&
  !/^\/+(invite|password)\//i.test(url.pathname);
