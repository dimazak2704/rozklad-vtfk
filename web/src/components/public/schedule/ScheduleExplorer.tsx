import type { UseQueryResult } from '@tanstack/react-query';
import { CalendarDays, CalendarRange, RotateCcw, WifiOff } from 'lucide-react';
import { useMemo } from 'react';
import { isApiError } from '@/api/errors';
import { useBells } from '@/api/meta';
import type { ScheduleDto } from '@/api/types';
import { DayList, NowIndicator, WeekGrid } from '@/components/schedule';
import { Button, DateNav, EmptyState, ErrorState, Panel, SegmentedControl } from '@/components/ui';
import { useHotkeys } from '@/hooks/useHotkeys';
import { cn } from '@/lib/cn';
import type { Perspective } from '@/lib/schedule';
import { useOnline } from '../useOnline';
import { BellsCompact } from './BellsCompact';
import { ChangesStrip } from './ChangesStrip';
import type { ScheduleParams, ScheduleView } from './schedule-params';
import { activePairs, changedLessons, dayOf, filterBySubgroup } from './schedule-utils';
import { ScheduleSkeleton } from './ScheduleSkeleton';
import { WeekDayTabs } from './WeekDayTabs';
import { WeekNav } from './WeekNav';

interface ScheduleExplorerProps {
  query: UseQueryResult<ScheduleDto>;
  params: ScheduleParams;
  perspective: Perspective;
  /** Чий розклад — для повідомлень: «4КІ1», «Кренцін», «ауд. 301». */
  ownerLabel: string;
  /** Підгрупи групи (для фільтра «Уся група · 1 п/г · 2 п/г»). */
  subgroups?: readonly number[];
}

const VIEW_OPTIONS = [
  { value: 'day' as const, label: 'День', icon: <CalendarDays aria-hidden="true" className="size-4" /> },
  { value: 'week' as const, label: 'Тиждень', icon: <CalendarRange aria-hidden="true" className="size-4" /> },
];

/**
 * Розклад групи, викладача чи аудиторії: «День / Тиждень», навігація датами, фільтр підгрупи,
 * «зараз», зміни тижня. Стан — в адресі (useScheduleParams), дані — з переданого запиту.
 */
export function ScheduleExplorer({ query, params, perspective, ownerLabel, subgroups = [] }: ScheduleExplorerProps) {
  const { date, today, homeDate, view, subgroup, week, setDate, setView, setSubgroup } = params;
  const online = useOnline();
  const bells = useBells(date);
  const schedule = useMemo(() => (query.data ? filterBySubgroup(query.data, subgroup) : undefined), [query.data, subgroup]);
  const placeholder = query.isPlaceholderData;
  const changes = useMemo(() => (placeholder ? [] : changedLessons(schedule)), [schedule, placeholder]);
  const day = placeholder ? undefined : dayOf(schedule, date);
  const slots = bells.data?.slots;

  useHotkeys([
    { keys: 'd', handler: () => setView('day'), description: 'Подання «День»', group: 'Розклад' },
    { keys: 'w', handler: () => setView('week'), description: 'Подання «Тиждень»', group: 'Розклад' },
  ]);

  const openDay = (next: string) => {
    setDate(next);
    if (view !== 'day') setView('day');
  };

  let content;
  if (!schedule) {
    if (query.isError) {
      const missing = isApiError(query.error) && query.error.isNotFound;
      content = (
        <ErrorState
          error={query.error}
          title={missing ? 'Розклад не знайдено' : undefined}
          description={missing ? `Сервер не знає розкладу «${ownerLabel}». Можливо, посилання застаріло.` : undefined}
          onRetry={() => void query.refetch()}
          retrying={query.isFetching}
          className="rounded-panel border border-default bg-surface"
        />
      );
    } else {
      content = <ScheduleSkeleton view={view} />;
    }
  } else if (schedule.days.every((d) => d.lessons.length === 0) && !placeholder) {
    const noStudy = schedule.days.every((d) => !d.studyDay);
    content = (
      <EmptyState
        className="rounded-panel border border-default bg-surface"
        title={noStudy ? 'Цього тижня занять немає' : `У ${ownerLabel} цього тижня пар немає`}
        description={
          noStudy
            ? 'Це канікули або дні поза семестром. Розклад на інші тижні відкривається стрілками або календарем.'
            : 'Можливо, практика чи зміни в розкладі. Перевірте інший тиждень або сторінку замін.'
        }
        action={
          homeDate < week.from || homeDate > week.to ? (
            <Button variant="secondary" onClick={() => setDate(homeDate)}>
              До поточного тижня
            </Button>
          ) : undefined
        }
      />
    );
  } else if (view === 'day') {
    content = (
      <div className="grid items-start gap-6 lg:grid-cols-[minmax(0,1fr)_18.5rem]">
        <div className="flex min-w-0 flex-col gap-4">
          {day && day.studyDay && slots && slots.length > 0 ? (
            <NowIndicator
              bells={slots}
              lessonPairs={activePairs(day)}
              date={day.date}
              className="rounded-panel border border-default bg-surface px-4 pt-4 pb-3"
            />
          ) : null}
          {day ? (
            <DayList day={day} bells={slots} perspective={perspective} today={today} className="max-w-3xl" />
          ) : (
            <ScheduleSkeleton view="day" />
          )}
        </div>
        <aside className="flex min-w-0 flex-col gap-4" aria-label="Тиждень і дзвінки">
          <ChangesStrip items={changes} onSelect={openDay} />
          {slots && slots.length > 0 ? (
            <Panel title="Дзвінки цього дня" headingLevel={2} className="hidden lg:block">
              <BellsCompact slots={slots} live={date === today} />
            </Panel>
          ) : null}
        </aside>
      </div>
    );
  } else {
    content = (
      <div className="flex flex-col gap-4">
        <ChangesStrip items={changes} onSelect={openDay} />
        <div className="hidden md:block">
          <WeekGrid schedule={schedule} perspective={perspective} today={today} stickyTop="var(--header-height)" />
        </div>
        <div className="flex flex-col gap-8 md:hidden">
          {schedule.days.map((d) => (
            <DayList key={d.date} day={d} perspective={perspective} today={today} />
          ))}
        </div>
      </div>
    );
  }

  return (
    <section aria-label={`Розклад: ${ownerLabel}`} className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
        <SegmentedControl<ScheduleView> aria-label="Подання розкладу" value={view} onValueChange={setView} options={VIEW_OPTIONS} />
        {view === 'day' ? (
          <DateNav value={date} onChange={setDate} today={today} hotkeys />
        ) : (
          <WeekNav value={date} onChange={setDate} today={today} hotkeys />
        )}
        {subgroups.length > 0 ? (
          <SegmentedControl
            aria-label="Підгрупа"
            value={subgroup === null ? 'all' : String(subgroup)}
            onValueChange={(value) => setSubgroup(value === 'all' ? null : Number(value))}
            options={[
              { value: 'all', label: 'Уся група' },
              ...subgroups.map((n) => ({ value: String(n), label: `${n} п/г`, ariaLabel: `${n} підгрупа` })),
            ]}
            className="lg:ms-auto"
          />
        ) : null}
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

      {!online ? (
        <p className="flex items-start gap-2 rounded-control border border-default bg-sunken px-3 py-2 text-caption text-secondary">
          <WifiOff aria-hidden="true" className="mt-px size-4 shrink-0 text-muted" />
          Немає інтернету. Показано збережений розклад — заміни могли змінитися.
        </p>
      ) : null}

      {query.isError && schedule ? (
        <p role="status" className="flex flex-wrap items-center gap-x-3 gap-y-1 text-caption text-secondary">
          Не вдалося оновити розклад, показано попередні дані.
          <Button
            variant="link"
            size="sm"
            onClick={() => void query.refetch()}
            iconStart={<RotateCcw aria-hidden="true" className="size-3.5" />}
          >
            Оновити
          </Button>
        </p>
      ) : null}

      <div aria-busy={placeholder || undefined} className={cn('transition-opacity duration-150', placeholder && 'opacity-60')}>
        {content}
      </div>
    </section>
  );
}
