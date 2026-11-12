/**
 * Позначка Service Worker (PWA) на відповіді з його кешу. NetworkFirst віддає збережену копію, коли мережа
 * не відповіла за `networkTimeoutSeconds` або впала, і для сторінки це звичайна 200. Плагін додає до такої
 * копії заголовок, і клієнт API (saved-copy.ts) чесно каже людині, що показано збережене.
 *
 * Значення — HTTP-дата з `Date` відповіді, тобто коли сервер її віддав і копію збережено, або `unknown`.
 */
export const SAVED_COPY_HEADER = 'X-Rozklad-Saved-Copy';

interface CachedResponseParam {
  cachedResponse?: Response | null;
}

/**
 * Плагін Workbox для `runtimeCaching[].options.plugins`. workbox-build переносить його у sw.js як текст
 * (toString), тож функція самодостатня: жодних посилань на змінні модуля, лише глобальні Headers і Response.
 */
export const savedCopyPlugin = {
  cachedResponseWillBeUsed: ({ cachedResponse }: CachedResponseParam): Promise<Response | null> => {
    if (!cachedResponse) return Promise.resolve(null);
    const headers = new Headers(cachedResponse.headers);
    headers.set('X-Rozklad-Saved-Copy', cachedResponse.headers.get('Date') || 'unknown');
    return Promise.resolve(
      new Response(cachedResponse.body, { status: cachedResponse.status, statusText: cachedResponse.statusText, headers }),
    );
  },
};
