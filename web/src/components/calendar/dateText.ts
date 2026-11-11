/**
 * Дата текстом для полів форм: показуємо «11.11.2026» (як у розпорядженнях), зберігаємо ISO.
 * Нативне `type="date"` в англійській локалі браузера показує «11/11/2026» (мм/дд), тому поля дат —
 * текстові з власним календарем (`DateInput`).
 */
import type { IsoDate } from '@/api/types';
import { isIsoDate, toIsoDate } from '@/lib/date';

/** «2026-11-11» → «11.11.2026»; порожнє чи не-ISO — як є. */
export function isoToText(value: string): string {
  if (!isIsoDate(value)) return value;
  const [year, month, day] = value.split('-');
  return `${day}.${month}.${year}`;
}

/**
 * Набране → ISO: «11.11.2026», «11.11.26», «11/11/2026», «1.9.2026», «2026-11-11».
 * Порядок завжди день, місяць, рік. Не дата (31.02, літери) — null.
 */
export function textToIso(raw: string): IsoDate | null {
  const text = raw.trim();
  if (isIsoDate(text)) return text;
  const match = /^(\d{1,2})[./\s-](\d{1,2})[./\s-](\d{2}|\d{4})$/.exec(text);
  if (!match) return null;
  const year = match[3]?.length === 2 ? 2000 + Number(match[3]) : Number(match[3]);
  const iso = toIsoDate(year, Number(match[2]), Number(match[1]));
  return isIsoDate(iso) ? iso : null;
}
