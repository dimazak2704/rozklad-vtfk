/**
 * Довідник «Види періодів» (CONTRACTS §2 «Calendar», `/api/admin/period-kinds`). Правила й тексти помилок — як
 * у контракті core: код і псевдоніми не повторюються між видами, вбудований вид не видаляють і не міняють йому код,
 * зміну ефекту спершу перевіряють `?dryRun=true`.
 *
 * Мок не знає живих замін, тож `conflicts` пробного збереження завжди порожні; пари рахує за опублікованим
 * шаблоном від сьогодні.
 */
import { http, HttpResponse } from 'msw';
import type { GroupRef, IsoDate, PeriodEffect, PeriodKindDto, PeriodKindImpactDto, PeriodKindRequest, Uuid } from '@/api/types';
import { addDays, compareIsoDates, isoWeekday, todayInKyiv } from '@/lib/date';
import { plural } from '@/lib/labels';
import { lessonsOn, type PeriodKindFixture } from '../fixtures';
import { mockId } from '../ids';
import { apiPath, badRequest, conflict, problem, readJson } from '../kit';
import { periodKindDto, periodKinds, periods, refreshPeriodsOfKind, semesters } from './calendar';
import { groupRefOf, groups } from './catalog';

const EFFECTS: readonly PeriodEffect[] = ['DAY_OFF', 'SUSPEND', 'ONLINE', 'INFO'];
const CODE_RE = /^[\p{L}\p{N}]{1,10}$/u;
const COLOR_RE = /^#[0-9A-Fa-f]{6}$/;
const MAX_ALIASES = 10;
const UK = new Intl.Collator('uk');

/** Тексти 409 зміни ефекту (CONTRACTS): у пробному збереженні вони йдуть у `blockers`. */
export const ONLINE_BLOCKER = 'Ефект «онлайн» можна ввімкнути чи вимкнути лише для виду без періодів: спершу видаліть періоди цього виду.';
export const ROOM_BLOCKER = 'Періоди цього виду мають аудиторію або керівників: спершу приберіть їх.';

function sorted(list: readonly PeriodKindFixture[]): PeriodKindFixture[] {
  return [...list].sort((a, b) => a.position - b.position || UK.compare(a.code, b.code));
}

const lower = (value: string) => value.toLocaleLowerCase('uk');

interface Valid {
  code: string;
  name: string;
  effect: PeriodEffect;
  color: string | null;
  aliases: string[];
  noteTemplate: string | null;
  active: boolean;
  position: number;
}

/** 400 з полями, інакше нормалізовані значення. */
function validate(body: PeriodKindRequest, current: PeriodKindFixture | undefined): Valid {
  const errors: Array<{ field: string; message: string }> = [];
  const add = (field: string, message: string) => errors.push({ field, message });
  const code = typeof body.code === 'string' ? body.code.trim() : '';
  if (!CODE_RE.test(code)) add('code', 'Код — від 1 до 10 літер чи цифр, без пробілів.');
  else if (current?.builtin && code !== current.code) add('code', 'Код вбудованого виду не змінюється.');
  const name = typeof body.name === 'string' ? body.name.trim() : '';
  if (!name) add('name', 'Назва не може бути порожньою.');
  else if (name.length > 100) add('name', 'Не довше за 100 символів.');
  if (!EFFECTS.includes(body.effect)) add('effect', 'Оберіть ефект: вихідний, група не на парах, форма навчання або лише позначка.');
  const color = body.color ? body.color.trim() : null;
  if (color && !COLOR_RE.test(color)) add('color', 'Колір має вигляд #RRGGBB.');
  const raw = body.aliases ?? [];
  if (raw.length > MAX_ALIASES) add('aliases', `Не більше ${MAX_ALIASES} псевдонімів.`);
  raw.forEach((alias, i) => {
    if (typeof alias !== 'string' || !CODE_RE.test(alias.trim())) add(`aliases[${i}]`, 'Псевдонім — від 1 до 10 літер чи цифр, без пробілів.');
  });
  const noteTemplate = body.noteTemplate?.trim() || null;
  if (noteTemplate && noteTemplate.length > 500) add('noteTemplate', 'Не довше за 500 символів.');
  const last = Math.max(0, ...periodKinds.all().map((k) => k.position));
  const position = body.position ?? current?.position ?? last + 10;
  if (!Number.isInteger(position) || position < 0 || position > 9999) add('position', 'Порядок — ціле число від 0 до 9999.');
  if (errors.length > 0) throw badRequest('Перевірте виділені поля.', errors);
  // повтори та сам код серед псевдонімів нічого не додають
  const aliases = [...new Map(raw.map((a) => a.trim()).filter((a) => lower(a) !== lower(code)).map((a) => [lower(a), a])).values()];
  return { code, name, effect: body.effect, color: color ? color.toUpperCase() : null, aliases, noteTemplate, active: body.active ?? current?.active ?? true, position };
}

/** Код чи псевдонім уже має інший вид → 409 (без урахування регістру). */
function checkClash(values: Valid, id: Uuid | undefined): void {
  for (const token of [values.code, ...values.aliases]) {
    const owner = periodKinds
      .all()
      .find((k) => k.id !== id && [k.code, ...k.aliases].some((other) => lower(other) === lower(token)));
    if (owner) throw conflict(`Код «${token}» уже має вид «${owner.name}».`);
  }
}

function staleData(): Response {
  return problem({
    status: 409,
    slug: 'stale-data',
    title: 'Дані змінилися',
    detail: 'Цей запис уже змінив хтось інший. Оновіть сторінку й внесіть зміни ще раз.',
  });
}

const closes = (effect: PeriodEffect) => effect === 'DAY_OFF' || effect === 'SUSPEND';

/** Групи, яких стосується період. */
function groupsOf(period: ReturnType<typeof periods.all>[number]): GroupRef[] {
  if (period.scope === 'GROUPS') return period.groups;
  const all = groups.all().filter((g) => g.active);
  return (period.scope === 'COURSES' ? all.filter((g) => (period.courses ?? []).includes(g.course)) : all).map(groupRefOf);
}

/** Що зачепить зміна виду: дати, групи, пари (від сьогодні), причини відмови. */
function impactOf(kind: PeriodKindFixture, next: Valid): PeriodKindImpactDto {
  const own = periods.all().filter((p) => p.kindId === kind.id);
  const effectChanged = next.effect !== kind.effect;
  const blockers: string[] = [];
  if (effectChanged && own.length > 0) {
    if (kind.effect === 'ONLINE' || next.effect === 'ONLINE') blockers.push(ONLINE_BLOCKER);
    if (next.effect !== 'SUSPEND' && own.some((p) => p.roomId || (p.supervisorIds ?? []).length > 0)) blockers.push(ROOM_BLOCKER);
  }
  const dates = new Set<IsoDate>();
  const touched = new Map<Uuid, GroupRef>();
  const today = todayInKyiv();
  let lessons = 0;
  for (const period of own) {
    const semester = semesters.find(period.semesterId);
    const periodGroups = groupsOf(period);
    for (const g of periodGroups) touched.set(g.id, g);
    const ids = new Set(periodGroups.map((g) => g.id));
    for (let date = period.startsOn; compareIsoDates(date, period.endsOn) <= 0; date = addDays(date, 1)) {
      if (isoWeekday(date) > 5) continue;
      if (semester && (compareIsoDates(date, semester.startsOn) < 0 || compareIsoDates(date, semester.endsOn) > 0)) continue;
      dates.add(date);
      // пари перебудовуються лише від сьогодні; рахуємо шаблон без періодів
      if (effectChanged && compareIsoDates(date, today) >= 0) lessons += lessonsOn(date, { periods: [] }).filter((l) => ids.has(l.group.id)).length;
    }
  }
  const closing = closes(kind.effect) !== closes(next.effect);
  return {
    kind: { ...periodKindDto(kind), ...next },
    effectChanged,
    periods: own.length,
    dates: [...dates].sort(compareIsoDates),
    groups: [...touched.values()].sort((a, b) => UK.compare(a.code, b.code)),
    lessons: {
      added: closing && !closes(next.effect) ? lessons : 0,
      removed: closing && closes(next.effect) ? lessons : 0,
      updated: 0,
    },
    conflicts: [],
    blockers,
    canApply: blockers.length === 0,
  };
}

export const handlers = [
  http.get(apiPath('/admin/period-kinds'), ({ request }) => {
    const active = new URL(request.url).searchParams.get('active');
    const list = periodKinds.all().filter((k) => active === null || String(k.active) === active);
    return HttpResponse.json(sorted(list).map(periodKindDto));
  }),

  http.get(apiPath('/admin/period-kinds/:id'), ({ params }) => HttpResponse.json(periodKindDto(periodKinds.get(String(params.id))))),

  http.post(apiPath('/admin/period-kinds'), async ({ request }) => {
    const values = validate(await readJson<PeriodKindRequest>(request), undefined);
    checkClash(values, undefined);
    const created = periodKinds.insert({ id: mockId('period-kind', crypto.randomUUID()), ...values, builtin: false, version: 0 });
    return HttpResponse.json(periodKindDto(created), { status: 201, headers: { Location: apiPath(`/admin/period-kinds/${created.id}`) } });
  }),

  http.put(apiPath('/admin/period-kinds/:id'), async ({ request, params }) => {
    const kind = periodKinds.get(String(params.id));
    const body = await readJson<PeriodKindRequest>(request);
    const values = validate(body, kind);
    if (body.version !== undefined && body.version !== null && body.version !== kind.version) throw staleData();
    checkClash(values, kind.id);
    const impact = impactOf(kind, values);
    if (new URL(request.url).searchParams.get('dryRun') === 'true') return HttpResponse.json(impact);
    if (impact.blockers[0]) throw conflict(impact.blockers[0]);
    const saved = periodKinds.update(kind.id, (current) => ({ ...current, ...values, version: current.version + 1 }));
    refreshPeriodsOfKind(saved);
    return HttpResponse.json(periodKindDto(saved));
  }),

  http.delete(apiPath('/admin/period-kinds/:id'), ({ params }) => {
    const kind = periodKinds.get(String(params.id));
    if (kind.builtin) throw conflict('Вбудований вид не видаляють: його можна вимкнути.');
    const usage = periodKindDto(kind).usage;
    if (usage > 0) {
      throw conflict(
        `Вид «${kind.code}» мають ${usage} ${plural(usage, ['період', 'періоди', 'періодів'])} календаря: спершу видаліть їх або змініть їм вид.`,
      );
    }
    periodKinds.remove(kind.id);
    return new HttpResponse(null, { status: 204 });
  }),
];

/** Для тестів: вид за кодом зі сховища. */
export function storedKind(code: string): PeriodKindDto {
  const kind = periodKinds.all().find((k) => k.code === code);
  if (!kind) throw new Error(`Немає виду «${code}»`);
  return periodKindDto(kind);
}
