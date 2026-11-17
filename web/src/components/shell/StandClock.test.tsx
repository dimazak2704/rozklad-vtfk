import { act, screen, waitFor } from '@testing-library/react';
import { useEffect } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { BellSlotDto } from '@/api/types';
import { useNow } from '@/hooks/useNow';
import { clockState, syncClock } from '@/lib/clock';
import { setMockStandTime } from '@/mocks/handlers/meta';
import { renderWithProviders } from '@/test/render';
import { ClockEpoch, StandClockSync, StandTimeBadge } from './StandClock';

const REAL = '2026-11-14T12:00:00Z'; // субота, 14:00 у Києві: пар немає
const BELLS: BellSlotDto[] = [
  { pair: 1, startsAt: '08:30', endsAt: '09:30' },
  { pair: 2, startsAt: '09:40', endsAt: '10:40' },
];

function Probe() {
  const { clock, status } = useNow(BELLS);
  return (
    <p data-testid="probe">
      {clock.date} {status?.kind === 'in-pair' ? `пара ${status.current.pair}, ще ${status.minutesLeft} хв` : 'без пари'}
    </p>
  );
}

describe('переведений час стенда', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(REAL));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('meta зі standTime переводить годинник: «зараз» — пʼятниця 10:00, 2 пара, і є позначка в шапці', async () => {
    setMockStandTime('2026-11-20T08:00:00Z');
    renderWithProviders(
      <>
        <StandClockSync />
        <StandTimeBadge />
        <Probe />
      </>,
    );

    await waitFor(() =>
      expect(document.querySelector('[data-stand-time] .sr-only')).toHaveTextContent('Час стенда: пт, 20 листопада, 10:00 (тест)'),
    );
    expect(document.querySelector('[data-stand-time]')).toHaveTextContent('пт 10:00 · тест');
    expect(screen.getByTestId('probe')).toHaveTextContent('2026-11-20 пара 2, ще 40 хв');
  });

  it('у звичайному режимі годинник справжній, а позначки немає', async () => {
    renderWithProviders(
      <>
        <StandClockSync />
        <StandTimeBadge />
        <Probe />
      </>,
    );

    await waitFor(() => expect(clockState().standTime).toBe(false));
    expect(screen.getByTestId('probe')).toHaveTextContent('2026-11-14 без пари');
    expect(document.querySelector('[data-stand-time]')).toBeNull();
  });

  it('у шапці сайту й кабінету повний текст залежить від ширини правої групи, а не вікна; у панелі — від 1280 px', () => {
    const real = Date.now();
    act(() => syncClock({ now: '2026-11-20T08:00:00Z', standTime: true }, real, real));
    renderWithProviders(
      <>
        <div data-testid="header">
          <StandTimeBadge expand="tools" />
        </div>
        <div data-testid="admin">
          <StandTimeBadge />
        </div>
      </>,
    );
    const classes = (scope: string, label: string) =>
      screen.getByTestId(scope).querySelector(`[data-stand-label="${label}"]`)!.className.split(/\s+/);
    // кабінет із пʼятьма розділами: повний текст лише коли праворуч є 42 rem, інакше «пт 10:00 · тест»
    expect(classes('header', 'compact')).toEqual(expect.arrayContaining(['sm:inline', '@[42rem]/tools:hidden']));
    expect(classes('header', 'full')).toEqual(expect.arrayContaining(['hidden', '@[42rem]/tools:inline']));
    expect(classes('header', 'full')).not.toContain('xl:inline');
    expect(classes('admin', 'compact')).toContain('xl:hidden');
    expect(classes('admin', 'full')).toContain('xl:inline');
    expect(screen.getByTestId('header').querySelector('[data-stand-label="full"]')).toHaveTextContent(
      'Час стенда: пт, 20 листопада, 10:00 (тест)',
    );
  });

  it('стрибок часу будує сторінку з нуля, щоб «сьогодні» перерахували всі', () => {
    const mounts = vi.fn();
    function Page() {
      useEffect(() => {
        mounts();
      }, []);
      return null;
    }
    renderWithProviders(
      <ClockEpoch>
        <Page />
      </ClockEpoch>,
    );
    expect(mounts).toHaveBeenCalledTimes(1);

    const real = Date.now();
    act(() => syncClock({ now: '2026-11-20T08:00:00Z', standTime: true }, real, real));
    expect(mounts).toHaveBeenCalledTimes(2);

    // дрібна похибка при наступній звірці — без перемальовування
    act(() => syncClock({ now: '2026-11-20T08:00:01Z', standTime: true }, real, real));
    expect(mounts).toHaveBeenCalledTimes(2);
  });
});
