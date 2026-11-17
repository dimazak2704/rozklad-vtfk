import type { UseQueryResult } from '@tanstack/react-query';
import { Archive, RotateCcw, WifiOff } from 'lucide-react';
import { useMemo } from 'react';
import { buildUrl } from '@/api/client';
import { isApiError } from '@/api/errors';
import { useBells, usePublicHistoryFrom } from '@/api/meta';
import { publicSchedulePath, useBellProfiles } from '@/api/public';
import { savedCopyTimeLabel, useSavedCopy } from '@/api/saved-copy';
import type { PracticeDto, ScheduleDto } from '@/api/types';
import {
  blockTitle,
  dayPractices,
  DayList,
  GridNamesProvider,
  noteWithoutPractices,
  NowIndicator,
  supervisorsLine,
  useArrivedChanges,
  useNavTransition,
  WeekGrid,
} from '@/components/schedule';
import { Button, EmptyState, ErrorState, SegmentedControl } from '@/components/ui';
import { useHotkeys } from '@/hooks/useHotkeys';
import { useMediaQuery } from '@/hooks/useMediaQuery';
import { bellsByWeekday } from '@/lib/bells';
import { cn } from '@/lib/cn';
import { addDays, compareIsoDates, formatDateRange, formatDayMonth, shiftStudyDay, startOfWeek } from '@/lib/date';
import type { Perspective } from '@/lib/schedule';
import { useEntrance } from '../entrance';
import { historyBound, isHistoryClosed, SCHEDULE_HISTORY_HINT } from '../history';
import { SavedCopyNote } from '../SavedCopyNote';
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
/** Від 1024 px тиждень — сітка, вужче — дні списком. */
const WIDE_QUERY = '(min-width: 1024px)';

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
  // гість гортає лише до тижня `publicHistoryFrom`; персоналу межі немає
  const historyFrom = usePublicHistoryFrom();
  useHotkeys([
    { keys: 'd', handler: () => setView('day'), description: 'Подання «День»', group: 'Розклад', enabled: hotkeys && !phone },
    { keys: 'w', handler: () => setView('week'), description: 'Подання «Тиждень»', group: 'Розклад', enabled: hotkeys && !phone },
  ]);
  if (phone) return null;
  return (
    <>
      <SegmentedControl<ScheduleView> aria-label="Подання розкладу" value={view} onValueChange={setView} options={VIEW_OPTIONS} />
      <WeekNav value={date} onChange={setDate} today={today} hotkeys={hotkeys} minDate={historyFrom} />
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
  const historyFrom = usePublicHistoryFrom();
  return (
    <div className="flex flex-col items-start gap-0.5">
      <div className="flex w-full items-center justify-between gap-2">
        <WeekNav value={date} onChange={setDate} today={today} returnLink="none" compact minDate={historyFrom} className="-ms-2" />
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
  const historyFrom = usePublicHistoryFrom();
  // сітка або дні списком — що одне видно, те й рендеримо: «тік» часу не перемальовує прихований варіант
  const wide = useMediaQuery(WIDE_QUERY);
  const bells = useBells(date);
  // дзвінки профілю, що діє, за днями тижня: тиждень показує пʼятничний час пʼятниці (4 пара о 12:20, а не 12:10)
  const profiles = useBellProfiles();
  const weekBells = useMemo(
    () => bellsByWeekday(bells.data?.profile.id, profiles.data?.profiles),
    [bells.data?.profile.id, profiles.data?.profiles],
  );
  const schedule = useMemo(() => {
    if (!query.data) return undefined;
    const filtered = filterBySubgroup(query.data, subgroup);
    return select ? select(filtered) : filtered;
  }, [query.data, subgroup, select]);
  const placeholder = query.isPlaceholderData;
  const changes = useMemo(() => (placeholder ? [] : changedLessons(schedule)), [schedule, placeholder]);
  const day = placeholder ? undefined : dayOf(schedule, date);
  const slots = bells.data?.slots;
  // зміни, що прийшли, поки людина дивиться на цей тиждень (без підгрупового фільтра й placeholder)
  const arrived = useArrivedChanges(placeholder ? undefined : query.data);
  // день чи тиждень входить спокійно: ковзає на 8 px у бік навігації; після скелета — проявляється
  const navKey = view === 'day' ? (day ? { view, at: day.date } : null) : schedule ? { view, at: schedule.from } : null;
  const enterRef = useNavTransition<HTMLDivElement>(navKey);
  // перший показ (і кожна інша група, бо сторінка групи монтується наново): стовпці тижня чи рядки дня проявляються по черзі
  useEntrance(enterRef, navKey !== null && !placeholder);

  const openDay = (next: string) => {
    setDate(next);
    if (view !== 'day') setView('day');
  };

  // без мережі запит цього тижня стоїть на паузі: дані на екрані (якщо є) — від іншого тижня, їх не показуємо
  const unavailableOffline = query.fetchStatus === 'paused' && (!schedule || placeholder);
  // сервер не відповів за 5 с чи недоступний, а Service Worker віддав збережену копію: кажемо про це й онлайн
  const owner = placeholder ? undefined : query.data?.owner;
  const savedCopy = useSavedCopy(
    owner ? buildUrl(publicSchedulePath(owner.type, owner.id), { from: week.from, to: week.to }) : undefined,
  );
  const showSavedNote = !unavailableOffline && Boolean(schedule) && (!online || savedCopy.fromCache);
  const savedWhat =
    savedCopy.fromCache && savedCopy.savedAt !== null
      ? `розклад, збережений ${savedCopyTimeLabel(savedCopy.savedAt)}`
      : 'збережений розклад';

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
    if (isHistoryClosed(query.error)) {
      // не збій: гостю давній розклад не показують — пояснюємо й ведемо до найранішого відкритого тижня
      const bound = historyBound(query.error, historyFrom);
      const firstWeek = bound ? startOfWeek(bound) : null;
      content = (
        <EmptyState
          icon={<Archive aria-hidden="true" className="size-5" />}
          title={view === 'day' ? 'Цей день уже в архіві' : 'Цей тиждень уже в архіві'}
          description={
            firstWeek
              ? `Без входу на сайті видно розклад з тижня ${formatDateRange(firstWeek, addDays(firstWeek, 4))} і на всі наступні. ${SCHEDULE_HISTORY_HINT}`
              : `Без входу на сайті видно розклад лише за останні тижні й на майбутнє. ${SCHEDULE_HISTORY_HINT}`
          }
          // «До цього тижня» вже є під перемикачем тижня: тут — лише шлях до найранішого відкритого
          action={
            <Button variant="secondary" onClick={() => setDate(firstWeek ?? homeDate)}>
              {firstWeek ? 'Найраніший доступний тиждень' : 'До поточного тижня'}
            </Button>
          }
        />
      );
    } else if (query.isError) {
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
    // причина з календаря важливіша за загальну здогадку: спершу блоки (практика, НС — «{Вид}: назва, ауд.» з
    // керівниками), далі решта приміток дня без їхніх назв (канікули)
    const blocks = new Map<string, PracticeDto>();
    for (const d of schedule.days) for (const block of dayPractices(d, perspective)) blocks.set(block.periodId, block);
    // до якого числа триває блок — студент має знати, коли пари повернуться
    const blockLines = [...blocks.values()].map((block) => {
      const supervisors = perspective === 'group' ? supervisorsLine(block) : null;
      const title = `${blockTitle(block, perspective)}, до ${formatDayMonth(block.endsOn)}`;
      return supervisors ? `${title}. ${supervisors}` : title;
    });
    const lastBlockDay = [...blocks.values()].map((block) => block.endsOn).sort(compareIsoDates).at(-1);
    // наступний будній день після блоку — лише для групи (керівник має й інші пари): «Заняття з 30 листопада»
    const backOn = perspective === 'group' && lastBlockDay ? shiftStudyDay(lastBlockDay, 1) : null;
    const dayNotes = schedule.days.map((d) => noteWithoutPractices(d, dayPractices(d, perspective))?.trim().replace(/\.$/, ''));
    const notes = [...new Set([...blockLines, ...dayNotes.filter((note): note is string => Boolean(note))])];
    // у поданні «День» — про день, а не про тиждень
    const when = view === 'day' ? (date === today ? 'Сьогодні' : 'Цього дня') : 'Цього тижня';
    content = (
      <EmptyState
        title={noStudy ? `${when} занять немає` : `У ${ownerLabel} ${when.toLocaleLowerCase('uk')} пар немає`}
        description={
          notes.length > 0
            ? `${notes.join('; ')}.${backOn ? ` Заняття з ${formatDayMonth(backOn)}.` : ''} Розклад на інші ${view === 'day' ? 'дні' : 'тижні'} відкривається стрілками або календарем.`
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
    // пар цього дня немає — про це вже каже список («Цього дня пар немає.»), тож рядок стану не повторює його
    const live = Boolean(day && day.studyDay && day.date === today && slots && slots.length > 0 && activePairs(day).length > 0);
    // на сторінці розкладу головне — код групи, тож стан дня — один тихий рядок на всіх ширинах;
    // великий відлік лишається для головної та «Дзвінків»
    content = (
      <div className="grid items-start gap-x-12 gap-y-6 lg:grid-cols-[minmax(0,1fr)_17rem]">
        <div className="flex min-w-0 flex-col gap-5">
          {live && day && slots ? <NowIndicator variant="inline" bells={slots} lessonPairs={activePairs(day)} date={day.date} /> : null}
          {day ? (
            <DayList day={day} bells={slots} perspective={perspective} today={today} showHeader={false} arrived={arrived} />
          ) : (
            <ScheduleSkeleton view="day" />
          )}
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
        {wide ? (
          <WeekGrid
            schedule={schedule}
            perspective={perspective}
            today={today}
            stickyTop="var(--header-height)"
            arrived={arrived}
            bells={weekBells}
          />
        ) : (
          <div className="flex flex-col gap-8">
            {schedule.days.map((d) => (
              <DayList
                key={d.date}
                day={d}
                bells={weekBells?.get(d.weekday)}
                perspective={perspective}
                today={today}
                arrived={arrived}
              />
            ))}
          </div>
        )}
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

      {showSavedNote ? (
        <SavedCopyNote online={online} onRefresh={() => void query.refetch()}>
          Показано {savedWhat} — заміни могли змінитися.
        </SavedCopyNote>
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
        <div ref={enterRef}>
          <GridNamesProvider days={schedule?.days}>{content}</GridNamesProvider>
        </div>
      </div>
    </section>
  );
}
