/** Рядок публічних змін як речення: що тепер, що було, і порядок рядків групи за парами. Без React — для тестів. */
import type { ChangeView } from '@/api/types';
import { pairOrdinal, pairOrdinalGenitive } from '@/lib/ordinal';

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
  /** Стара аудиторія зміни аудиторії (закреслено перед новою); null — невідома чи та сама. */
  oldRoom: string | null;
}

export function changeRowText(item: ChangeView): ChangeRowText {
  const placeholder = item.rowType === 'ADDITIONAL' || item.from.discipline?.trim().toUpperCase() === ADDITIONAL_PLACEHOLDER;
  const before = placeholder ? '' : joined(item.from.discipline, item.from.teacher);
  const after = joined(item.to.discipline, item.to.teacher);
  const cancelled = item.rowType === 'CANCELLATION';
  const roomChange = item.rowType === 'ROOM_CHANGE';
  const title = (cancelled || roomChange || !after ? before : after) || (roomChange ? ROOM_CHANGE_TITLE : '—');
  const old = !cancelled && !roomChange && after && before && before !== after ? before : null;
  const previous = roomChange ? item.previousRoom?.trim() : null;
  const oldRoom = previous && previous !== item.room?.trim() ? previous : null;
  return { title, old, oldRoom };
}

/** Викладач без позначки підгрупи «(п.)»: у скасуванні й заміні однієї підгрупи вона може стояти не скрізь. */
function bareTeacher(teacher: string | null | undefined): string {
  return (teacher ?? '').replace(/\s*\(п\.\)\s*$/, '').trim();
}

/** «1-ша» → «1-шу», «3-тя» → «3-тю»: «перенесено на 3-тю пару». */
function pairOrdinalAccusative(n: number): string {
  return pairOrdinal(n).replace(/а$/, 'у').replace(/я$/, 'ю');
}

/**
 * Перенесена пара: розпорядження друкує її двома рядками — скасування там, звідки забрали, і заміну там,
 * куди поставили, з тим самим заняттям. Сторінка групи називає це «Перенесено · з 2-ї пари», тож тут
 * обидва рядки отримують ту саму підказку: «перенесено з 2-ї пари» і «перенесено на 1-шу пару».
 * Лише однозначні пари рядків (по одному скасуванню й заміні того самого заняття в межах групи).
 */
export function movedPairHints(items: readonly ChangeView[]): Map<ChangeView, string> {
  const hints = new Map<ChangeView, string>();
  const key = (discipline: string | null | undefined, teacher: string | null | undefined) =>
    discipline?.trim() && bareTeacher(teacher) ? `${discipline.trim()}|${bareTeacher(teacher)}|` : null;
  const single = (label: string) => (/^\d+$/.test(label.trim()) ? Number(label) : null);
  const cancellations = new Map<string, ChangeView[]>();
  const replacements = new Map<string, ChangeView[]>();
  for (const item of items) {
    const sub = item.subgroupNote ?? '';
    if (item.rowType === 'CANCELLATION') {
      const k = key(item.from.discipline, item.from.teacher);
      if (k) cancellations.set(k + sub, [...(cancellations.get(k + sub) ?? []), item]);
    } else if (item.rowType === 'REPLACEMENT') {
      const k = key(item.to.discipline, item.to.teacher);
      if (k) replacements.set(k + sub, [...(replacements.get(k + sub) ?? []), item]);
    }
  }
  for (const [k, cancelled] of cancellations) {
    const placed = replacements.get(k);
    if (cancelled.length !== 1 || placed?.length !== 1) continue;
    const [from, to] = [cancelled[0] as ChangeView, placed[0] as ChangeView];
    const [fromPair, toPair] = [single(from.pairLabel), single(to.pairLabel)];
    if (fromPair === null || toPair === null || fromPair === toPair) continue;
    hints.set(to, `перенесено з ${pairOrdinalGenitive(fromPair)} пари`);
    hints.set(from, `перенесено на ${pairOrdinalAccusative(toPair)} пару`);
  }
  return hints;
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
