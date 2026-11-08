/**
 * Навантаження (CONTRACTS §2 «Load»): CRUD, тижневий шаблон «1 + ½», виведення з версії розкладу.
 * Початкові записи — TIMETABLE_DERIVED з опублікованого шаблону (DOMAIN §12): пара щотижня — 1, лише
 * чисельник або знаменник — ½; уся група й підгрупи — окремі записи, як у core (LoadDerivation).
 * Вид заняття в таблиці не вказано (MIXED), форма контролю невідома (NONE).
 */
import { http, HttpResponse } from 'msw';
import type { DerivedLoadResult, LoadItem, LoadItemRequest, SkippedDerivation } from '@/api/load';
import type {
  ControlForm,
  FieldErrorDto,
  LessonKind,
  RoomType,
  TemplateLessonDto,
  TimetableVersionDto,
  Uuid,
} from '@/api/types';
import { DEFAULT_HOURS_PER_PAIR, loadPattern } from '@/components/load/pattern';
import { PUBLISHED_VERSION_EFFECTIVE_FROM, PUBLISHED_VERSION_ID, TEMPLATE_LESSONS } from '../fixtures';
import { mockId } from '../ids';
import { apiPath, badRequest, conflict, notFound, problem, readJson } from '../kit';
import { createMockCollection } from '../store';
import { activeSemester, semesters } from './calendar';
import {
  disciplineRefOf,
  disciplines,
  groupRefOf,
  groups,
  registerCatalogUsage,
  roomRefOf,
  rooms,
  teacherRefOf,
  teachers,
} from './catalog';

const LESSON_KINDS: readonly LessonKind[] = ['LECTURE', 'PRACTICE', 'LAB', 'SEMINAR', 'MIXED'];
const CONTROL_FORMS: readonly ControlForm[] = ['EXAM', 'CREDIT', 'NONE'];
const ROOM_TYPES: readonly RoomType[] = ['CLASSROOM', 'COMPUTER_LAB', 'LAB', 'GYM', 'SPORTS_GROUND', 'OTHER'];

// ───────────────────────── Виведення з шаблону ─────────────────────────

interface DerivedDraft {
  groupId: Uuid;
  disciplineId: Uuid;
  split: boolean;
  hoursPerWeek: number;
  /** підгрупа → викладач (0 — уся група) */
  teachers: Map<number, Uuid>;
}

const pairsOf = (lessons: readonly TemplateLessonDto[]) => lessons.reduce((sum, l) => sum + (l.parity === 'BOTH' ? 1 : 0.5), 0);

/** Викладач з найбільшою кількістю пар; за рівності — менший id, щоб результат був сталим. */
function mostFrequentTeacher(lessons: readonly TemplateLessonDto[]): Uuid | null {
  const weight = new Map<Uuid, number>();
  for (const lesson of lessons) {
    if (lesson.teacher) weight.set(lesson.teacher.id, (weight.get(lesson.teacher.id) ?? 0) + (lesson.parity === 'BOTH' ? 1 : 0.5));
  }
  let best: Uuid | null = null;
  let bestWeight = -1;
  for (const [id, value] of [...weight.entries()].sort(([a], [b]) => a.localeCompare(b))) {
    if (value > bestWeight) {
      best = id;
      bestWeight = value;
    }
  }
  return best;
}

export function deriveFromTemplate(lessons: readonly TemplateLessonDto[], hoursPerPair = DEFAULT_HOURS_PER_PAIR): DerivedDraft[] {
  const byKey = new Map<string, TemplateLessonDto[]>();
  for (const lesson of lessons) {
    const key = `${lesson.group.id}|${lesson.discipline.id}`;
    byKey.set(key, [...(byKey.get(key) ?? []), lesson]);
  }
  const drafts: DerivedDraft[] = [];
  for (const rows of byKey.values()) {
    const first = rows[0] as TemplateLessonDto;
    const whole = rows.filter((l) => l.subgroup === 0);
    const split = rows.filter((l) => l.subgroup > 0);
    if (whole.length > 0) {
      const teacher = mostFrequentTeacher(whole);
      drafts.push({
        groupId: first.group.id,
        disciplineId: first.discipline.id,
        split: false,
        hoursPerWeek: pairsOf(whole) * hoursPerPair,
        teachers: new Map(teacher ? [[0, teacher]] : []),
      });
    }
    if (split.length > 0) {
      const bySubgroup = new Map<number, TemplateLessonDto[]>();
      for (const lesson of split) bySubgroup.set(lesson.subgroup, [...(bySubgroup.get(lesson.subgroup) ?? []), lesson]);
      const teachersBySubgroup = new Map<number, Uuid>();
      for (const [subgroup, list] of [...bySubgroup.entries()].sort(([a], [b]) => a - b)) {
        const teacher = mostFrequentTeacher(list);
        if (teacher) teachersBySubgroup.set(subgroup, teacher);
      }
      drafts.push({
        groupId: first.group.id,
        disciplineId: first.discipline.id,
        split: true,
        hoursPerWeek: Math.max(...[...bySubgroup.values()].map(pairsOf)) * hoursPerPair,
        teachers: teachersBySubgroup,
      });
    }
  }
  return drafts;
}

function itemFromDraft(draft: DerivedDraft, semesterId: Uuid, teachingWeeks: number): LoadItem {
  const group = groups.get(draft.groupId);
  const discipline = disciplines.get(draft.disciplineId);
  return {
    id: mockId('load-item', `${semesterId}|${draft.groupId}|${draft.disciplineId}|MIXED|${draft.split}`),
    semesterId,
    group: groupRefOf(group),
    discipline: disciplineRefOf(discipline),
    lessonKind: 'MIXED',
    hoursPerWeek: draft.hoursPerWeek,
    plannedHours: Math.round(draft.hoursPerWeek * teachingWeeks),
    splitSubgroups: draft.split,
    teachers: [...draft.teachers.entries()].map(([subgroup, teacherId]) => ({ subgroup, teacher: teacherRefOf(teachers.get(teacherId)) })),
    requiredRoomType: null,
    preferredRoom: null,
    controlForm: 'NONE',
    source: 'TIMETABLE_DERIVED',
    pattern: loadPattern(draft.hoursPerWeek),
    streamId: null,
    note: null,
    version: 0,
  };
}

/**
 * Плани з програм дисциплін для 4КІ1. НДЕКСМ — реальна розбіжність (DOMAIN §4: програма каже 40 год,
 * навчальний план — 44). СТП і ОПЦЗ — ДЕМО-обсяги, менші за тижневі години × тижні: так облік годин
 * показує «вичитано» й пропозицію зняти зайві пари.
 */
const PROGRAM_PLANS: ReadonlyArray<{ group: string; discipline: string; plannedHours: number; note: string }> = [
  { group: '4КІ1', discipline: 'НДЕКСМ', plannedHours: 40, note: 'Програма дисципліни: 40 год; у навчальному плані — 44 (24 лекц. + 20 лаб.).' },
  { group: '4КІ1', discipline: 'СТП', plannedHours: 48, note: 'Обсяг за програмою дисципліни.' },
  { group: '4КІ1', discipline: 'ОПЦЗ', plannedHours: 20, note: 'Обсяг за програмою дисципліни.' },
];

export const loadItems = createMockCollection<LoadItem>('Запис навантаження', () => {
  const semester = activeSemester();
  return deriveFromTemplate(TEMPLATE_LESSONS).map((draft) => {
    const item = itemFromDraft(draft, semester.id, semester.teachingWeeks);
    const program = PROGRAM_PLANS.find((p) => p.group === item.group.code && p.discipline === item.discipline.shortName);
    return program && !item.splitSubgroups
      ? { ...item, plannedHours: program.plannedHours, source: 'PROGRAM' as const, note: program.note }
      : item;
  });
});

registerCatalogUsage((entity, id, extra) => {
  const items = loadItems.all();
  const used = (() => {
    switch (entity) {
      case 'groups':
        return items.some((i) => i.group.id === id);
      case 'disciplines':
        return items.some((i) => i.discipline.id === id);
      case 'teachers':
        return items.some((i) => i.teachers.some((t) => t.teacher.id === id));
      case 'rooms':
        return items.some((i) => i.preferredRoom?.id === id);
      case 'subgroup':
        return items.some((i) => i.group.id === id && i.teachers.some((t) => t.subgroup === extra?.subgroup));
      case 'departments':
      case 'specialties':
      case 'cycle-commissions':
      case 'buildings':
        return false;
    }
  })();
  return used ? 'навантаження' : null;
});

// ───────────────────────── Перевірки ─────────────────────────

function validate(body: LoadItemRequest, ownId?: Uuid): void {
  const errors: FieldErrorDto[] = [];
  const add = (field: string, message: string) => errors.push({ field, message });
  const semester = body.semesterId ? semesters.find(body.semesterId) : undefined;
  if (!semester) add('semesterId', 'Семестр не знайдено.');
  const group = body.groupId ? groups.find(body.groupId) : undefined;
  if (!group) add('groupId', 'Групу не знайдено.');
  if (!body.disciplineId || !disciplines.find(body.disciplineId)) add('disciplineId', 'Дисципліну не знайдено.');
  if (body.preferredRoomId && !rooms.find(body.preferredRoomId)) add('preferredRoomId', 'Аудиторію не знайдено.');
  const hours = body.hoursPerWeek;
  if (typeof hours !== 'number' || !Number.isFinite(hours) || hours <= 0) add('hoursPerWeek', 'Годин на тиждень має бути більше 0');
  else if (hours > 40) add('hoursPerWeek', 'Не більше 40 годин на тиждень');
  else if (Math.round(hours * 10) !== hours * 10) add('hoursPerWeek', 'Години — число з одним знаком після коми, наприклад 1.5');
  if (body.plannedHours != null && (!Number.isInteger(body.plannedHours) || body.plannedHours < 0 || body.plannedHours > 2000)) {
    add('plannedHours', 'Плановий обсяг — ціле число від 0 до 2000.');
  }
  if (body.lessonKind && !LESSON_KINDS.includes(body.lessonKind)) add('lessonKind', 'Невідомий вид заняття.');
  if (body.controlForm && !CONTROL_FORMS.includes(body.controlForm)) add('controlForm', 'Невідома форма контролю.');
  if (body.requiredRoomType && !ROOM_TYPES.includes(body.requiredRoomType)) add('requiredRoomType', 'Невідомий тип приміщення.');
  if (body.note && body.note.length > 1000) add('note', 'Не довше за 1000 символів.');
  const split = body.splitSubgroups === true;
  if (group) {
    const numbers = group.subgroups.map((s) => s.number);
    if (split && numbers.length === 0) {
      add('splitSubgroups', `Група ${group.code} не поділена на підгрупи: спершу додайте підгрупи в каталозі.`);
    }
    const seen = new Set<number>();
    (body.teachers ?? []).forEach((teacher, i) => {
      const field = `teachers[${i}]`;
      if (seen.has(teacher.subgroup)) add(`${field}.subgroup`, `Підгрупа ${teacher.subgroup} повторюється.`);
      seen.add(teacher.subgroup);
      if (split && teacher.subgroup === 0) add(`${field}.subgroup`, 'Для поділеної групи вкажіть номер підгрупи (1, 2, …).');
      else if (split && !numbers.includes(teacher.subgroup)) add(`${field}.subgroup`, `У групі ${group.code} немає підгрупи ${teacher.subgroup}.`);
      else if (!split && teacher.subgroup !== 0) add(`${field}.subgroup`, 'Для всієї групи підгрупа — 0.');
      if (!teachers.find(teacher.teacherId)) add(`${field}.teacherId`, 'Викладача не знайдено.');
    });
  }
  if (ownId) {
    const current = loadItems.get(ownId);
    if (current.semesterId !== body.semesterId) add('semesterId', 'Семестр навантаження змінити не можна: створіть новий запис.');
    if (current.group.id !== body.groupId) add('groupId', 'Групу навантаження змінити не можна: створіть новий запис.');
  }
  if (errors.length > 0) throw badRequest('Перевірте виділені поля.', errors);
  const kind = body.lessonKind ?? 'MIXED';
  const twin = loadItems
    .all()
    .find(
      (i) =>
        i.id !== ownId &&
        i.semesterId === body.semesterId &&
        i.group.id === body.groupId &&
        i.discipline.id === body.disciplineId &&
        i.lessonKind === kind &&
        i.splitSubgroups === split,
    );
  if (twin) throw conflict(`У групи ${twin.group.code} уже є таке навантаження з цієї дисципліни.`);
}

function save(body: LoadItemRequest, id?: Uuid): LoadItem {
  validate(body, id);
  const semester = semesters.get(body.semesterId);
  const group = groups.get(body.groupId);
  const discipline = disciplines.get(body.disciplineId);
  const room = body.preferredRoomId ? rooms.get(body.preferredRoomId) : null;
  const fields = {
    semesterId: semester.id,
    group: groupRefOf(group),
    discipline: disciplineRefOf(discipline),
    lessonKind: body.lessonKind ?? 'MIXED',
    hoursPerWeek: body.hoursPerWeek,
    plannedHours: body.plannedHours ?? Math.round(body.hoursPerWeek * semester.teachingWeeks),
    splitSubgroups: body.splitSubgroups === true,
    teachers: (body.teachers ?? [])
      .map((t) => ({ subgroup: t.subgroup, teacher: teacherRefOf(teachers.get(t.teacherId)) }))
      .sort((a, b) => a.subgroup - b.subgroup),
    requiredRoomType: body.requiredRoomType ?? null,
    preferredRoom: room ? roomRefOf(room) : null,
    controlForm: body.controlForm ?? 'NONE',
    pattern: loadPattern(body.hoursPerWeek),
    streamId: body.streamId ?? null,
    note: body.note?.trim() || null,
  };
  if (id) {
    return loadItems.update(id, (current) => {
      if (body.version != null && body.version !== current.version) {
        throw problem({
          status: 409,
          slug: 'stale-data',
          title: 'Дані змінилися',
          detail: 'Цей запис уже змінив хтось інший. Оновіть список і внесіть зміни ще раз.',
        });
      }
      // ручна правка виведеного запису робить його ручним: наступне виведення його не перезапише
      return { ...current, ...fields, source: current.source === 'TIMETABLE_DERIVED' ? 'MANUAL' : current.source, version: current.version + 1 };
    });
  }
  return loadItems.insert({ id: mockId('load-item', crypto.randomUUID()), ...fields, source: 'MANUAL', version: 0 });
}

function derive(versionId: Uuid): DerivedLoadResult {
  if (versionId !== PUBLISHED_VERSION_ID) throw notFound(`Версію розкладу з id ${versionId} не знайдено.`);
  const semester = activeSemester();
  const drafts = deriveFromTemplate(TEMPLATE_LESSONS);
  const existing = loadItems.all().filter((i) => i.semesterId === semester.id);
  let created = 0;
  let updated = 0;
  const skipped: SkippedDerivation[] = [];
  const keep = new Set<Uuid>();
  for (const draft of drafts) {
    const handMade = existing.find(
      (i) => i.group.id === draft.groupId && i.discipline.id === draft.disciplineId && i.source !== 'TIMETABLE_DERIVED',
    );
    if (handMade) {
      skipped.push({
        group: handMade.group,
        discipline: handMade.discipline,
        reason: 'Навантаження вже внесено вручну або з плану — його не перезаписано.',
      });
      continue;
    }
    const next = itemFromDraft(draft, semester.id, semester.teachingWeeks);
    keep.add(next.id);
    const previous = loadItems.find(next.id);
    if (!previous) {
      loadItems.insert(next);
      created += 1;
    } else if (previous.hoursPerWeek !== next.hoursPerWeek || JSON.stringify(previous.teachers) !== JSON.stringify(next.teachers)) {
      loadItems.update(next.id, () => ({ ...next, version: previous.version + 1 }));
      updated += 1;
    }
  }
  // виведені раніше записи, яких у розкладі вже немає, прибираємо
  let removed = 0;
  for (const item of existing) {
    if (item.source === 'TIMETABLE_DERIVED' && !keep.has(item.id)) {
      loadItems.remove(item.id);
      removed += 1;
    }
  }
  return {
    versionId,
    semesterId: semester.id,
    created,
    updated,
    removed,
    skipped,
    items: loadItems.all().filter((i) => i.semesterId === semester.id && i.source === 'TIMETABLE_DERIVED'),
  };
}

// ───────────────────────── Версії розкладу (резервний мок) ─────────────────────────

/**
 * GET /admin/timetable/versions належить області «Базовий розклад». Поки її мок-модуля немає, цей
 * обробник віддає опубліковану версію з fixtures, щоб «Вивести з розкладу» працювало; щойно
 * handlers/timetable.ts чи handlers/generation.ts зʼявляться, він вимикається (без двох відповідей на один шлях).
 */
const VERSIONS_MOCKED_ELSEWHERE = Object.keys(import.meta.glob(['./timetable.ts', './generation.ts'])).length > 0;

const versionsFallback = VERSIONS_MOCKED_ELSEWHERE
  ? []
  : [
      http.get(apiPath('/admin/timetable/versions'), ({ request }) => {
        const semesterId = new URL(request.url).searchParams.get('semesterId');
        const semester = activeSemester();
        const published: TimetableVersionDto = {
          id: PUBLISHED_VERSION_ID,
          name: 'Розклад з Excel 2025/26 (I семестр)',
          status: 'PUBLISHED',
          source: 'IMPORT',
          effectiveFrom: PUBLISHED_VERSION_EFFECTIVE_FROM,
          publishedAt: '2026-08-31T09:00:00Z',
          lessonCount: TEMPLATE_LESSONS.length,
          hardConflicts: 0,
          softWarnings: 0,
          metrics: null,
        };
        return HttpResponse.json(!semesterId || semesterId === semester.id ? [published] : []);
      }),
    ];

// ───────────────────────── Обробники ─────────────────────────

export const handlers = [
  http.get(apiPath('/admin/load'), ({ request }) => {
    const url = new URL(request.url);
    const semesterId = url.searchParams.get('semesterId');
    if (!semesterId) throw badRequest('Не вказано семестр.', [{ field: 'semesterId', message: 'Обовʼязковий параметр' }]);
    semesters.get(semesterId);
    const groupId = url.searchParams.get('groupId');
    const list = loadItems
      .all()
      .filter((i) => i.semesterId === semesterId && (!groupId || i.group.id === groupId))
      .sort(
        (a, b) =>
          a.group.code.localeCompare(b.group.code, 'uk', { numeric: true }) ||
          a.discipline.shortName.localeCompare(b.discipline.shortName, 'uk') ||
          Number(a.splitSubgroups) - Number(b.splitSubgroups),
      );
    return HttpResponse.json(list);
  }),
  http.get(apiPath('/admin/load/:id'), ({ params }) => HttpResponse.json(loadItems.get(String(params.id)))),
  http.post(apiPath('/admin/load/derive-from-version/:versionId'), ({ params }) => HttpResponse.json(derive(String(params.versionId)))),
  http.post(apiPath('/admin/load'), async ({ request }) =>
    HttpResponse.json(save(await readJson<LoadItemRequest>(request)), { status: 201 }),
  ),
  http.put(apiPath('/admin/load/:id'), async ({ request, params }) =>
    HttpResponse.json(save(await readJson<LoadItemRequest>(request), String(params.id))),
  ),
  http.delete(apiPath('/admin/load/:id'), ({ params }) => {
    loadItems.remove(String(params.id));
    return new HttpResponse(null, { status: 204 });
  }),
  ...versionsFallback,
];
