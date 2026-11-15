/**
 * Збережені цілі (остання, нещодавні, обране) переживають перевантаження бази: id групи, викладача чи аудиторії
 * змінюються, а код групи, імʼя й номер аудиторії лишаються. Довідник вирішує: id є — ціль жива; немає, але є
 * той самий підпис того самого типу — переносимо на новий id (людина й далі бачить «свою» 4КІ1); немає й підпису —
 * забуваємо, щоб головна не відкривала розклад, якого вже не існує («Групу з ідентифікатором … не знайдено»).
 */
import type { EntityType } from '@/api/types';
import type { ScheduleTarget } from '@/stores/favorites';

/** Що є в довіднику зараз: id, підпис (як у ScheduleTarget) і адреса сторінки. */
export interface CatalogEntry {
  id: string;
  label: string;
  url: string;
}

/** Завантажені довідники за типом; тип без довідника (ще вантажиться, помилка, порожньо) не чіпаємо. */
export type Catalogs = Partial<Record<EntityType, readonly CatalogEntry[]>>;

export interface SavedTargets {
  lastViewed: ScheduleTarget | null;
  recent: ScheduleTarget[];
  favorites: ScheduleTarget[];
}

/** Ціль у сьогоднішньому довіднику: та сама, перенесена на новий id або null (зникла). Без довідника — як є. */
export function resolveTarget(target: ScheduleTarget, catalogs: Catalogs): ScheduleTarget | null {
  const catalog = catalogs[target.type];
  if (!catalog || catalog.length === 0) return target;
  if (catalog.some((entry) => entry.id === target.id)) return target;
  const renamed = catalog.find((entry) => entry.label === target.label);
  return renamed ? { ...target, id: renamed.id, label: renamed.label, url: renamed.url } : null;
}

function resolveList(targets: readonly ScheduleTarget[], catalogs: Catalogs): ScheduleTarget[] {
  const seen = new Set<string>();
  const out: ScheduleTarget[] = [];
  for (const target of targets) {
    const resolved = resolveTarget(target, catalogs);
    if (!resolved) continue;
    const key = `${resolved.type}:${resolved.id}`;
    // стара й нова копія тієї самої групи зливаються в одну
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(resolved);
  }
  return out;
}

const same = (a: ScheduleTarget | null, b: ScheduleTarget | null) =>
  a === b || (a !== null && b !== null && a.type === b.type && a.id === b.id && a.label === b.label && a.url === b.url);
const sameList = (a: readonly ScheduleTarget[], b: readonly ScheduleTarget[]) => a.length === b.length && a.every((t, i) => same(t, b[i] ?? null));

/** Виправлені збережені цілі або null, якщо виправляти нічого (стор не перезаписуємо зайвий раз). */
export function repairSavedTargets(state: SavedTargets, catalogs: Catalogs): SavedTargets | null {
  const lastViewed = state.lastViewed ? resolveTarget(state.lastViewed, catalogs) : null;
  const recent = resolveList(state.recent, catalogs);
  const favorites = resolveList(state.favorites, catalogs);
  if (same(lastViewed, state.lastViewed) && sameList(recent, state.recent) && sameList(favorites, state.favorites)) return null;
  return { lastViewed, recent, favorites };
}
