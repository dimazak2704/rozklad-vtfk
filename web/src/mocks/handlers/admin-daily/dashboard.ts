/**
 * Панель диспетчера на дату (CONTRACTS §2: GET /api/admin/dashboard): лічильники з поточного стану і
 * сповіщення рівно тих видів, що надсилає core: NO_SEMESTER, DECLINED, UNCOVERED_LESSONS, PENDING_OVERDUE,
 * ORDER_NOT_PUBLISHED. `aiSpendToday` завжди null (витрати ШІ — у importer).
 */
import type { DashboardAlert, DashboardDto, IsoDate } from '@/api/types';
import { formatNumericDate } from '@/lib/date';
import { plural } from '@/lib/labels';
import { isStudyDay, isWithinSemester, parityOn } from '../../fixtures';
import { currentSettings } from '../settings';
import { buildDraft } from './orders';
import { absenceCovers, affectedLessons, isCovered } from './rules';
import { changesOn, type World } from './world';

const PAIR_FORMS = ['пара', 'пари', 'пар'] as const;

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
  const overdue = pending.filter((c) => c.confirmDeadline && Date.parse(c.confirmDeadline) <= now.getTime());
  const changesHref = `/admin/changes?date=${date}`;
  // рівно ті види, тексти й посилання, що й у core (order.internal.dashboard.Dashboard)
  const alerts: DashboardAlert[] = [];
  if (!isWithinSemester(date)) {
    alerts.push({ kind: 'NO_SEMESTER', severity: 'WARN', message: `Дата ${formatNumericDate(date)} не входить у жоден семестр.`, link: '/admin/calendar' });
  }
  for (const change of changes.filter((c) => c.status === 'DECLINED')) {
    const pairs = change.pairFrom === change.pairTo ? String(change.pairFrom) : `${change.pairFrom}-${change.pairTo}`;
    alerts.push({
      kind: 'DECLINED',
      severity: 'ERROR',
      message: `${change.new.teacher?.displayName ?? 'Викладач'} відмовився від заміни: ${change.group.code}, ${pairs} пара`,
      link: changesHref,
    });
  }
  if (uncovered.length > 0) {
    alerts.push({
      kind: 'UNCOVERED_LESSONS',
      severity: 'WARN',
      message: `${uncovered.length} ${plural(uncovered.length, PAIR_FORMS)} без рішення через відсутність викладачів`,
      link: changesHref,
    });
  }
  if (overdue.length > 0) {
    alerts.push({
      kind: 'PENDING_OVERDUE',
      severity: 'WARN',
      message: `${overdue.length} ${plural(overdue.length, ['заміна', 'заміни', 'замін'])} довше встановленого часу чекають підтвердження`,
      link: changesHref,
    });
  }
  if (confirmed.length > 0) {
    alerts.push({
      kind: 'ORDER_NOT_PUBLISHED',
      severity: 'INFO',
      message: `${confirmed.length} ${plural(confirmed.length, ['підтверджену зміну', 'підтверджені зміни', 'підтверджених змін'])} ще не опубліковано в розпорядженні`,
      link: `/admin/orders?date=${date}`,
    });
  }

  return {
    date,
    // поза семестром core парності не вигадує
    parity: isWithinSemester(date) ? parityOn(date) : null,
    absences: new Set(absences.map((a) => a.teacher.id)).size,
    uncoveredLessons: uncovered.length,
    pending: pending.length,
    confirmed: confirmed.length,
    declined: declined.length,
    orderDraft: !draft || (published && draftRows === 0) ? null : { id: draft.id, itemCount: draftRows },
    generation: null,
    // core завжди надсилає null: витрати ШІ рахує importer
    aiSpendToday: null,
    alerts,
  };
}
