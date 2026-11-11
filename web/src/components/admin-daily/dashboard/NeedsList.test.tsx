import { screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AbsenceDto, DashboardAlert } from '@/api/types';
import { FIXTURE_LESSON, FIXTURE_TEACHERS } from '@/mocks/fixtures/lesson-options';
import { renderWithProviders } from '@/test/render';
import type { LessonEntry } from '../model';
import { NeedsList } from './NeedsList';

const absence: AbsenceDto = {
  id: 'a-1',
  teacher: FIXTURE_TEACHERS.krysak,
  dateFrom: '2026-11-09',
  dateTo: '2026-11-09',
  pairs: null,
  reasonCategory: 'SICK_LEAVE',
  comment: null,
  source: 'BOT',
  status: 'ACTIVE',
  createdBy: 'telegram-bot',
  createdAt: '2026-11-09T04:00:00Z',
  version: 0,
};

const entry = (patch: Partial<LessonEntry['item']['lesson']>, state: LessonEntry['state'] = 'uncovered'): LessonEntry => ({
  absence,
  item: { date: '2026-11-09', lesson: { ...FIXTURE_LESSON, ...patch }, decision: null, asSubstitute: false },
  state,
  change: null,
});

const uncoveredAlert: DashboardAlert = { kind: 'UNCOVERED_LESSONS', severity: 'ERROR', message: '2 пари без рішення', link: '/admin/changes' };
const orderAlert: DashboardAlert = { kind: 'ORDER_NOT_PUBLISHED', severity: 'INFO', message: '2 зміни ще не опубліковано', link: '/admin/orders' };

describe('«Що потребує рішення»', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    // 12:00 за Києвом: 1 пара (08:30–09:30) минула, 3 пара (11:50–12:50) йде, 5 пара попереду
    vi.setSystemTime(new Date('2026-11-09T10:00:00Z'));
  });
  afterEach(() => vi.useRealTimers());

  it('пари без рішення за часом: минула, «вже йде», «за …»; сповіщення про ці ж пари не дублюються', () => {
    renderWithProviders(
      <NeedsList
        date="2026-11-09"
        pairsKnown
        calm="—"
        alerts={[uncoveredAlert, orderAlert]}
        entries={[
          entry({ id: 'l5', pair: 5, startsAt: '13:30', endsAt: '14:30' }),
          entry({ id: 'l1', pair: 1, startsAt: '08:30', endsAt: '09:30' }, 'declined'),
          entry({ id: 'l3' }),
          entry({ id: 'l4', pair: 4, startsAt: '13:00', endsAt: '14:00' }, 'confirmed'),
        ]}
      />,
    );
    const links = screen.getAllByRole('link');
    // три пари без рішення за номером і одне сповіщення про розпорядження; підтверджена — не тут
    expect(links).toHaveLength(4);
    expect(links[0]).toHaveTextContent('уже минула');
    expect(links[0]).toHaveTextContent('Відмова — потрібен інший варіант');
    expect(links[1]).toHaveTextContent('вже йде');
    expect(links[2]).toHaveTextContent(/за\s1\sгод\s30\sхв/);
    expect(links[3]).toHaveTextContent('2 зміни ще не опубліковано');
    expect(screen.queryByText('2 пари без рішення')).not.toBeInTheDocument();
    expect(links[1]).toHaveAttribute('href', '/admin/changes?date=2026-11-09&lesson=l3');
  });

  it('коли пара — одразу після слова стану; зламаний сервіс — рядок із переходом у розділ', () => {
    renderWithProviders(
      <NeedsList
        date="2026-11-09"
        pairsKnown
        calm="—"
        alerts={[]}
        serviceAlerts={[{ kind: 'BOT_OFFLINE', severity: 'ERROR', message: 'Telegram-бот не відповідає', link: '/admin/telegram' }]}
        entries={[entry({ id: 'l5', pair: 5, startsAt: '13:30', endsAt: '14:30' })]}
      />,
    );
    const [pair, bot] = screen.getAllByRole('link');
    expect(pair).toHaveTextContent(/Потрібне рішення · за\s1\sгод\s30\sхв/);
    expect(bot).toHaveTextContent('Telegram-бот не відповідає');
    expect(bot).toHaveAttribute('href', '/admin/telegram');
  });

  it('без пар і сповіщень — одне спокійне речення; пари невідомі — сповіщення сервера показуються всі', () => {
    const { unmount } = renderWithProviders(<NeedsList date="2026-11-09" pairsKnown calm="Сьогодні ніхто не відсутній — замін не потрібно." alerts={[]} entries={[]} />);
    expect(screen.getByText('Сьогодні ніхто не відсутній — замін не потрібно.')).toBeInTheDocument();
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
    unmount();

    renderWithProviders(<NeedsList date="2026-11-09" pairsKnown={false} calm="—" alerts={[uncoveredAlert]} entries={[]} />);
    expect(screen.getByRole('link', { name: '2 пари без рішення' })).toBeInTheDocument();
  });
});
