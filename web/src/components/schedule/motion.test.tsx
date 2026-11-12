import { act, render, renderHook, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { LessonView, ScheduleDto } from '@/api/types';
import { BELLS_MON_THU, DEMO_WEEK } from '@/pages/dev/demo-data';
import { renderWithProviders } from '@/test/render';
import { LessonCell } from './LessonCell';
import { LessonDetails } from './LessonDetails';
import { Countdown, NowIndicator } from './NowIndicator';
import { segmentHint } from './track-hint';
import { ARRIVAL_MS, useArrivedChanges } from './useArrivedChanges';
import { enterKeyframes, navEnter, SKELETON_DELAY_MS, useNavTransition, type NavKey } from './useNavTransition';

const monday = DEMO_WEEK.days[0]!;
const substituted = monday.lessons.find((lesson) => lesson.kind === 'SUBSTITUTED')!;
const roomChanged = DEMO_WEEK.days.flatMap((day) => day.lessons).find((lesson) => lesson.kind === 'ROOM_CHANGED')!;

function setReducedMotion(reduce: boolean) {
  vi.spyOn(window, 'matchMedia').mockImplementation(
    (query: string) => ({
      matches: reduce && query.includes('prefers-reduced-motion: reduce'),
      media: query,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    }),
  );
}

afterEach(() => {
  vi.restoreAllMocks();
  vi.useRealTimers();
});

describe('navEnter: як входить новий день чи тиждень', () => {
  const week = (at: string): NavKey => ({ view: 'week', at });

  it('пізніший тиждень ковзає справа, ранiший — зліва', () => {
    expect(navEnter(week('2026-11-09'), week('2026-11-16'))).toEqual({ kind: 'slide', direction: 1 });
    expect(navEnter(week('2026-11-16'), week('2026-11-09'))).toEqual({ kind: 'slide', direction: -1 });
  });

  it('перший показ, той самий день і завантаження без скелета — без руху', () => {
    expect(navEnter(undefined, week('2026-11-09'))).toBeNull();
    expect(navEnter(week('2026-11-09'), week('2026-11-09'))).toBeNull();
    expect(navEnter(week('2026-11-09'), null)).toBeNull();
  });

  it('після видимого скелета чи зміни подання — лише проявлення', () => {
    expect(navEnter(undefined, week('2026-11-09'), true)).toEqual({ kind: 'fade' });
    expect(navEnter(week('2026-11-09'), { view: 'day', at: '2026-11-10' })).toEqual({ kind: 'fade' });
  });

  it('ковзання — лише transform і opacity на 8 px', () => {
    expect(enterKeyframes({ kind: 'slide', direction: 1 })).toEqual([
      { opacity: 0, transform: 'translateX(8px)' },
      { opacity: 1, transform: 'none' },
    ]);
    expect(enterKeyframes({ kind: 'fade' })).toEqual([{ opacity: 0 }, { opacity: 1 }]);
  });
});

describe('useNavTransition', () => {
  function mount(initial: NavKey | null) {
    const animate = vi.fn(() => ({ cancel: vi.fn() }) as unknown as Animation);
    const element = { animate } as unknown as HTMLDivElement;
    const hook = renderHook(
      ({ navKey }: { navKey: NavKey | null }) => {
        const ref = useNavTransition<HTMLDivElement>(navKey);
        ref.current = element;
        return ref;
      },
      { initialProps: { navKey: initial } },
    );
    return { animate, ...hook };
  }

  it('гортання вперед запускає одну анімацію входу справа', () => {
    setReducedMotion(false);
    const { animate, rerender } = mount({ view: 'week', at: '2026-11-09' });
    expect(animate).not.toHaveBeenCalled();
    rerender({ navKey: { view: 'week', at: '2026-11-16' } });
    expect(animate).toHaveBeenCalledTimes(1);
    expect(animate).toHaveBeenCalledWith(enterKeyframes({ kind: 'slide', direction: 1 }), expect.objectContaining({ duration: 200 }));
  });

  it('напрямок рахується від останнього показаного, навіть якщо між ними був скелет', () => {
    setReducedMotion(false);
    const { animate, rerender } = mount({ view: 'day', at: '2026-11-13' });
    rerender({ navKey: null });
    rerender({ navKey: { view: 'day', at: '2026-11-16' } });
    expect(animate).toHaveBeenCalledWith(enterKeyframes({ kind: 'slide', direction: 1 }), expect.anything());
  });

  it('скелет, що висів довше 300 мс, змінюється проявленням', () => {
    setReducedMotion(false);
    vi.useFakeTimers();
    const { animate, rerender } = mount(null);
    vi.advanceTimersByTime(SKELETON_DELAY_MS + 50);
    rerender({ navKey: { view: 'week', at: '2026-11-09' } });
    expect(animate).toHaveBeenCalledWith([{ opacity: 0 }, { opacity: 1 }], expect.objectContaining({ duration: 160 }));
  });

  it('з prefers-reduced-motion нічого не рухається', () => {
    setReducedMotion(true);
    const { animate, rerender } = mount({ view: 'week', at: '2026-11-09' });
    rerender({ navKey: { view: 'week', at: '2026-11-16' } });
    expect(animate).not.toHaveBeenCalled();
  });
});

describe('useArrivedChanges', () => {
  const withLessons = (schedule: ScheduleDto, change: (lesson: LessonView) => LessonView): ScheduleDto => ({
    ...schedule,
    days: schedule.days.map((day) => ({ ...day, lessons: day.lessons.map(change) })),
  });
  const regularId = monday.lessons.find((lesson) => lesson.kind === 'REGULAR')!.id;
  const substitute = (lesson: LessonView): LessonView =>
    lesson.id === regularId ? { ...lesson, kind: 'SUBSTITUTED', changeId: 'change-new', original: { discipline: null, teacher: null, room: null, pair: null } } : lesson;

  it('перше завантаження нічого не позначає', () => {
    const { result } = renderHook(() => useArrivedChanges(DEMO_WEEK));
    expect(result.current.size).toBe(0);
  });

  it('нова зміна в тому самому тижні позначається один раз і знімається за секунду', () => {
    vi.useFakeTimers();
    const { result, rerender } = renderHook(({ schedule }) => useArrivedChanges(schedule), { initialProps: { schedule: DEMO_WEEK } });
    rerender({ schedule: withLessons(DEMO_WEEK, substitute) });
    expect([...result.current]).toEqual([regularId]);
    act(() => {
      vi.advanceTimersByTime(ARRIVAL_MS);
    });
    expect(result.current.size).toBe(0);
  });

  it('повторний запит без змін і завантаження нічого не позначають', () => {
    const { result, rerender } = renderHook<ReadonlySet<string>, { schedule: ScheduleDto | undefined }>(
      ({ schedule }) => useArrivedChanges(schedule),
      { initialProps: { schedule: DEMO_WEEK } },
    );
    rerender({ schedule: { ...DEMO_WEEK } });
    expect(result.current.size).toBe(0);
    rerender({ schedule: undefined });
    expect(result.current.size).toBe(0);
  });

  it('інший тиждень чи інший власник — навігація, а не «прийшла зміна»', () => {
    const { result, rerender } = renderHook(({ schedule }) => useArrivedChanges(schedule), { initialProps: { schedule: DEMO_WEEK } });
    rerender({ schedule: withLessons({ ...DEMO_WEEK, from: '2026-11-16', to: '2026-11-20' }, substitute) });
    expect(result.current.size).toBe(0);
    rerender({ schedule: withLessons({ ...DEMO_WEEK, owner: { ...DEMO_WEEK.owner, id: 'other' } }, substitute) });
    expect(result.current.size).toBe(0);
  });
});

describe('LessonCell: зміна прийшла наживо', () => {
  it('закреслення проводиться, потім проявляються тло й слово стану', () => {
    renderWithProviders(<LessonCell lesson={substituted} arrived />);
    const cell = screen.getByRole('button', { name: /2 пара/ });
    expect(cell.className).toMatch(/anim-wash-in/);
    expect(within(cell).getByText('Заміна').className).toMatch(/anim-word-in/);
    expect(within(cell).getByText('СТП · Кренцін').className).toMatch(/anim-strike/);
  });

  it('інша аудиторія закреслює стару аудиторію', () => {
    renderWithProviders(<LessonCell lesson={roomChanged} arrived />);
    const struck = screen.getByRole('button').querySelector('s');
    expect(struck?.className).toMatch(/anim-strike/);
  });

  it('без позначки нічого не рухається', () => {
    renderWithProviders(<LessonCell lesson={substituted} />);
    const cell = screen.getByRole('button', { name: /2 пара/ });
    expect(cell.outerHTML).not.toMatch(/anim-(wash-in|word-in|strike)/);
  });
});

describe('Countdown', () => {
  it('рухаються лише цифри, що змінилися: стара йде вгору, нова заходить знизу', () => {
    const { container, rerender } = render(<Countdown value={23} />);
    expect(container.querySelector('[data-leaving]')).toBeNull();
    rerender(<Countdown value={22} />);
    const leaving = container.querySelectorAll('[data-leaving]');
    expect(leaving).toHaveLength(1);
    expect(leaving[0]!.textContent).toBe('3');
    expect(leaving[0]!.className).toMatch(/anim-digit-out/);
    // десятки не змінилися — не рухаються й не мають цифри, що йде
    expect(container.textContent).toBe('223');
  });
});

describe('Деталі при наведенні', () => {
  it('смуга доріжки дня називає пару, час і стан', () => {
    const slot = { pair: 3, startsAt: '11:00', endsAt: '12:00' };
    expect(segmentHint(slot, 'current')).toBe('3 пара · 11:00–12:00 · іде зараз');
    expect(segmentHint(slot, 'past')).toBe('3 пара · 11:00–12:00 · минула');
    expect(segmentHint(slot, 'future')).toBe('3 пара · 11:00–12:00');
    expect(segmentHint(slot, 'future', true)).toBe('3 пара · 11:00–12:00 · пари немає');
    // вікно в минулому — не «минула»: пари не було, а не вона відбулася
    expect(segmentHint(slot, 'past', true)).toBe('3 пара · 11:00–12:00 · пари не було');
    // вікно, що триває, — теж не «іде зараз»
    expect(segmentHint(slot, 'current', true)).toBe('3 пара · 11:00–12:00 · пари немає');
  });

  it('головна з запамʼятованою групою: доріжка загальна, а пари, якої в групи не було, — порожня пунктирна смуга', () => {
    // 4КІ1 без першої пари; зараз 10:00, тобто 2 пара (09:40–10:40)
    renderWithProviders(<NowIndicator bells={BELLS_MON_THU} groupPairs={[2, 3]} at={10 * 60} variant="strip" />);
    const segments = [...document.querySelectorAll<HTMLElement>('[data-track-segment] > span')];
    // відлік і доріжка — за дзвінками коледжу: усі пари дня, а не лише пари групи
    expect(segments).toHaveLength(BELLS_MON_THU.length);
    expect(segments[0]!.className).toMatch(/border-dashed/);
    expect(segments[0]!.className).not.toMatch(/fill-track-past/);
    expect(segments[1]!.className).toMatch(/bg-selected/);
    expect(segments[2]!.className).not.toMatch(/border-dashed/);
  });

  it('картка заняття: повна назва, хто, де, коли, що було й чому — без повтору «ауд. · аудиторія»', () => {
    renderWithProviders(<LessonDetails lesson={substituted} date={monday.date} dayParity="NUMERATOR" />);
    expect(screen.getByText('Надійність, діагностика та експлуатація компʼютерних систем і мереж')).toBeInTheDocument();
    expect(screen.getByText('Веде')).toBeInTheDocument();
    expect(screen.getByText('Було')).toBeInTheDocument();
    expect(screen.getByText('взаємна заміна')).toBeInTheDocument();
    if (substituted.room?.type === 'CLASSROOM') expect(screen.queryByText(/· аудиторія/)).not.toBeInTheDocument();
  });
});
