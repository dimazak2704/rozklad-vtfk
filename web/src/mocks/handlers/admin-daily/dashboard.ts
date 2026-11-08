/**
 * Панель диспетчера на дату (CONTRACTS §2: GET /api/admin/dashboard): лічильники з поточного стану і
 * сповіщення, що вимагають дії: пара скоро почнеться без заміни, викладач мовчить довше за тайм-аут,
 * відмова, неопубліковане розпорядження, облік годин.
 */
import type { DashboardAlert, DashboardDto, IsoDate } from '@/api/types';
import { diffDays, formatDayMonth, kyivClock, timeToMinutes } from '@/lib/date';
import { countLabel } from '@/lib/labels';
import { isStudyDay, parityOn } from '../../fixtures';
import { currentSettings } from '../settings';
import { buildDraft } from './orders';
import { absenceCovers, affectedLessons, isCovered } from './rules';
import { changesOn, type World } from './world';

/** За скільки хвилин до початку пара без заміни стає тривогою. */
const UNCOVERED_SOON_MINUTES = 90;
const SEVERITY_ORDER: Record<DashboardAlert['severity'], number> = { ERROR: 0, WARN: 1, INFO: 2 };
const PAIR_FORMS = ['пара', 'пари', 'пар'] as const;
const ROW_FORMS = ['рядок', 'рядки', 'рядків'] as const;

const changesLink = (date: IsoDate, extra: Record<string, string> = {}) =>
  `/admin/changes?${new URLSearchParams({ date, ...extra }).toString()}`;

export function dashboardFor(world: World, date: IsoDate, now: Date): DashboardDto {
  const absences = [...world.absences.values()].filter((a) => absenceCovers(a, date));
  const affected = absences.flatMap((absence) => affectedLessons(world, absence).filter((item) => item.date === date));
  const uncovered = affected.filter((item) => !isCovered(item.decision));
  const changes = changesOn(world, date);
  const pending = changes.filter((c) => c.status === 'PENDING_CONFIRMATION');
  const confirmed = changes.filter((c) => c.status === 'CONFIRMED');
  const declined = affected.filter((item) => item.decision?.status === 'DECLINED');
  const published = [...world.orders.values()].some((o) => o.date === date && o.status === 'PUBLISHED');
  // розпорядження складають лише на навчальні дні семестру
  const draft = isStudyDay(date) ? buildDraft(world, date, currentSettings(), now) : null;
  const draftRows = draft ? draft.items.length + draft.roomChanges.length : 0;
  const clock = kyivClock(now);
  const alerts: DashboardAlert[] = [];

  if (clock.date === date) {
    for (const item of uncovered) {
      const minutesLeft = timeToMinutes(item.lesson.startsAt) - clock.minutes;
      if (minutesLeft < 0 || minutesLeft > UNCOVERED_SOON_MINUTES) continue;
      alerts.push({
        kind: 'UNCOVERED_SOON',
        severity: 'ERROR',
        message: `За ${minutesLeft} хв почнеться ${item.lesson.pair} пара ${item.lesson.group.code} (${item.lesson.discipline.shortName}) — заміни ще немає`,
        link: changesLink(date, { lesson: item.lesson.id }),
      });
    }
  }
  for (const item of declined) {
    const change = item.decision;
    if (!change) continue;
    alerts.push({
      kind: 'DECLINED',
      severity: 'ERROR',
      message: `Відмова: ${change.new.teacher?.shortName ?? 'викладач'} не може взяти ${item.lesson.pair} пару ${item.lesson.group.code}. Потрібен інший кандидат`,
      link: changesLink(date, { lesson: item.lesson.id }),
    });
  }
  for (const change of pending) {
    if (!change.confirmDeadline || Date.parse(change.confirmDeadline) > now.getTime()) continue;
    const overdue = Math.max(1, Math.round((now.getTime() - Date.parse(change.confirmDeadline)) / 60_000));
    alerts.push({
      kind: 'PENDING_OVERDUE',
      severity: 'WARN',
      message: `${change.new.teacher?.shortName ?? 'Викладач'} не відповідає вже ${overdue} хв після строку: ${change.group.code}, ${change.pairFrom} пара`,
      link: change.originalLessonId ? changesLink(date, { lesson: change.originalLessonId }) : changesLink(date),
    });
  }
  const daysAhead = diffDays(clock.date, date);
  if (!published && draftRows > 0 && daysAhead >= 0 && daysAhead <= 1) {
    alerts.push({
      kind: 'ORDER_NOT_PUBLISHED',
      severity: 'INFO',
      message: `Розпорядження на ${formatDayMonth(date)} ще не опубліковано: ${countLabel(draftRows, ROW_FORMS)} у чернетці`,
      link: `/admin/orders?date=${date}`,
    });
  }
  if (uncovered.length > 0 && clock.date !== date) {
    alerts.push({
      kind: 'UNCOVERED',
      severity: 'WARN',
      message: `${countLabel(uncovered.length, PAIR_FORMS)} на ${formatDayMonth(date)} без рішення`,
      link: changesLink(date, { filter: 'uncovered' }),
    });
  }
  // ДЕМО: облік годин рахує область «Облік»; приклад сповіщення «вичитано» з реальною групою й дисципліною
  alerts.push({
    kind: 'DISCIPLINE_FINISHED',
    severity: 'INFO',
    message: 'Вичитано: Вст.спц. у 1ТК1 — план годин виконано, у розкладі ще лишаються пари до кінця семестру',
    link: '/admin/accounting',
  });

  const botAbsencesToday = [...world.absences.values()].filter(
    (a) => a.source === 'BOT' && a.createdAt && kyivClock(new Date(a.createdAt)).date === clock.date,
  ).length;

  return {
    date,
    parity: parityOn(date),
    absences: new Set(absences.map((a) => a.teacher.id)).size,
    uncoveredLessons: uncovered.length,
    pending: pending.length,
    confirmed: confirmed.length,
    declined: declined.length,
    orderDraft: !draft || (published && draftRows === 0) ? null : { id: draft.id, itemCount: draftRows },
    generation: null,
    // ДЕМО: розбір вільного тексту від бота — один виклик моделі на відсутність (≈ 0,0014 $)
    aiSpendToday: Math.round(botAbsencesToday * 0.0014 * 10_000) / 10_000,
    alerts: alerts.sort((a, b) => SEVERITY_ORDER[a.severity] - SEVERITY_ORDER[b.severity]),
  };
}
