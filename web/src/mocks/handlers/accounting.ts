/**
 * Облік годин (CONTRACTS §2 «Accounting», DOMAIN §9). План — сума планових годин навантаження
 * (handlers/load.ts), факт і прогноз — заняття опублікованого шаблону, розгорнуті на дати семестру з
 * парністю, перевизначеннями тижнів і періодами календаря (handlers/calendar.ts). «Зараз» — київський час.
 * Правила — як у core (accounting/internal/HoursLedger): допуск 2 години, поділена дисципліна рахується
 * за когортою з найбільшим прогнозом.
 */
import { http, HttpResponse } from 'msw';
import type { AccountingRow, AccountingSuggestionDetail } from '@/api/accounting';
import type { AccountingStatus, IsoDate, TeacherStatsDto, TemplateLessonDto, Uuid } from '@/api/types';
import { addDays, compareIsoDates, formatDayMonth, isoWeekday, kyivClock, timeToMinutes } from '@/lib/date';
import { plural } from '@/lib/labels';
import { ukCollator } from '@/lib/sort';
import { TEMPLATE_LESSONS } from '../fixtures';
import { mockId } from '../ids';
import { apiPath, badRequest } from '../kit';
import { bellProfiles, parityOfDate, periods, semesters, activeSemester } from './calendar';
import { disciplineRefOf, disciplines, groupRefOf, groups, teacherRefOf, teachers } from './catalog';
import { loadItems } from './load';
import type { CalendarPeriod, CalendarSemester } from '@/api/calendar';

const HOURS_PER_PAIR = 2;
const TOLERANCE_HOURS = 2;

interface LedgerLesson {
  id: Uuid;
  date: IsoDate;
  pair: number;
  subgroup: number;
  ended: boolean;
}

function periodTouchesGroup(period: CalendarPeriod, groupId: Uuid, course: number): boolean {
  switch (period.scope) {
    case 'ALL':
      return true;
    case 'COURSES':
      return (period.courses ?? []).includes(course);
    case 'GROUPS':
      return (period.groupIds ?? []).includes(groupId);
  }
}

/** Чи має група пари цієї дати: канікули й «без занять» знімають усі пари, практика — пари групи. */
function groupStudiesOn(date: IsoDate, groupId: Uuid, course: number, semesterPeriods: readonly CalendarPeriod[]): boolean {
  return !semesterPeriods.some(
    (p) =>
      (p.kind === 'HOLIDAY' || p.kind === 'NO_CLASSES' || p.kind === 'PRACTICE') &&
      compareIsoDates(date, p.startsOn) >= 0 &&
      compareIsoDates(date, p.endsOn) <= 0 &&
      periodTouchesGroup(p, groupId, course),
  );
}

/** Заняття шаблону, розгорнуті на дати семестру для потрібних пар «група|дисципліна». */
function ledgerLessons(semester: CalendarSemester, keys: ReadonlySet<string>, now: Date): Map<string, LedgerLesson[]> {
  const clock = kyivClock(now);
  const profile = bellProfiles.find(semester.bellProfileId ?? '') ?? bellProfiles.all().find((p) => p.defaultProfile);
  const endsAt = new Map((profile?.slots ?? []).map((s) => [`${s.weekday}|${s.pair}`, timeToMinutes(s.endsAt)]));
  const semesterPeriods = periods.all().filter((p) => p.semesterId === semester.id);
  const byWeekday = new Map<number, TemplateLessonDto[]>();
  for (const lesson of TEMPLATE_LESSONS) {
    if (!keys.has(`${lesson.group.id}|${lesson.discipline.id}`)) continue;
    byWeekday.set(lesson.weekday, [...(byWeekday.get(lesson.weekday) ?? []), lesson]);
  }
  const out = new Map<string, LedgerLesson[]>();
  for (let date = semester.startsOn; compareIsoDates(date, semester.endsOn) <= 0; date = addDays(date, 1)) {
    const weekday = isoWeekday(date);
    if (weekday > 5) continue;
    const parity = parityOfDate(semester, date);
    for (const lesson of byWeekday.get(weekday) ?? []) {
      if (lesson.parity !== 'BOTH' && lesson.parity !== parity) continue;
      if (!groupStudiesOn(date, lesson.group.id, lesson.group.course, semesterPeriods)) continue;
      const end = endsAt.get(`${weekday}|${lesson.pair}`) ?? 24 * 60;
      const ended = compareIsoDates(date, clock.date) < 0 || (date === clock.date && end <= clock.minutes);
      const key = `${lesson.group.id}|${lesson.discipline.id}`;
      out.set(key, [
        ...(out.get(key) ?? []),
        { id: mockId('lesson', `${lesson.id}@${date}`), date, pair: lesson.pair, subgroup: lesson.subgroup, ended },
      ]);
    }
  }
  return out;
}

const chronological = (a: LedgerLesson, b: LedgerLesson) => compareIsoDates(a.date, b.date) || a.pair - b.pair;

/** Когорта з найбільшим прогнозом: підгрупа k відвідує свої заняття й заняття всієї групи. */
function representativeCohort(lessons: readonly LedgerLesson[]): LedgerLesson[] {
  const subgroups = [...new Set(lessons.map((l) => l.subgroup).filter((s) => s > 0))].sort((a, b) => a - b);
  if (subgroups.length === 0) return [...lessons].sort(chronological);
  let best: LedgerLesson[] = [];
  let bestEnded = -1;
  for (const subgroup of subgroups) {
    const cohort = lessons.filter((l) => l.subgroup === 0 || l.subgroup === subgroup).sort(chronological);
    const ended = cohort.filter((l) => l.ended).length;
    if (cohort.length > best.length || (cohort.length === best.length && ended > bestEnded)) {
      best = cohort;
      bestEnded = ended;
    }
  }
  return best;
}

function statusOf(plan: number, fact: number, forecast: number): AccountingStatus {
  if (fact >= plan) return 'FINISHED';
  if (forecast > plan + TOLERANCE_HOURS) return 'WILL_OVERRUN';
  if (forecast < plan - TOLERANCE_HOURS) return 'WILL_UNDERRUN';
  return 'ON_TRACK';
}

const pairsWord = (count: number) => `${count} ${plural(count, ['пару', 'пари', 'пар'])}`;

function ledger(plan: number, lessons: readonly LedgerLesson[]) {
  const cohort = representativeCohort(lessons);
  const ended = cohort.filter((l) => l.ended).length;
  const fact = ended * HOURS_PER_PAIR;
  const remaining = (cohort.length - ended) * HOURS_PER_PAIR;
  const forecast = fact + remaining;
  const status = statusOf(plan, fact, forecast);
  const finishingIndex = plan === 0 ? -1 : cohort.findIndex((_, index) => (index + 1) * HOURS_PER_PAIR >= plan);
  const forecastFinishDate = finishingIndex >= 0 ? (cohort[finishingIndex]?.date ?? null) : null;
  let suggestion: AccountingSuggestionDetail | null = null;
  const beyond =
    plan === 0 ? cohort.filter((l) => !l.ended) : finishingIndex >= 0 ? cohort.slice(finishingIndex + 1).filter((l) => !l.ended) : [];
  if ((status === 'FINISHED' || status === 'WILL_OVERRUN') && beyond.length > 0) {
    const first = beyond[0] as LedgerLesson;
    suggestion = {
      kind: 'DISCIPLINE_FINISHED',
      text: forecastFinishDate
        ? `Вичитано ${formatDayMonth(forecastFinishDate)}: зняти ${pairsWord(beyond.length)} після цієї дати`
        : `Години не заплановані: зняти ${pairsWord(beyond.length)}`,
      fromDate: first.date,
      lessons: beyond.length,
      lessonIds: beyond.map((l) => l.id),
    };
  } else if (status === 'WILL_UNDERRUN') {
    const missing = plan - forecast;
    const count = Math.ceil(missing / HOURS_PER_PAIR);
    suggestion = { kind: 'ADDITIONAL', text: `Не вистачає ${missing} год: додати ${pairsWord(count)}`, fromDate: null, lessons: count, lessonIds: [] };
  }
  return { fact, remaining, forecast, status, forecastFinishDate, suggestion };
}

export function accountingRows(semesterId: Uuid | null, groupId: Uuid | null, now: Date = new Date()): AccountingRow[] {
  const semester = semesterId ? semesters.get(semesterId) : activeSemester();
  if (groupId) groups.get(groupId);
  const plans = new Map<string, number>();
  for (const item of loadItems.all()) {
    if (item.semesterId !== semester.id || (groupId && item.group.id !== groupId)) continue;
    const key = `${item.group.id}|${item.discipline.id}`;
    plans.set(key, (plans.get(key) ?? 0) + item.plannedHours);
  }
  const lessonsByKey = ledgerLessons(semester, new Set(plans.keys()), now);
  const rows: AccountingRow[] = [];
  for (const [key, plan] of plans) {
    const [gid = '', did = ''] = key.split('|');
    const group = groups.find(gid);
    const discipline = disciplines.find(did);
    if (!group || !discipline) continue;
    rows.push({ group: groupRefOf(group), discipline: disciplineRefOf(discipline), plan, ...ledger(plan, lessonsByKey.get(key) ?? []) });
  }
  return rows.sort(
    (a, b) =>
      a.group.course - b.group.course ||
      ukCollator.compare(a.group.code, b.group.code) ||
      ukCollator.compare(a.discipline.shortName, b.discipline.shortName),
  );
}

// ───────────────────────── Статистика викладачів ─────────────────────────

interface DemoChange {
  date: IsoDate;
  /** Хто провів пару замість іншого (null — пару скасовано). */
  substitute: string | null;
  /** Чию пару замінено. */
  absent: string;
  pairs: number;
}

/**
 * ДЕМО-заміни для статистики. Жовтень — рядки розпорядження №35 на 19.10.2026 (DOMAIN §11): Прокопчук
 * замість Крисак Л. (1КІ2, 2 пара), взаємна заміна Непийвода/Кренцін (4КІ1, 2 пара), скасована пара
 * Крисак Л. (1ТК1, 1 пара). Листопад — синтетичні записи для показу таблиці.
 */
const DEMO_CHANGES: readonly DemoChange[] = [
  { date: '2026-10-19', substitute: 'Прокопчук', absent: 'Крисак Л.В.', pairs: 1 },
  { date: '2026-10-19', substitute: 'Непийвода', absent: 'Кренцін', pairs: 1 },
  { date: '2026-10-19', substitute: null, absent: 'Крисак Л.В.', pairs: 1 },
  { date: '2026-11-04', substitute: 'Непийвода', absent: 'Кренцін', pairs: 2 },
  { date: '2026-11-04', substitute: 'Колесник А.', absent: 'Кренцін', pairs: 1 },
  { date: '2026-11-05', substitute: 'Гарболінська', absent: 'Прокопчук', pairs: 1 },
];

export function teacherStats(month: string): TeacherStatsDto[] {
  const stats = new Map<Uuid, TeacherStatsDto>();
  const touch = (shortName: string) => {
    const teacher = teachers.all().find((t) => t.shortName === shortName);
    if (!teacher) return null;
    const current = stats.get(teacher.id) ?? {
      teacher: teacherRefOf(teacher),
      substitutionsCount: 0,
      substitutionsHours: 0,
      missedCount: 0,
    };
    stats.set(teacher.id, current);
    return current;
  };
  for (const change of DEMO_CHANGES.filter((c) => c.date.startsWith(month))) {
    const absent = touch(change.absent);
    if (absent) absent.missedCount += change.pairs;
    if (change.substitute) {
      const substitute = touch(change.substitute);
      if (substitute) {
        substitute.substitutionsCount += change.pairs;
        substitute.substitutionsHours += change.pairs * HOURS_PER_PAIR;
      }
    }
  }
  return [...stats.values()].sort(
    (a, b) => b.substitutionsCount - a.substitutionsCount || ukCollator.compare(a.teacher.shortName, b.teacher.shortName),
  );
}

// ───────────────────────── Обробники ─────────────────────────

export const handlers = [
  http.get(apiPath('/admin/accounting'), ({ request }) => {
    const url = new URL(request.url);
    return HttpResponse.json(accountingRows(url.searchParams.get('semesterId'), url.searchParams.get('groupId')));
  }),
  http.get(apiPath('/admin/accounting/teachers'), ({ request }) => {
    const raw = new URL(request.url).searchParams.get('month');
    const month = raw ?? kyivClock().date.slice(0, 7);
    if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(month)) {
      throw badRequest('Місяць має вигляд РРРР-ММ, наприклад 2026-10.', [{ field: 'month', message: 'Формат РРРР-ММ' }]);
    }
    return HttpResponse.json(teacherStats(month));
  }),
];
