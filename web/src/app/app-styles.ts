/**
 * Стилі застосунку (≈160 КБ CSS) не блокують перше малювання. Збірка (vite.config.ts) робить посилання на них
 * у index.html невидимим для рендеру (`media="print"`, плюс `preload`, щоб файл однаково вантажився одразу
 * й першочергово), а статична оболонка в index.html має власні вбудовані стилі. Тож браузер малює шапку,
 * щойно отримав HTML, а не після CSS і виконання коду. Перед рендером застосунку `applyAppStyles` вмикає
 * стилі й чекає, доки вони завантажаться: сторінка без стилів не показується ніколи.
 */

/** Позначка посилання на стилі застосунку в index.html. */
export const APP_STYLES_ATTR = 'data-app-styles';

/**
 * Помилку завантаження, що сталася до нашої перевірки, браузер не повторить подією: після цієї паузи
 * рендеримо однаково, щоб людина не лишилася з оболонкою назавжди.
 */
export const APP_STYLES_TIMEOUT_MS = 10_000;

const ENTRY_STYLESHEET = /<link rel="stylesheet" crossorigin href="(\/assets\/index-[\w-]+\.css)">/;

/**
 * Збірка: посилання на головний CSS у index.html → `preload` (вантажити одразу) і стилі з `media="print"`
 * (не блокують малювання). Немає такого посилання — помилка збірки, а не тиха зміна поведінки.
 */
export function deferAppStyles(html: string): string {
  const match = ENTRY_STYLESHEET.exec(html);
  if (!match) throw new Error('index.html: не знайдено посилання на стилі застосунку (/assets/index-*.css)');
  const href = match[1];
  return html.replace(
    match[0],
    `<link rel="preload" as="style" crossorigin href="${href}">\n    ` +
      `<link rel="stylesheet" crossorigin href="${href}" media="print" ${APP_STYLES_ATTR}>`,
  );
}

/**
 * Вмикає стилі застосунку і чекає, доки вони завантажаться (або не завантажаться — тоді однаково рендеримо:
 * краще сторінка без оформлення, ніж вічна оболонка). У dev посилання немає — стилі вставляє Vite.
 */
export function applyAppStyles(doc: Document = document): Promise<void> {
  const link = doc.querySelector<HTMLLinkElement>(`link[${APP_STYLES_ATTR}]`);
  if (!link) return Promise.resolve();
  const ready = link.sheet
    ? Promise.resolve()
    : new Promise<void>((resolve) => {
        const done = () => {
          window.clearTimeout(timer);
          resolve();
        };
        const timer = window.setTimeout(done, APP_STYLES_TIMEOUT_MS);
        link.addEventListener('load', done, { once: true });
        link.addEventListener('error', done, { once: true });
      });
  link.media = 'all';
  return ready;
}
