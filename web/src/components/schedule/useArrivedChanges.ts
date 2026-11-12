import { useEffect, useState } from 'react';
import type { LessonView, ScheduleDto } from '@/api/types';

const NONE: ReadonlySet<string> = new Set();
/** Скільки тримати позначку «щойно прийшла»: довше за саму появу (200 + 200 мс). */
export const ARRIVAL_MS = 1000;

/** Що саме змінилося в занятті; звичайна пара й блок — не зміна. */
function changeSignature(lesson: LessonView): string | null {
  if (lesson.kind === 'REGULAR' || lesson.kind === 'BLOCK') return null;
  return [lesson.kind, lesson.changeId, lesson.discipline.id, lesson.teacher?.id, lesson.room?.id, lesson.original?.pair].join('|');
}

/** Місце заняття в тижні: день, пара, підгрупа. Ідентифікатор заняття для заміни може бути новим. */
function slotKey(date: string, lesson: LessonView): string {
  return `${date}|${lesson.pair}|${lesson.subgroup}`;
}

/** Чий і який тиждень: інший власник чи інший тиждень — це навігація, а не «прийшла зміна». */
function scopeOf(schedule: ScheduleDto): string {
  return `${schedule.owner.type}|${schedule.owner.id}|${schedule.from}|${schedule.to}`;
}

function signatures(schedule: ScheduleDto): Map<string, string> {
  const map = new Map<string, string>();
  for (const day of schedule.days) {
    for (const lesson of day.lessons) {
      const signature = changeSignature(lesson);
      if (signature) map.set(slotKey(day.date, lesson), signature);
    }
  }
  return map;
}

interface Seen {
  source: ScheduleDto | undefined;
  scope: string | null;
  signatures: Map<string, string> | null;
  arrived: ReadonlySet<string>;
}

function initial(schedule: ScheduleDto | undefined): Seen {
  return { source: schedule, scope: schedule ? scopeOf(schedule) : null, signatures: schedule ? signatures(schedule) : null, arrived: NONE };
}

/**
 * Зміни, що «прийшли наживо»: заняття, у яких зʼявилась або стала іншою зміна, поки людина дивилася на
 * той самий тиждень того самого розкладу (оновлення через SSE чи повторний запит). Перше завантаження,
 * інший тиждень чи інший власник нічого не позначають — інакше кожна навігація «спалахувала б» усіма
 * замінами. Передавайте справжні дані (не placeholder і не відфільтровані за підгрупою).
 * Повертає ідентифікатори занять; клітинка програє появу один раз: закреслення, потім тло й слово стану.
 */
export function useArrivedChanges(schedule: ScheduleDto | undefined): ReadonlySet<string> {
  const [seen, setSeen] = useState<Seen>(() => initial(schedule));

  // поява грає один раз: за секунду (уся поява — 400 мс) позначку знято, тож повторний монтаж клітинки
  // (інше подання того ж тижня) її вже не повторить
  useEffect(() => {
    if (seen.arrived.size === 0) return;
    const timer = setTimeout(() => setSeen((current) => ({ ...current, arrived: NONE })), ARRIVAL_MS);
    return () => clearTimeout(timer);
  }, [seen.arrived]);

  // немає даних (завантаження) — тримаємо останнє, що бачили
  if (!schedule || schedule === seen.source) return seen.arrived;

  const scope = scopeOf(schedule);
  if (scope !== seen.scope || !seen.signatures) {
    setSeen(initial(schedule));
    return NONE;
  }

  const next = signatures(schedule);
  const ids = new Set<string>();
  for (const day of schedule.days) {
    for (const lesson of day.lessons) {
      const key = slotKey(day.date, lesson);
      const signature = next.get(key);
      if (signature && seen.signatures.get(key) !== signature) ids.add(lesson.id);
    }
  }
  // нічого нового — лишаємо попередній набір, щоб клітинка, що саме «закреслюється», не втратила клас
  const arrived = ids.size > 0 ? ids : seen.arrived;
  setSeen({ source: schedule, scope, signatures: next, arrived });
  return arrived;
}
