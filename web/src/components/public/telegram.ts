/** Бот коледжу (UI.md §5). Адресу можна змінити змінною VITE_TELEGRAM_BOT_URL. */
export const TELEGRAM_BOT_URL = (import.meta.env.VITE_TELEGRAM_BOT_URL || 'https://t.me/scheduler_vtfk_bot').replace(/\/$/, '');

/** «@scheduler_vtfk_bot» — для підписів. */
export const TELEGRAM_BOT_HANDLE = `@${TELEGRAM_BOT_URL.split('/').pop() ?? 'scheduler_vtfk_bot'}`;
