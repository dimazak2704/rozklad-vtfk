/**
 * Мета й пошук для оболонки (CONTRACTS §1): GET /api/public/meta і GET /api/public/search.
 */
import { http, HttpResponse } from 'msw';
import type { EntityType, GroupDto, PairTimeDto, PublicMetaDto, SearchResultDto, Weekday } from '@/api/types';
import { pairStatus } from '@/lib/bells';
import { isoWeekday, kyivClock } from '@/lib/date';
import { capitalize, ROOM_TYPE_LABEL } from '@/lib/labels';
import { matchScore } from '@/lib/search';
import {
  ACTIVE_BELL_PROFILE,
  ACTIVE_SEMESTER,
  bellSlots,
  departmentOf,
  DISCIPLINES,
  disciplinesOfTeacher,
  GROUPS,
  isStudyDay,
  parityOn,
  ROOMS,
  semesterWeekNumber,
  specialtyOf,
  TEACHERS,
} from '../fixtures';
import { apiPath, badRequest } from '../kit';

/** Назва з затвердження таблиці розкладу (R2: «Директор Вінницького технічного фахового коледжу»). */
export const COLLEGE_NAME = 'Вінницький технічний фаховий коледж';

/** Стан «зараз» за київським часом: семестр, тиждень, парність, поточна й наступна пара. */
export function publicMeta(now: Date = new Date()): PublicMetaDto {
  const clock = kyivClock(now);
  const studyDay = isStudyDay(clock.date);
  let currentPair: PairTimeDto | null = null;
  let nextPair: PairTimeDto | null = null;
  if (studyDay) {
    const weekday = isoWeekday(clock.date) as Weekday;
    const status = pairStatus(bellSlots(weekday), clock.minutes + clock.seconds / 60);
    const toPair = (slot: { pair: number; startsAt: string; endsAt: string }): PairTimeDto => ({
      number: slot.pair,
      startsAt: slot.startsAt,
      endsAt: slot.endsAt,
    });
    if (status.kind === 'in-pair') {
      currentPair = { ...toPair(status.current), minutesLeft: status.minutesLeft };
      nextPair = status.next ? toPair(status.next) : null;
    } else if (status.kind === 'break' || status.kind === 'before-day') {
      nextPair = toPair(status.next);
    }
  }
  return {
    collegeName: COLLEGE_NAME,
    semester: {
      id: ACTIVE_SEMESTER.id,
      name: ACTIVE_SEMESTER.name,
      startsOn: ACTIVE_SEMESTER.startsOn,
      endsOn: ACTIVE_SEMESTER.endsOn,
    },
    today: clock.date,
    weekNumber: semesterWeekNumber(clock.date),
    parity: parityOn(clock.date),
    studyDay,
    currentPair,
    nextPair,
    bellProfile: { id: ACTIVE_BELL_PROFILE.id, name: ACTIVE_BELL_PROFILE.name },
  };
}

interface Candidate {
  result: SearchResultDto;
  texts: string[];
}

const TYPE_ORDER: Record<EntityType, number> = { GROUP: 0, TEACHER: 1, ROOM: 2 };
const disciplineShort = new Map(DISCIPLINES.map((d) => [d.id, d.shortName]));

/** «4 курс, Компʼютерна інженерія»; якщо повна назва спеціальності невідома — відділення. */
function groupSublabel(group: GroupDto): string {
  const specialty = specialtyOf(group);
  const known = specialty.name !== specialty.letters;
  return `${group.course} курс, ${known ? specialty.name : departmentOf(group).name}`;
}

const CANDIDATES: readonly Candidate[] = [
  ...GROUPS.map((group) => ({
    result: {
      type: 'GROUP' as const,
      id: group.id,
      label: group.code,
      sublabel: groupSublabel(group),
      url: `/g/${encodeURIComponent(group.code)}`,
    },
    texts: [group.code],
  })),
  ...TEACHERS.map((teacher) => {
    const taught = [...disciplinesOfTeacher(teacher.id)].map((id) => disciplineShort.get(id) ?? '').filter(Boolean);
    return {
      result: {
        type: 'TEACHER' as const,
        id: teacher.id,
        label: teacher.shortName,
        sublabel: taught.length > 0 ? taught.slice(0, 3).join(', ') : null,
        url: `/t/${teacher.id}`,
      },
      texts: [teacher.shortName, teacher.displayName, teacher.lastName],
    };
  }),
  ...ROOMS.map((room) => {
    const numeric = /^\d+$/.test(room.name);
    return {
      result: {
        type: 'ROOM' as const,
        id: room.id,
        label: numeric ? `ауд. ${room.name}` : room.name,
        sublabel: capitalize(ROOM_TYPE_LABEL[room.type]),
        url: `/r/${room.id}`,
      },
      texts: [room.name, `ауд. ${room.name}`, ROOM_TYPE_LABEL[room.type]],
    };
  }),
];

/** Нечіткий пошук (DOMAIN §10, UI.md §4.3): «4ki1» → 4КІ1, «кренц» → Кренцін, «6» → ауд. 6. */
export function searchCatalog(query: string, limit: number): SearchResultDto[] {
  if (!query.trim()) return [];
  return CANDIDATES.map((candidate) => ({ candidate, score: matchScore(query, ...candidate.texts) }))
    .filter((entry) => entry.score >= 40)
    .sort(
      (a, b) =>
        b.score - a.score ||
        TYPE_ORDER[a.candidate.result.type] - TYPE_ORDER[b.candidate.result.type] ||
        a.candidate.result.label.localeCompare(b.candidate.result.label, 'uk'),
    )
    .slice(0, limit)
    .map((entry) => entry.candidate.result);
}

export const handlers = [
  http.get(apiPath('/public/meta'), () => HttpResponse.json(publicMeta())),

  http.get(apiPath('/public/search'), ({ request }) => {
    const url = new URL(request.url);
    const limit = Number(url.searchParams.get('limit') ?? 10);
    if (!Number.isInteger(limit) || limit < 1 || limit > 50) {
      return badRequest('Параметр limit має бути цілим числом від 1 до 50.', [
        { field: 'limit', message: 'Від 1 до 50' },
      ]);
    }
    return HttpResponse.json(searchCatalog(url.searchParams.get('q') ?? '', limit));
  }),
];
