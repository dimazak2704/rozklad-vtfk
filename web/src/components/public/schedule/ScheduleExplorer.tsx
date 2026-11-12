import type { UseQueryResult } from '@tanstack/react-query';
import { RotateCcw, WifiOff } from 'lucide-react';
import { useMemo } from 'react';
import { isApiError } from '@/api/errors';
import { useBells } from '@/api/meta';
import type { ScheduleDto } from '@/api/types';
import { DayList, NowIndicator, WeekGrid } from '@/components/schedule';
import { Button, EmptyState, ErrorState, SegmentedControl } from '@/components/ui';
import { useHotkeys } from '@/hooks/useHotkeys';
import { useMediaQuery } from '@/hooks/useMediaQuery';
import { cn } from '@/lib/cn';
import type { Perspective } from '@/lib/schedule';
import { useOnline } from '../useOnline';
import { BellsCompact } from './BellsCompact';
import { ChangesNote, WEEK_REGION_ID } from './ChangesNote';
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
  /**
   * Власний рядок керувань над розкладом («День / Тиждень», тиждень, заміни тижня).
   * `false` — сторінка ставить `ScheduleControls` у свій заголовок (PageHeader), а фільтр підгрупи — у «⋯».
   */
  toolbar?: boolean;
  /** Перетворення розкладу лише для показу (аудиторія ховає саму себе з клітинок). */
  select?: (schedule: ScheduleDto) => ScheduleDto;
}

/** Телефон: тиждень і подання живуть в одному рядку над днями, а не трьома рядками керувань. */
export const PHONE_QUERY = '(max-width: 639.98px)';

/**
 * «День / Тиждень» і перемикач тижня — два керування для заголовка сторінки (§7: не більше двох).
 * Підгрупа — не тут, а в «⋯» сторінки: її обирають раз, і вона запамʼятовується.
 */
export function ScheduleControls({
  params,
  hotkeys = true,
  phone = false,
}: {
  params: ScheduleParams;
  hotkeys?: boolean;
  /** На телефоні в заголовку керувань немає: тиждень і подання — одним рядком над днями (`PhoneWeekBar`). */
  phone?: boolean;
}) {
  const { date, today, view, setView, setDate } = params;
  useHotkeys([
    { keys: 'd', handler: () => setView('day'), description: 'Подання «День»', group: 'Розклад', enabled: hotkeys && !phone },
    { keys: 'w', handler: () => setView('week'), description: 'Подання «Тиждень»', group: 'Розклад', enabled: hotkeys && !phone },
  ]);
  if (phone) return null;
  return (
    <>
      <SegmentedControl<ScheduleView> aria-label="Подання розкладу" value={view} onValueChange={setView} options={VIEW_OPTIONS} />
      <WeekNav value={date} onChange={setDate} today={today} hotkeys={hotkeys} />
    </>
  );
}

/**
 * Телефон: «‹ 9–13 лист. ›» і «День / Тиждень» в одному рядку, під ним — дні тижня. Обидва слова видно:
 * людина бачить, що це перемикач, а не підпис. Інший тиждень — тихе «До цього тижня» під рядком.
 */
function PhoneWeekBar({ params }: { params: ScheduleParams }) {
  const { date, today, homeDate, view, week, setDate, setView } = params;
  const offWeek = homeDate < week.from || homeDate > week.to;
  return (
    <div className="flex flex-col items-start gap-0.5">
      <div className="flex w-full items-center justify-between gap-2">
        <WeekNav value={date} onChange={setDate} today={today} returnLink="none" compact className="-ms-2" />
        <SegmentedControl<ScheduleView>
          size="sm"
          aria-label="Подання розкладу"
          value={view}
          onValueChange={setView}
          options={VIEW_OPTIONS}
          className="shrink-0 [&>button]:px-2.5"
        />
      </div>
      {offWeek ? (
        <Button variant="link" size="sm" onClick={() => setDate(homeDate)}>
          До цього тижня
        </Button>
      ) : null}
    </div>
  );
}

const VIEW_OPTIONS = [
  { value: 'day' as const, label: 'День' },
  { value: 'week' as const, label: 'Тиждень' },
];

/**
 * Розклад групи, викладача чи аудиторії: «День / Тиждень», навігація датами, фільтр підгрупи,
 * «зараз», зміни тижня. Стан — в адресі (useScheduleParams), дані — з переданого запиту.
 */
export function ScheduleExplorer({ query, params, perspective, ownerLabel, toolbar = true, select }: ScheduleExplorerProps) {
  const { date, today, homeDate, view, subgroup, week, setDate, setView } = params;
  const online = useOnline();
  const phone = useMediaQuery(PHONE_QUERY);
  const bells = useBells(date);
  const schedule = useMemo(() => {
    if (!query.data) return undefined;
    const filtered = filterBySubgroup(query.data, subgroup);
    return select ? select(filtered) : filtered;
  }, [query.data, subgroup, select]);
  const placeholder = query.isPlaceholderData;
  const changes = useMemo(() => (placeholder ? [] : changedLessons(schedule)), [schedule, placeholder]);
  const day = placeholder ? undefined : dayOf(schedule, date);
  const slots = bells.data?.slots;

  const openDay = (next: string) => {
    setDate(next);
    if (view !== 'day') setView('day');
  };

  // без мережі запит цього тижня стоїть на паузі: дані на екрані (якщо є) — від іншого тижня, їх не показуємо
  const unavailableOffline = query.fetchStatus === 'paused' && (!schedule || placeholder);

  let content;
  if (unavailableOffline) {
    content = (
      <EmptyState
        icon={<WifiOff aria-hidden="true" className="size-5" />}
        title={view === 'day' ? 'Цей день не збережено на пристрої' : 'Цей тиждень не збережено на пристрої'}
        description="Без інтернету відкриваються лише ті тижні, які ви вже переглядали. Підключіться до мережі — розклад завантажиться сам."
        action={
          homeDate < week.from || homeDate > week.to ? (
            <Button variant="secondary" onClick={() => setDate(homeDate)}>
              До поточного тижня
            </Button>
          ) : undefined
        }
      />
    );
  } else if (!schedule) {
    if (query.isError) {
      const missing = isApiError(query.error) && query.error.isNotFound;
      content = (
        <ErrorState
          error={query.error}
          title={missing ? 'Розклад не знайдено' : undefined}
          description={missing ? `Сервер не знає розкладу «${ownerLabel}». Можливо, посилання застаріло.` : undefined}
          onRetry={() => void query.refetch()}
          retrying={query.isFetching}
        />
      );
    } else {
      content = <ScheduleSkeleton view={view} />;
    }
  } else if (schedule.days.every((d) => d.lessons.length === 0) && !placeholder) {
    const noStudy = schedule.days.every((d) => !d.studyDay);
    // причина з календаря (практика групи, канікули) важливіша за загальну здогадку
    const notes = [...new Set(schedule.days.map((d) => d.note?.trim().replace(/\.$/, '')).filter((note): note is string => Boolean(note)))];
    content = (
      <EmptyState
        title={noStudy ? 'Цього тижня занять немає' : `У ${ownerLabel} цього тижня пар немає`}
        description={
          notes.length > 0
            ? `${notes.join('; ')}. Розклад на інші тижні відкривається стрілками або календарем.`
            : noStudy
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
    const live = Boolean(day && day.studyDay && day.date === today && slots && slots.length > 0);
    // на сторінці розкладу головне — код групи, тож стан дня — один тихий рядок на всіх ширинах;
    // великий відлік лишається для головної та «Дзвінків»
    content = (
      <div className="grid items-start gap-x-12 gap-y-6 lg:grid-cols-[minmax(0,1fr)_17rem]">
        <div className="flex min-w-0 flex-col gap-5">
          {live && day && slots ? <NowIndicator variant="inline" bells={slots} lessonPairs={activePairs(day)} date={day.date} /> : null}
          {day ? <DayList day={day} bells={slots} perspective={perspective} today={today} showHeader={false} /> : <ScheduleSkeleton view="day" />}
        </div>
        {slots && slots.length > 0 ? (
          <section aria-labelledby="day-bells-heading" className="hidden flex-col gap-2 lg:flex">
            <h2 id="day-bells-heading" className="text-ui font-semibold text-secondary">
              Дзвінки цього дня
            </h2>
            <BellsCompact slots={slots} live={date === today} showBreaks={false} />
          </section>
        ) : null}
      </div>
    );
  } else {
    content = (
      <div id={WEEK_REGION_ID}>
        <div className="hidden lg:block">
          <WeekGrid schedule={schedule} perspective={perspective} today={today} stickyTop="var(--header-height)" />
        </div>
        <div className="flex flex-col gap-8 lg:hidden">
          {schedule.days.map((d) => (
            <DayList key={d.date} day={d} perspective={perspective} today={today} />
          ))}
        </div>
      </div>
    );
  }

  return (
    <section aria-label={`Розклад: ${ownerLabel}`} className="flex flex-col gap-4">
      {toolbar ? (
        <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
          <ScheduleControls params={params} />
          <ChangesNote items={changes} view={view} onOpenDay={openDay} className="lg:ms-auto" />
        </div>
      ) : null}

      {phone && !toolbar ? <PhoneWeekBar params={params} /> : null}

      {view === 'day' ? (
        <WeekDayTabs
          weekStart={week.from}
          days={placeholder ? undefined : schedule?.days}
          value={date}
          today={today}
          onChange={setDate}
        />
      ) : null}

      {!online && !unavailableOffline ? (
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

      <div
        aria-busy={(placeholder && !unavailableOffline) || undefined}
        className={cn('transition-opacity duration-150', placeholder && !unavailableOffline && 'opacity-60')}
      >
        {content}
      </div>
    </section>
  );
}
