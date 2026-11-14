/** Рядок публічних змін як речення: що тепер, що було, і порядок рядків групи за парами. Без React — для тестів. */
import type { ChangeView } from '@/api/types';

/**
 * Слово, яке розпорядження друкує в колонці «було» для додаткової пари: це не заняття, а позначка, тож
 * «Замість: ДОДАТКОВО» не показуємо.
 */
const ADDITIONAL_PLACEHOLDER = 'ДОДАТКОВО';

/** Зміна аудиторії без знімка заняття: пара та сама, що в розкладі, змінилося лише «де». */
export const ROOM_CHANGE_TITLE = 'Пара за розкладом';

function joined(discipline?: string | null, teacher?: string | null): string {
  return [discipline, teacher].filter(Boolean).join(', ');
}

export interface ChangeRowText {
  /** Головний рядок: нове заняття; для скасування, блоку й зміни аудиторії — те, що було в розкладі. */
  title: string;
  /** Що було замість (закреслено); null — нічого не замінили. */
  old: string | null;
}

export function changeRowText(item: ChangeView): ChangeRowText {
  const placeholder = item.rowType === 'ADDITIONAL' || item.from.discipline?.trim().toUpperCase() === ADDITIONAL_PLACEHOLDER;
  const before = placeholder ? '' : joined(item.from.discipline, item.from.teacher);
  const after = joined(item.to.discipline, item.to.teacher);
  const cancelled = item.rowType === 'CANCELLATION';
  const roomChange = item.rowType === 'ROOM_CHANGE';
  const title = (cancelled || roomChange || !after ? before : after) || (roomChange ? ROOM_CHANGE_TITLE : '—');
  const old = !cancelled && !roomChange && after && before && before !== after ? before : null;
  return { title, old };
}

/** Перша пара рядка: «5» → 5, «1-4» → 1; без числа — у кінець. */
function firstNumber(label: string | null | undefined): number {
  const match = /\d+/.exec(label ?? '');
  return match ? Number(match[0]) : 999;
}

/**
 * Рядки групи за порядком пар (а в межах пари — уся група, потім підгрупи за номером). Розпорядження
 * друкує зміни аудиторій окремим розділом після замін, тож без сортування 1ВЕ читалася б «1, 2, 5, 4».
 * Сортування стабільне: рядки однієї пари лишаються в порядку документа.
 */
export function sortByPair(items: readonly ChangeView[]): ChangeView[] {
  return [...items].sort(
    (a, b) =>
      firstNumber(a.pairLabel) - firstNumber(b.pairLabel) ||
      (a.subgroupNote ? firstNumber(a.subgroupNote) : 0) - (b.subgroupNote ? firstNumber(b.subgroupNote) : 0),
  );
}
