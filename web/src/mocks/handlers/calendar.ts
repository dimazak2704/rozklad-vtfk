/**
 * Календар диспетчера (CONTRACTS §2 «Calendar»): семестри, активація, тижні з перевизначенням парності,
 * профілі дзвінків, календарні періоди, вікна змін. Правила й тексти помилок — як у core
 * (calendar/internal/SemesterService, BellProfileService, CalendarPeriodService).
 */
import { http, HttpResponse } from 'msw';
import type {
  BellProfileRequest,
  CalendarBellProfile,
  CalendarPeriod,
  CalendarPeriodRequest,
  CalendarSemester,
  CalendarWeek,
  SemesterRequest,
} from '@/api/calendar';
import type { BellProfileSlotDto, CalendarPeriodKind, FieldErrorDto, IsoDate, PeriodKindDto, PeriodScope, StudyMode, Uuid, WeekParity } from '@/api/types';
import { addDays, compareIsoDates, diffDays, formatNumericDate, isIsoDate, isoWeekday, startOfWeek, timeToMinutes } from '@/lib/date';
import { WEEKDAY_ACCUSATIVE } from '@/lib/labels';
import {
  ACADEMIC_YEAR,
  BELL_PROFILES,
  CALENDAR_PERIODS,
  kindRef,
  LEGACY_KIND_CODE,
  legacyKindOf,
  PERIOD_KINDS,
  SEMESTERS,
  SHIFT_WINDOWS,
  type PeriodKindFixture,
} from '../fixtures';
import { mockId } from '../ids';
import { apiPath, badRequest, conflict, problem, readJson } from '../kit';
import { createMockCollection, createMockStore } from '../store';
import { groupRefOf, groups, roomRefOf, rooms, teachers } from './catalog';

const MODES: readonly StudyMode[] = ['OFFLINE', 'ONLINE', 'MIXED'];
const SCOPES: readonly PeriodScope[] = ['ALL', 'COURSES', 'GROUPS'];
const OTHER: Record<WeekParity, WeekParity> = { NUMERATOR: 'DENOMINATOR', DENOMINATOR: 'NUMERATOR' };

// ───────────────────────── Стан ─────────────────────────

const weeksSpan = (startsOn: IsoDate, endsOn: IsoDate) => Math.floor(diffDays(startOfWeek(startsOn), startOfWeek(endsOn)) / 7) + 1;

export const bellProfiles = createMockCollection<CalendarBellProfile>('Профіль дзвінків', () =>
  BELL_PROFILES.map((p) => ({ ...p, slots: [...p.slots], defaultProfile: p.active, version: 0 })),
);

export const semesters = createMockCollection<CalendarSemester>('Семестр', () =>
  SEMESTERS.map((s) => {
    const profile = BELL_PROFILES.find((p) => p.id === s.bellProfileId);
    return {
      ...s,
      academicYear: ACADEMIC_YEAR.name,
      teachingWeeks: s.weeksCount ?? weeksSpan(s.startsOn, s.endsOn),
      bellProfile: profile ? { id: profile.id, label: profile.name } : null,
      version: 0,
    };
  }),
);

/** Ручні перевизначення парності: `semesterId|weekStart` → парність. У демо їх немає. */
const overrides = createMockStore<Map<string, WeekParity>>(() => new Map());

export const periods = createMockCollection<CalendarPeriod>('Період', () =>
  CALENDAR_PERIODS.map((p) => ({
    ...p,
    groups: (p.groupIds ?? []).map((id) => groups.find(id)).filter((g) => g !== undefined).map(groupRefOf),
    supervisorIds: p.supervisorIds ?? [],
    supervisors: p.supervisors ?? [],
    version: 0,
  })),
);

/** Довідник «Види періодів» (вбудовані з графіка коледжу); `usage` рахується з періодів у `periodKindDto`. */
export const periodKinds = createMockCollection<PeriodKindFixture>('Вид періоду', () =>
  PERIOD_KINDS.map((kind) => ({ ...kind, aliases: [...kind.aliases] })),
);

/** Вид у відповіді API: з кількістю періодів календаря цього виду. */
export function periodKindDto(kind: PeriodKindFixture): PeriodKindDto {
  return { ...kind, usage: periods.all().filter((p) => p.kindId === kind.id).length };
}

/** Після зміни назви, кольору чи ефекту виду періоди показують його новим (core читає вид тим самим запитом). */
export function refreshPeriodsOfKind(kind: PeriodKindFixture): void {
  for (const period of periods.all().filter((p) => p.kindId === kind.id)) {
    periods.update(period.id, (current) => ({ ...current, kind: legacyKindOf(kind), periodKind: kindRef(kind) }));
  }
}

/** Вид для запиту періоду: `kindId`, інакше вбудований за старим `kind`; помилка — у поле `kindId`. */
function resolveKind(body: CalendarPeriodRequest, current: CalendarPeriod | undefined, add: (field: string, message: string) => void) {
  let kind: PeriodKindFixture | undefined;
  if (body.kindId) {
    kind = periodKinds.find(body.kindId);
    if (!kind) add('kindId', 'Вид періоду не знайдено.');
  } else if (body.kind && body.kind in LEGACY_KIND_CODE) {
    kind = periodKinds.all().find((k) => k.code === LEGACY_KIND_CODE[body.kind as CalendarPeriodKind]);
  } else {
    add('kindId', 'Вкажіть вид періоду.');
  }
  // вимкнений вид лишається в наявних періодах, але не обирається для нового періоду чи нового виду
  if (kind && !kind.active && current?.kindId !== kind.id) {
    add('kindId', `Вид «${kind.name}» вимкнено: увімкніть його в «Видах періодів» або виберіть інший.`);
  }
  return kind;
}

// ───────────────────────── Парність ─────────────────────────

/** Тижні семестру: тиждень 1 містить дату початку; непарні — `firstWeekParity`, перевизначення важливіше. */
export function weeksOf(semester: CalendarSemester): CalendarWeek[] {
  const first = startOfWeek(semester.startsOn);
  const count = weeksSpan(semester.startsOn, semester.endsOn);
  return Array.from({ length: count }, (_, index) => {
    const weekStart = addDays(first, index * 7);
    const computedParity = index % 2 === 0 ? semester.firstWeekParity : OTHER[semester.firstWeekParity];
    const override = overrides.get().get(`${semester.id}|${weekStart}`);
    return {
      weekStart,
      weekEnd: addDays(weekStart, 4),
      weekNumber: index + 1,
      parity: override ?? computedParity,
      computedParity,
      overridden: override !== undefined,
    };
  });
}

/** Парність дати з урахуванням перевизначень (для інших областей мок-API, напр. обліку годин). */
export function parityOfDate(semester: CalendarSemester, date: IsoDate): WeekParity {
  const week = weeksOf(semester).find((w) => w.weekStart === startOfWeek(date));
  return week?.parity ?? semester.firstWeekParity;
}

export function activeSemester(): CalendarSemester {
  const active = semesters.all().find((s) => s.active) ?? semesters.all()[0];
  if (!active) throw problem({ status: 422, slug: 'business-rule', detail: 'Немає жодного семестру.' });
  return active;
}

// ───────────────────────── Перевірки ─────────────────────────

const ROMAN = ['', 'I', 'II'];

function fieldErrors(): { list: FieldErrorDto[]; add: (field: string, message: string) => void; throwIfAny: () => void } {
  const list: FieldErrorDto[] = [];
  return {
    list,
    add: (field, message) => list.push({ field, message }),
    throwIfAny: () => {
      if (list.length > 0) throw badRequest('Перевірте виділені поля.', list);
    },
  };
}

function staleData(): Response {
  return problem({
    status: 409,
    slug: 'stale-data',
    title: 'Дані змінилися',
    detail: 'Цей запис уже змінив хтось інший. Оновіть сторінку й внесіть зміни ще раз.',
  });
}

function checkVersion(current: { version: number }, expected: number | null | undefined): void {
  if (expected !== undefined && expected !== null && expected !== current.version) throw staleData();
}

function saveSemester(body: SemesterRequest, id?: Uuid): CalendarSemester {
  const errors = fieldErrors();
  if (body.number !== 1 && body.number !== 2) errors.add('number', 'Номер семестру — 1 або 2.');
  if (!isIsoDate(body.startsOn)) errors.add('startsOn', 'Вкажіть дату початку.');
  if (!isIsoDate(body.endsOn)) errors.add('endsOn', 'Вкажіть дату кінця.');
  else if (isIsoDate(body.startsOn) && compareIsoDates(body.endsOn, body.startsOn) <= 0) {
    errors.add('endsOn', 'Кінець семестру має бути пізніше за початок.');
  }
  const parity = body.firstWeekParity ?? 'NUMERATOR';
  if (parity !== 'NUMERATOR' && parity !== 'DENOMINATOR') errors.add('firstWeekParity', 'Парність першого тижня — чисельник або знаменник.');
  const profile = body.bellProfileId ? bellProfiles.find(body.bellProfileId) : null;
  if (body.bellProfileId && !profile) errors.add('bellProfileId', 'Профіль дзвінків не знайдено.');
  const startYear = isIsoDate(body.startsOn) ? Number(body.startsOn.slice(0, 4)) : 0;
  // осінній семестр починає навчальний рік, весняний — його закінчує
  const derivedYear = body.number === 2 ? `${startYear - 1}/${startYear}` : `${startYear}/${startYear + 1}`;
  const academicYear = body.academicYear?.trim() || derivedYear;
  const yearMatch = /^(\d{4})\/(\d{4})$/.exec(academicYear);
  if (!yearMatch || Number(yearMatch[2]) !== Number(yearMatch[1]) + 1) {
    errors.add('academicYear', 'Навчальний рік має вигляд 2026/2027 (роки поспіль).');
  }
  if (body.weeksCount != null && (!Number.isInteger(body.weeksCount) || body.weeksCount < 1 || body.weeksCount > 53)) {
    errors.add('weeksCount', 'Кількість тижнів — ціле число від 1 до 53.');
  }
  errors.throwIfAny();
  const name = body.name?.trim() || `${ROMAN[body.number] ?? body.number} семестр ${academicYear}`;
  const twin = semesters.all().find((s) => s.id !== id && s.number === body.number && s.academicYear === academicYear);
  if (twin) throw conflict(`Семестр ${ROMAN[body.number] ?? body.number} ${academicYear} уже існує.`);
  const overlap = semesters
    .all()
    .find((s) => s.id !== id && compareIsoDates(body.startsOn, s.endsOn) <= 0 && compareIsoDates(s.startsOn, body.endsOn) <= 0);
  if (overlap) {
    throw conflict(
      `Дати семестру перетинаються з «${overlap.name}» (${formatNumericDate(overlap.startsOn)}–${formatNumericDate(overlap.endsOn)}).`,
    );
  }
  const fields = {
    number: body.number,
    name,
    academicYear,
    startsOn: body.startsOn,
    endsOn: body.endsOn,
    firstWeekParity: parity,
    weeksCount: body.weeksCount ?? null,
    teachingWeeks: body.weeksCount ?? weeksSpan(body.startsOn, body.endsOn),
    bellProfileId: profile?.id ?? null,
    bellProfile: profile ? { id: profile.id, label: profile.name } : null,
  };
  if (id) {
    return semesters.update(id, (current) => {
      checkVersion(current, body.version);
      const outside = periods
        .all()
        .filter((p) => p.semesterId === id && (compareIsoDates(p.startsOn, body.startsOn) < 0 || compareIsoDates(p.endsOn, body.endsOn) > 0));
      if (outside.length > 0) {
        throw conflict(`Нові дати не охоплюють періоди календаря: ${outside.map((p) => `«${p.title}»`).join(', ')}. Спершу змініть їх.`);
      }
      return { ...current, ...fields, version: current.version + 1 };
    });
  }
  return semesters.insert({
    id: mockId('semester', crypto.randomUUID()),
    academicYearId: mockId('academic-year', academicYear),
    active: false,
    ...fields,
    version: 0,
  });
}

function validateSlots(slots: unknown): BellProfileSlotDto[] {
  const errors = fieldErrors();
  if (!Array.isArray(slots) || slots.length < 1 || slots.length > 40) {
    errors.add('slots', 'Додайте від 1 до 40 пар');
    errors.throwIfAny();
  }
  const list = slots as BellProfileSlotDto[];
  const seen = new Set<string>();
  list.forEach((slot, i) => {
    if (!Number.isInteger(slot.weekday) || slot.weekday < 1 || slot.weekday > 5) errors.add(`slots[${i}].weekday`, 'День — від 1 (пн) до 5 (пт).');
    if (!Number.isInteger(slot.pair) || slot.pair < 1 || slot.pair > 8) errors.add(`slots[${i}].pair`, 'Пара — від 1 до 8.');
    const valid = /^\d{2}:\d{2}$/.test(slot.startsAt ?? '') && /^\d{2}:\d{2}$/.test(slot.endsAt ?? '');
    if (!valid) errors.add(`slots[${i}].startsAt`, 'Час має вигляд ГГ:ХХ.');
    else if (timeToMinutes(slot.endsAt) <= timeToMinutes(slot.startsAt)) {
      errors.add(`slots[${i}].endsAt`, 'Пара має закінчуватися пізніше, ніж починається.');
    }
    const key = `${slot.weekday}:${slot.pair}`;
    if (seen.has(key)) errors.add(`slots[${i}].pair`, `Пара ${slot.pair} у ${WEEKDAY_ACCUSATIVE[slot.weekday] ?? ''} повторюється.`);
    seen.add(key);
  });
  for (let weekday = 1; weekday <= 5; weekday += 1) {
    const day = list
      .map((slot, index) => ({ slot, index }))
      .filter(({ slot }) => slot.weekday === weekday && /^\d{2}:\d{2}$/.test(slot.startsAt ?? '') && /^\d{2}:\d{2}$/.test(slot.endsAt ?? ''))
      .sort((a, b) => a.slot.pair - b.slot.pair);
    for (let k = 1; k < day.length; k += 1) {
      const previous = day[k - 1];
      const current = day[k];
      if (previous && current && timeToMinutes(current.slot.startsAt) < timeToMinutes(previous.slot.endsAt)) {
        errors.add(
          `slots[${current.index}].startsAt`,
          `Пара ${current.slot.pair} у ${WEEKDAY_ACCUSATIVE[weekday] ?? ''} починається раніше, ніж закінчується пара ${previous.slot.pair}.`,
        );
      }
    }
  }
  errors.throwIfAny();
  return list
    .map(({ weekday, pair, startsAt, endsAt }) => ({ weekday, pair, startsAt, endsAt }))
    .sort((a, b) => a.weekday - b.weekday || a.pair - b.pair);
}

function saveBellProfile(body: BellProfileRequest, id?: Uuid): CalendarBellProfile {
  const errors = fieldErrors();
  const name = body.name?.trim() ?? '';
  if (!name) errors.add('name', 'Назва не може бути порожньою.');
  else if (name.length > 100) errors.add('name', 'Не довше за 100 символів.');
  errors.throwIfAny();
  const slots = validateSlots(body.slots);
  const twin = bellProfiles.all().find((p) => p.id !== id && p.name.toLocaleLowerCase('uk') === name.toLocaleLowerCase('uk'));
  if (twin) throw conflict(`Профіль дзвінків «${twin.name}» уже існує.`);
  const active = body.active ?? true;
  if (id) {
    return bellProfiles.update(id, (current) => {
      checkVersion(current, body.version);
      if (!active && current.defaultProfile) {
        throw conflict('Типовий профіль дзвінків не можна вимкнути: спершу виберіть інший у налаштуваннях.');
      }
      return { ...current, name, active, slots, version: current.version + 1 };
    });
  }
  return bellProfiles.insert({ id: mockId('bell-profile', crypto.randomUUID()), name, active, slots, defaultProfile: false, version: 0 });
}

function savePeriod(body: CalendarPeriodRequest, id?: Uuid): CalendarPeriod {
  const errors = fieldErrors();
  const current = id ? periods.get(id) : undefined;
  const semester = body.semesterId ? semesters.find(body.semesterId) : undefined;
  if (!semester) errors.add('semesterId', 'Семестр не знайдено.');
  const kind = resolveKind(body, current, errors.add);
  const effect = kind?.effect;
  const title = body.title?.trim() ?? '';
  if (!title) errors.add('title', 'Назва не може бути порожньою.');
  else if (title.length > 300) errors.add('title', 'Не довше за 300 символів.');
  if (!isIsoDate(body.startsOn)) errors.add('startsOn', 'Вкажіть дату початку.');
  if (!isIsoDate(body.endsOn)) errors.add('endsOn', 'Вкажіть дату кінця.');
  if (isIsoDate(body.startsOn) && isIsoDate(body.endsOn)) {
    if (compareIsoDates(body.endsOn, body.startsOn) < 0) errors.add('endsOn', 'Кінець періоду не може бути раніше за початок.');
    else if (semester && (compareIsoDates(body.startsOn, semester.startsOn) < 0 || compareIsoDates(body.endsOn, semester.endsOn) > 0)) {
      errors.add('startsOn', `Період має бути в межах семестру (${formatNumericDate(semester.startsOn)}–${formatNumericDate(semester.endsOn)}).`);
    }
  }
  const studyMode = body.studyMode ?? null;
  if (effect === 'ONLINE' && (!studyMode || !MODES.includes(studyMode))) {
    errors.add('studyMode', 'Для виду з ефектом «онлайн» вкажіть режим: офлайн, онлайн або змішаний.');
  }
  if (effect && effect !== 'ONLINE' && studyMode) errors.add('studyMode', 'Режим навчання задають лише для виду з ефектом «онлайн».');
  const scope = body.scope ?? 'ALL';
  if (!SCOPES.includes(scope)) errors.add('scope', 'Оберіть, кого стосується період.');
  const courses = body.courses ?? [];
  const groupIds = body.groupIds ?? [];
  if (scope === 'ALL' && (courses.length > 0 || groupIds.length > 0)) errors.add('scope', 'Для періоду «для всіх» не вказують курси чи групи.');
  if (scope === 'COURSES') {
    if (courses.length === 0) errors.add('courses', 'Вкажіть хоча б один курс.');
    if (courses.some((c) => !Number.isInteger(c) || c < 1 || c > 6)) errors.add('courses', 'Курс — від 1 до 6.');
    if (groupIds.length > 0) errors.add('groupIds', 'Для періоду за курсами групи не вказують.');
  }
  if (scope === 'GROUPS') {
    if (groupIds.length === 0) errors.add('groupIds', 'Вкажіть хоча б одну групу.');
    if (courses.length > 0) errors.add('courses', 'Для періоду за групами курси не вказують.');
    groupIds.forEach((groupId, i) => {
      if (!groups.find(groupId)) errors.add(`groupIds[${i}]`, 'Групу не знайдено.');
    });
  }
  const orderNote = body.orderNote?.trim() || null;
  if (orderNote && orderNote.length > 500) errors.add('orderNote', 'Не довше за 500 символів.');
  // аудиторія періоду (у кроці 1 лише показується): лише для виду «група не на парах»
  const room = body.roomId ? rooms.find(body.roomId) : null;
  if (body.roomId && effect && effect !== 'SUSPEND') errors.add('roomId', 'Аудиторію вказують лише для виду, коли група не на парах.');
  else if (body.roomId && !room) errors.add('roomId', 'Аудиторію не знайдено.');
  // керівники, як у core: лише для SUSPEND, кожен — активний викладач, без повторів
  const supervisorIds = body.supervisorIds ?? [];
  if (supervisorIds.length > 0 && effect && effect !== 'SUSPEND') {
    errors.add('supervisorIds', 'Керівників призначають лише для виду, коли група не на парах (практика, НС…).');
  } else {
    supervisorIds.forEach((teacherId, i) => {
      const teacher = teachers.find(teacherId);
      if (supervisorIds.indexOf(teacherId) !== i) errors.add(`supervisorIds[${i}]`, 'Цього викладача вже призначено керівником.');
      else if (!teacher) errors.add(`supervisorIds[${i}]`, 'Викладача не знайдено.');
      else if (!teacher.active) errors.add(`supervisorIds[${i}]`, `Викладач ${teacher.shortName} неактивний: його не можна призначити керівником.`);
    });
  }
  errors.throwIfAny();
  const chosen = kind as PeriodKindFixture;
  const suspend = chosen.effect === 'SUSPEND';
  const supervisors = suspend
    ? supervisorIds
        .map((tid) => teachers.find(tid))
        .filter((t) => t !== undefined)
        .map((t) => ({ id: t.id, shortName: t.shortName, displayName: t.displayName }))
        .sort((a, b) => a.shortName.localeCompare(b.shortName, 'uk'))
    : [];
  const fields = {
    semesterId: body.semesterId,
    kind: legacyKindOf(chosen),
    kindId: chosen.id,
    periodKind: kindRef(chosen),
    title,
    startsOn: body.startsOn,
    endsOn: body.endsOn,
    studyMode: chosen.effect === 'ONLINE' ? studyMode : null,
    orderNote,
    scope,
    courses: scope === 'COURSES' ? [...new Set(courses)].sort((a, b) => a - b) : null,
    groupIds: scope === 'GROUPS' ? [...new Set(groupIds)] : null,
    groups:
      scope === 'GROUPS'
        ? [...new Set(groupIds)].map((gid) => groups.find(gid)).filter((g) => g !== undefined).map(groupRefOf)
        : [],
    roomId: suspend && room ? room.id : null,
    room: suspend && room ? roomRefOf(room) : null,
    supervisorIds: supervisors.map((t) => t.id),
    supervisors,
  };
  if (id) {
    return periods.update(id, (stored) => {
      checkVersion(stored, body.version);
      // правка імпортованого періоду робить його ручним (ключ лишається): наступний імпорт його не чіпає
      return { ...stored, ...fields, source: 'MANUAL', version: stored.version + 1 };
    });
  }
  return periods.insert({ id: mockId('calendar-period', crypto.randomUUID()), ...fields, source: 'MANUAL', sourceKey: null, version: 0 });
}

// ───────────────────────── Обробники ─────────────────────────

export const handlers = [
  // Семестри
  http.get(apiPath('/admin/semesters'), () =>
    HttpResponse.json([...semesters.all()].sort((a, b) => compareIsoDates(a.startsOn, b.startsOn))),
  ),
  http.get(apiPath('/admin/semesters/:id'), ({ params }) => HttpResponse.json(semesters.get(String(params.id)))),
  http.post(apiPath('/admin/semesters'), async ({ request }) =>
    HttpResponse.json(saveSemester(await readJson<SemesterRequest>(request)), { status: 201 }),
  ),
  http.put(apiPath('/admin/semesters/:id'), async ({ request, params }) =>
    HttpResponse.json(saveSemester(await readJson<SemesterRequest>(request), String(params.id))),
  ),
  http.delete(apiPath('/admin/semesters/:id'), ({ params }) => {
    const semester = semesters.get(String(params.id));
    if (semester.active) throw conflict('Активний семестр не можна видалити: спершу активуйте інший.');
    if (periods.all().some((p) => p.semesterId === semester.id)) {
      throw conflict(`Семестр «${semester.name}» не можна видалити: у ньому є календарні періоди, навантаження або розклад.`);
    }
    semesters.remove(semester.id);
    return new HttpResponse(null, { status: 204 });
  }),
  http.post(apiPath('/admin/semesters/:id/activate'), ({ params }) => {
    const id = String(params.id);
    semesters.get(id);
    for (const semester of semesters.all()) {
      semesters.update(semester.id, (current) => ({
        ...current,
        active: current.id === id,
        version: current.active !== (current.id === id) ? current.version + 1 : current.version,
      }));
    }
    return HttpResponse.json(semesters.get(id));
  }),

  // Тижні
  http.get(apiPath('/admin/semesters/:id/weeks'), ({ params }) => HttpResponse.json(weeksOf(semesters.get(String(params.id))))),
  http.put(apiPath('/admin/semesters/:id/weeks/:weekStart'), async ({ request, params }) => {
    const semester = semesters.get(String(params.id));
    const weekStart = String(params.weekStart);
    const body = await readJson<{ parity?: unknown }>(request);
    const errors = fieldErrors();
    if (!isIsoDate(weekStart) || isoWeekday(weekStart) !== 1) errors.add('weekStart', 'Тиждень задається датою його понеділка.');
    else if (!weeksOf(semester).some((w) => w.weekStart === weekStart)) {
      errors.add('weekStart', `Тиждень ${formatNumericDate(weekStart)} не належить семестру.`);
    }
    if (body.parity !== 'NUMERATOR' && body.parity !== 'DENOMINATOR') {
      errors.add('parity', 'Парність тижня — чисельник (NUMERATOR) або знаменник (DENOMINATOR).');
    }
    errors.throwIfAny();
    const parity = body.parity as WeekParity;
    const week = weeksOf(semester).find((w) => w.weekStart === weekStart) as CalendarWeek;
    overrides.update((map) => {
      const next = new Map(map);
      // вибір тієї самої парності, що й за чергуванням, — це зняття перевизначення
      if (parity === week.computedParity) next.delete(`${semester.id}|${weekStart}`);
      else next.set(`${semester.id}|${weekStart}`, parity);
      return next;
    });
    return HttpResponse.json(weeksOf(semester).find((w) => w.weekStart === weekStart));
  }),
  http.delete(apiPath('/admin/semesters/:id/weeks/:weekStart'), ({ params }) => {
    const semester = semesters.get(String(params.id));
    overrides.update((map) => {
      const next = new Map(map);
      next.delete(`${semester.id}|${String(params.weekStart)}`);
      return next;
    });
    return new HttpResponse(null, { status: 204 });
  }),

  // Профілі дзвінків
  http.get(apiPath('/admin/bell-profiles'), () => HttpResponse.json(bellProfiles.all())),
  http.get(apiPath('/admin/bell-profiles/:id'), ({ params }) => HttpResponse.json(bellProfiles.get(String(params.id)))),
  http.post(apiPath('/admin/bell-profiles'), async ({ request }) =>
    HttpResponse.json(saveBellProfile(await readJson<BellProfileRequest>(request)), { status: 201 }),
  ),
  http.put(apiPath('/admin/bell-profiles/:id'), async ({ request, params }) =>
    HttpResponse.json(saveBellProfile(await readJson<BellProfileRequest>(request), String(params.id))),
  ),
  http.delete(apiPath('/admin/bell-profiles/:id'), ({ params }) => {
    const profile = bellProfiles.get(String(params.id));
    if (profile.defaultProfile) throw conflict(`Профіль «${profile.name}» — типовий у налаштуваннях: спершу виберіть інший.`);
    if (semesters.all().some((s) => s.bellProfileId === profile.id)) {
      throw conflict(`Профіль «${profile.name}» використовує семестр: спершу змініть дзвінки семестру.`);
    }
    bellProfiles.remove(profile.id);
    return new HttpResponse(null, { status: 204 });
  }),

  // Календарні періоди
  http.get(apiPath('/admin/calendar-periods'), ({ request }) => {
    const semesterId = new URL(request.url).searchParams.get('semesterId');
    const list = periods.all().filter((p) => !semesterId || p.semesterId === semesterId);
    return HttpResponse.json(list.sort((a, b) => compareIsoDates(a.startsOn, b.startsOn) || a.title.localeCompare(b.title, 'uk')));
  }),
  http.get(apiPath('/admin/calendar-periods/:id'), ({ params }) => HttpResponse.json(periods.get(String(params.id)))),
  http.post(apiPath('/admin/calendar-periods'), async ({ request }) =>
    HttpResponse.json(savePeriod(await readJson<CalendarPeriodRequest>(request)), { status: 201 }),
  ),
  http.put(apiPath('/admin/calendar-periods/:id'), async ({ request, params }) =>
    HttpResponse.json(savePeriod(await readJson<CalendarPeriodRequest>(request), String(params.id))),
  ),
  http.delete(apiPath('/admin/calendar-periods/:id'), ({ params }) => {
    periods.remove(String(params.id));
    return new HttpResponse(null, { status: 204 });
  }),

  // Вікна змін (лише читання в цій області)
  http.get(apiPath('/admin/shift-windows'), () => HttpResponse.json(SHIFT_WINDOWS)),
];
