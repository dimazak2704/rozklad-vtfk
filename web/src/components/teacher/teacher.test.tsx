import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import { useState } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { BellSlotDto, PreferenceKind, Weekday } from '@/api/types';
import { teacherNamed } from '@/mocks/fixtures';
import { apiPath, problem } from '@/mocks/kit';
import { server } from '@/mocks/server';
import { renderWithProviders } from '@/test/render';
import { absenceSchema, toAbsenceRequest } from './absence-schema';
import { PreferenceGrid } from './PreferenceGrid';
import { cellKey, changedCells, nextKind, toList, toMap, type CellKey } from './preferences';
import { SubstitutionRequests } from './SubstitutionRequests';

const krentsin = teacherNamed('Кренцін');

describe('SubstitutionRequests', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-11-09T08:05:00Z'));
  });
  afterEach(() => vi.useRealTimers());

  it('підтвердження оптимістичне: картка зникає одразу', async () => {
    const user = userEvent.setup();
    renderWithProviders(<SubstitutionRequests teacherId={krentsin.id} />);
    const cards = await screen.findAllByRole('article');
    expect(cards).toHaveLength(2);
    await user.click(within(cards[0] as HTMLElement).getByRole('button', { name: 'Підтверджую' }));
    expect(screen.getAllByRole('article')).toHaveLength(1);
  });

  it('якщо сервер відмовив — картка повертається', async () => {
    server.use(http.post(apiPath('/me/changes/:id/confirm'), () => problem({ status: 409, detail: 'На цей запит уже відповіли.' })));
    const user = userEvent.setup();
    renderWithProviders(<SubstitutionRequests teacherId={krentsin.id} />);
    const cards = await screen.findAllByRole('article');
    await user.click(within(cards[0] as HTMLElement).getByRole('button', { name: 'Підтверджую' }));
    await waitFor(() => expect(screen.getAllByRole('article')).toHaveLength(2));
  });

  it('без запитів — спокійний порожній стан', async () => {
    server.use(http.get(apiPath('/me/changes'), () => Response.json([])));
    renderWithProviders(<SubstitutionRequests teacherId={krentsin.id} />);
    expect(await screen.findByText(/Нових запитів на заміну немає/)).toBeInTheDocument();
  });
});

describe('побажання', () => {
  it('цикл станів і різниця з збереженим', () => {
    expect([nextKind(null), nextKind('UNWANTED'), nextKind('UNAVAILABLE')]).toEqual(['UNWANTED', 'UNAVAILABLE', null]);
    const saved = toMap([{ weekday: 1, pair: 1, kind: 'UNWANTED', note: 'рано' }]);
    const draft = new Map(saved).set(cellKey(5, 6), 'UNAVAILABLE');
    draft.delete(cellKey(1, 1));
    expect(changedCells(saved, draft)).toBe(2);
    expect(toList(new Map<CellKey, PreferenceKind>([[cellKey(1, 1), 'UNAVAILABLE']]), [{ weekday: 1, pair: 1, kind: 'UNWANTED', note: 'рано' }])).toEqual([
      { weekday: 1, pair: 1, kind: 'UNAVAILABLE', note: 'рано' },
    ]);
  });

  function Harness() {
    const [value, setValue] = useState<Map<CellKey, PreferenceKind>>(new Map());
    const slots = new Map<Weekday, BellSlotDto[]>(
      ([1, 2, 3, 4, 5] as const).map((d) => [d, Array.from({ length: d === 5 ? 6 : 8 }, (_, i) => ({ pair: i + 1, startsAt: '08:30', endsAt: '09:30' }))]),
    );
    return (
      <PreferenceGrid
        value={value}
        slotsByWeekday={slots}
        onCellChange={(key, kind) =>
          setValue((current) => {
            const next = new Map(current);
            if (kind) next.set(key, kind);
            else next.delete(key);
            return next;
          })
        }
      />
    );
  }

  it('сітка: клік, клавіші 1/2/0, стрілки; у пʼятницю немає 7–8 пар', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    const monday1 = screen.getByRole('button', { name: /^Понеділок, 1 пара/ });
    await user.click(monday1);
    expect(monday1).toHaveAccessibleName(/небажано/);
    await user.keyboard('2');
    expect(monday1).toHaveAccessibleName(/неможливо/);
    await user.keyboard('0');
    expect(monday1).toHaveAccessibleName(/без побажань/);
    await user.keyboard('{ArrowRight}{ArrowDown}');
    expect(screen.getByRole('button', { name: /^Вівторок, 2 пара/ })).toHaveFocus();
    await act(async () => user.keyboard('1'));
    expect(screen.getByRole('button', { name: /^Вівторок, 2 пара/ })).toHaveAccessibleName(/небажано/);
    expect(screen.queryByRole('button', { name: /^Пʼятниця, 7 пара/ })).toBeNull();
  });
});

describe('схема відсутності', () => {
  const schema = absenceSchema('2026-11-09');

  it('минула відсутність, кінець раніше початку, порожні пари — помилки; та, що вже триває, — можна', () => {
    const result = schema.safeParse({ dateFrom: '2026-11-06', dateTo: '2026-11-05', wholeDay: false, pairs: [], reasonCategory: 'OTHER', comment: '' });
    expect(result.success).toBe(false);
    const paths = result.error?.issues.map((issue) => issue.path.join('.')).sort();
    // як core (ABSENCE_IN_PAST): минулою є відсутність, що вже закінчилась
    expect(paths).toEqual(['dateTo', 'dateTo', 'pairs']);
    const ongoing = schema.safeParse({ dateFrom: '2026-11-06', dateTo: '2026-11-10', wholeDay: true, pairs: [], reasonCategory: 'SICK_LEAVE', comment: '' });
    expect(ongoing.success).toBe(true);
    expect(schema.safeParse({ dateFrom: '2026-11-09', dateTo: '2026-11-09', wholeDay: true, pairs: [], comment: '' }).success).toBe(false);
  });

  it('у тіло запиту: увесь день — pairs null, коментар без пробілів', () => {
    const values = schema.parse({ dateFrom: '2026-11-10', dateTo: '2026-11-11', wholeDay: false, pairs: [4, 2], reasonCategory: 'SICK_LEAVE', comment: '  ' });
    expect(toAbsenceRequest(values)).toEqual({ dateFrom: '2026-11-10', dateTo: '2026-11-11', pairs: [2, 4], reasonCategory: 'SICK_LEAVE', comment: null });
    expect(toAbsenceRequest({ ...values, wholeDay: true }).pairs).toBeNull();
  });
});
