import type { UseQueryResult } from '@tanstack/react-query';
import { RotateCcw } from 'lucide-react';
import { useBells } from '@/api/meta';
import type { ScheduleDto, WeekParity } from '@/api/types';
import { dayOf } from '@/components/public/schedule/schedule-utils';
import type { ScheduleParams, ScheduleView } from '@/components/public/schedule/schedule-params';
import { ScheduleSkeleton } from '@/components/public/schedule/ScheduleSkeleton';
import { WeekDayTabs } from '@/components/public/schedule/WeekDayTabs';
import { WeekNav } from '@/components/public/schedule/WeekNav';
import { DayList, WeekGrid } from '@/components/schedule';
import { Button, EmptyState, ErrorState, ParityBadge, SegmentedControl } from '@/components/ui';
import { useHotkeys } from '@/hooks/useHotkeys';
import { cn } from '@/lib/cn';

const VIEW_OPTIONS = [
  { value: 'day' as const, label: 'День' },
  { value: 'week' as const, label: 'Тиждень' },
];

interface MyLessonsProps {
  query: UseQueryResult<ScheduleDto>;
  params: ScheduleParams;
  /** Номер навчального тижня (для рядка парності). */
  weekNumber: number | null;
}

/**
 * «Мої пари»: день або тиждень викладача з урахуванням замін. Заміни видно в самих клітинках
 * (слово «Заміна», закреслене старе) — окремою смугою над сіткою їх не повторюємо.
 * Парність і номер тижня — один раз, біля перемикача тижня. Навігатор один на обидва подання — тиждень;
 * у поданні «День» день обирають вкладки Пн–Пт (сьогоднішня позначена в них самих).
 */
export function MyLessons({ query, params, weekNumber }: MyLessonsProps) {
  const { date, today, homeDate, view, week, setDate, setView } = params;
  const bells = useBells(view === 'day' ? date : null);
  const placeholder = query.isPlaceholderData;
  const schedule = query.data;
  const day = placeholder ? undefined : dayOf(schedule, date);
  const parity: WeekParity | null = placeholder ? null : (schedule?.days.find((d) => d.parity)?.parity ?? null);
  const awayFromNow = homeDate < week.from || homeDate > week.to;

  useHotkeys([
    { keys: 'd', handler: () => setView('day'), description: 'Подання «День»', group: 'Розклад' },
    { keys: 'w', handler: () => setView('week'), description: 'Подання «Тиждень»', group: 'Розклад' },
  ]);

  const back = awayFromNow ? (
    <Button variant="secondary" onClick={() => setDate(homeDate)}>
      До поточного тижня
    </Button>
  ) : undefined;

  let content;
  if (!schedule) {
    content = query.isError ? (
      <ErrorState error={query.error} onRetry={() => void query.refetch()} retrying={query.isFetching} />
    ) : (
      <ScheduleSkeleton view={view} />
    );
  } else if (!placeholder && schedule.days.every((d) => d.lessons.length === 0)) {
    const noStudy = schedule.days.every((d) => !d.studyDay);
    content = (
      <EmptyState
        title={noStudy ? 'Цього тижня занять немає.' : 'Цього тижня у вас пар немає.'}
        description={noStudy ? 'Це канікули або дні поза семестром.' : undefined}
        action={back}
      />
    );
  } else if (view === 'day') {
    content = day ? (
      day.studyDay && day.lessons.length === 0 ? (
        <EmptyState title="Цього дня у вас пар немає." />
      ) : (
        <DayList day={day} bells={bells.data?.slots} perspective="teacher" today={today} showHeader={false} />
      )
    ) : (
      <ScheduleSkeleton view="day" />
    );
  } else {
    content = (
      <>
        <div className="hidden md:block">
          <WeekGrid schedule={schedule} perspective="teacher" today={today} stickyTop="var(--header-height)" />
        </div>
        <div className="flex flex-col gap-8 md:hidden">
          {schedule.days.map((d) => (
            <DayList key={d.date} day={d} perspective="teacher" today={today} />
          ))}
        </div>
      </>
    );
  }

  return (
    <section aria-labelledby="my-lessons-heading" className="flex flex-col gap-4">
      <div className="flex flex-wrap items-end justify-between gap-x-6 gap-y-3">
        <div className="flex min-w-0 flex-col gap-0.5">
          <h2 id="my-lessons-heading" className="text-title text-primary">
            Мої пари
          </h2>
          {parity ? <ParityBadge parity={parity} weekNumber={weekNumber} size="sm" className="self-start text-secondary" /> : null}
        </div>
        <div className="flex max-w-full flex-wrap items-center gap-2">
          <SegmentedControl<ScheduleView> aria-label="Подання розкладу" value={view} onValueChange={setView} options={VIEW_OPTIONS} />
          {/* як на публічній сторінці: перемикач завжди гортає тиждень, а день обирають вкладки під ним */}
          <WeekNav value={date} onChange={setDate} today={today} hotkeys />
        </div>
      </div>

      {view === 'day' ? (
        <WeekDayTabs
          weekStart={week.from}
          days={placeholder ? undefined : schedule?.days}
          value={date}
          today={today}
          onChange={setDate}
          className="max-w-3xl"
        />
      ) : null}

      {query.isError && schedule ? (
        <p role="status" className="flex flex-wrap items-center gap-x-3 gap-y-1 text-caption text-secondary">
          Не вдалося оновити розклад, показано попередні дані.
          <Button variant="link" size="sm" onClick={() => void query.refetch()} iconStart={<RotateCcw aria-hidden="true" className="size-3.5" />}>
            Оновити
          </Button>
        </p>
      ) : null}

      <div
        aria-busy={placeholder || undefined}
        className={cn('transition-opacity duration-150', view === 'day' && 'max-w-3xl', placeholder && 'opacity-60')}
      >
        {content}
      </div>
    </section>
  );
}
