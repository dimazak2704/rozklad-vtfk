import { Fragment, type ReactNode } from 'react';
import { Link } from 'react-router';
import { useBells, usePublicMeta } from '@/api/meta';
import { usePublicSchedule } from '@/api/public';
import type { DayDto, IsoDate, LessonView } from '@/api/types';
import { activePairs } from '@/components/public/schedule/schedule-utils';
import { NowIndicator } from '@/components/schedule';
import { ErrorState, ParityBadge, Skeleton, useErrorCovered } from '@/components/ui';
import { useNow } from '@/hooks/useNow';
import { cn } from '@/lib/cn';
import { compareIsoDates, formatLongDate, formatWeekdayDate, formatWeekdayDateAfterNa, relativeDayLabel } from '@/lib/date';
import { capitalize, LESSON_VIEW_KIND_LABEL } from '@/lib/labels';
import { LESSON_TONE, roomLabel } from '@/lib/schedule';
import type { ScheduleTarget } from '@/stores/favorites';
import { useStalled } from '../useStalled';
import { groupFocusPlan } from './group-focus';
import { nowPanelRange } from './now-range';
import { useRememberedGroup } from './useRememberedGroup';

/** Тонований блок героя — і для завантаження, і для спокійних станів без доріжки. */
const HERO = 'flex flex-col gap-2 rounded-[20px] bg-today-wash p-5 md:rounded-hero md:px-10 md:py-8';
const DISPLAY = 'font-display text-[2.125rem] leading-[2.375rem] font-semibold tracking-[-0.025em] text-primary md:text-display';

/** «Прогр., ауд. 301, Стець» — заняття одним рядком, без зайвих розділювачів. */
function lessonLine(lesson: LessonView): string {
  return [lesson.discipline.shortName, roomLabel(lesson.room?.name), lesson.teacher?.shortName].filter(Boolean).join(', ');
}

function firstLesson(day: DayDto | undefined): LessonView | undefined {
  return day?.lessons.filter((l) => l.kind !== 'CANCELLED').sort((a, b) => a.pair - b.pair)[0];
}

/**
 * Силует героя тієї ж висоти, що й готовий герой посеред пари (рядок дати 28 px, відлік з реченням 96 px,
 * доріжка 56 px; на телефоні — відлік у два рядки й смуга пари): пошук і списки під ним не стрибають,
 * коли приходять дані. Із запамʼятованою групою на телефоні під відліком є ще її блок — тримаємо і його місце
 * (на десктопі він стоїть праворуч від відліку й висоти не додає).
 */
function HeroSkeleton({ dateLine, withFocus, className }: { dateLine?: ReactNode; withFocus?: boolean; className?: string }) {
  return (
    <section
      aria-label="Стан навчального дня"
      aria-busy="true"
      className={cn('flex flex-col gap-5 rounded-[20px] bg-today-wash p-5 md:gap-7 md:rounded-hero md:px-10 md:py-8', className)}
    >
      <div className="flex flex-col gap-2">
        {dateLine ? (
          <p className="flex h-7 items-center text-body text-secondary">{dateLine}</p>
        ) : (
          <span className="flex h-7 items-center">
            <Skeleton className="h-5 w-56" />
          </span>
        )}
        <span className="flex h-[6.375rem] flex-col justify-end gap-3 md:h-24 md:justify-center">
          <Skeleton className="h-12 w-72 max-w-full md:h-16" />
          <Skeleton className="hidden h-5 w-80 max-w-full md:block" />
        </span>
        {withFocus ? (
          <span className="mt-2 md:hidden">
            <FocusSkeleton />
          </span>
        ) : null}
      </div>
      <span className="hidden h-14 items-center md:flex">
        <Skeleton className="h-2 w-full" />
      </span>
      <span className="flex h-16 flex-col justify-center gap-3 md:hidden">
        <Skeleton className="h-2 w-full" />
        <Skeleton className="h-4 w-2/3" />
      </span>
    </section>
  );
}

/** Група одним посиланням у реченні героя. */
function GroupLink({ group }: { group: ScheduleTarget }) {
  return (
    <Link
      to={group.url}
      className="rounded-tag font-[650] text-accent focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus"
    >
      <span className="u-line">{group.label}</span>
    </Link>
  );
}

/** Аудиторія й викладач заняття: «ауд. 16 · Крисак Л.В.»; номер аудиторії — головне, чорнилом. */
const STATE_TONE = { change: 'text-change', add: 'text-add', room: 'text-room' } as const;

function placeLine(lesson: LessonView): ReactNode {
  const room = roomLabel(lesson.room?.name);
  const roomNode = room?.startsWith('ауд.') ? (
    <>
      ауд.&nbsp;<span className="font-[650] text-primary figures">{room.replace(/^ауд\.\u00a0/, '')}</span>
    </>
  ) : (
    room
  );
  const tone = LESSON_TONE[lesson.kind];
  // заміна чи інша аудиторія — словом у кольорі стану на початку рядка: свою зміну видно одразу
  const state =
    tone === 'change' || tone === 'add' || tone === 'room' ? (
      <span className={cn('font-[650]', STATE_TONE[tone])}>{LESSON_VIEW_KIND_LABEL[lesson.kind]}</span>
    ) : null;
  const parts: ReactNode[] = [state, lesson.subgroup > 0 ? `${lesson.subgroup}\u00a0п/г` : null, roomNode, lesson.teacher?.shortName].filter(Boolean);
  return parts.map((part, index) => (
    <Fragment key={index}>
      {index > 0 ? ' · ' : null}
      {part}
    </Fragment>
  ));
}

/**
 * Що в запамʼятованої групи зараз чи далі (`groupFocusPlan`) — окремим блоком героя: назва заняття (повна, рівнем заголовка),
 * під нею аудиторія й викладач. Це найкорисніше після відліку («куди йти»), тож не ховається дрібним рядком.
 */
function GroupFocus({ group, lessons, when }: { group: ScheduleTarget; lessons: readonly LessonView[]; when: string }) {
  return (
    <div className="flex flex-col gap-1">
      <p className="text-caption text-secondary">
        У <GroupLink group={group} /> {when}
      </p>
      {/* підгрупи з тією самою дисципліною — одна назва й по рядку місця на кожну */}
      {lessons.map((lesson, index) => {
        const name = lesson.discipline.name || lesson.discipline.shortName;
        const repeated = index > 0 && lessons[index - 1]?.discipline.id === lesson.discipline.id;
        return (
          <Fragment key={lesson.id}>
            {repeated ? null : <p className="text-heading font-bold text-balance wrap-break-word text-primary">{name}</p>}
            <p className="text-ui text-secondary">{placeLine(lesson)}</p>
          </Fragment>
        );
      })}
    </div>
  );
}

/** Силует блоку групи: підпис, назва заняття, рядок місця — як у `GroupFocus`. */
function FocusSkeleton() {
  return (
    <div aria-hidden="true" className="flex w-64 max-w-full flex-col gap-1">
      <span className="flex h-[1.125rem] items-center">
        <Skeleton className="h-3 w-24" />
      </span>
      <span className="flex h-6 items-center">
        <Skeleton className="h-4 w-56 max-w-full" />
      </span>
      <span className="flex h-5 items-center">
        <Skeleton className="h-3 w-40" />
      </span>
    </div>
  );
}

/**
 * Герой головної: «4 пара · 38 хв до дзвінка», речення «що далі» й доріжка дня. Якщо людина вже
 * відкривала свою групу, тут же одним рядком — що в неї зараз, а після пар — коли завтра перша.
 */
export function NowPanel({ className }: { className?: string }) {
  const covered = useErrorCovered();
  const meta = usePublicMeta();
  const group = useRememberedGroup();
  const today = meta.data?.today ?? null;
  const studyToday = Boolean(meta.data?.studyDay);
  const nextDay: IsoDate | null = meta.data?.nextStudyDay?.date ?? null;
  const bells = useBells(studyToday ? today : null);
  const slots = bells.data?.slots ?? null;
  const live = useNow(studyToday ? slots : null, { date: today });
  const range = nowPanelRange(meta.data);
  const groupSchedule = usePublicSchedule('GROUP', group?.id, range?.from ?? '', range?.to ?? '', {
    enabled: Boolean(group && range),
  });
  const stall = useStalled([meta, studyToday ? bells : null]);

  // запит «завис» (немає мережі, сервер мовчить) — силует не висить: сторінка каже це одним рядком
  if ((meta.isPending || (studyToday && bells.isPending)) && stall) return null;
  if (meta.isPending) return <HeroSkeleton withFocus={Boolean(group)} className={className} />;
  // сторінка вже показує одну спільну помилку: порожній тонований блок лише заважав би
  if (meta.isError && !meta.data && covered) return null;
  if (!meta.data) {
    return (
      <section aria-label="Стан навчального дня" className={cn(HERO, className)}>
        <ErrorState error={meta.error} size="sm" align="start" onRetry={() => void meta.refetch()} retrying={meta.isFetching} />
      </section>
    );
  }

  const data = meta.data;
  const semester = data.semester;
  const dateLine = (
    <span className="inline-flex flex-wrap items-center gap-x-2">
      <span>{formatWeekdayDate(data.today)}</span>
      {data.studyDay && data.parity ? (
        <>
          <span aria-hidden="true">·</span>
          <ParityBadge parity={data.parity} size="sm" />
        </>
      ) : null}
    </span>
  );

  // core: семестру немає або сьогодні між семестрами — тоді немає ні парності, ні номера тижня
  if (!semester || compareIsoDates(data.today, semester.endsOn) > 0) {
    return (
      <section aria-label="Стан навчального дня" className={cn(HERO, className)}>
        <p className="text-body text-secondary">{formatWeekdayDate(data.today)}</p>
        <p className={DISPLAY}>Пар немає</p>
        <p className="text-body text-secondary">
          {semester
            ? `${capitalize(semester.name)} завершився ${formatLongDate(semester.endsOn)}.`
            : 'Навчального семестру зараз немає — пар не заплановано.'}
        </p>
      </section>
    );
  }

  if (compareIsoDates(data.today, semester.startsOn) < 0) {
    return (
      <section aria-label="Стан навчального дня" className={cn(HERO, className)}>
        <p className="text-body text-secondary">{formatWeekdayDate(data.today)}</p>
        <p className={DISPLAY}>Заняття ще не почалися</p>
        <p className="text-body text-secondary">
          {capitalize(semester.name)} починається {formatLongDate(semester.startsOn)}.
        </p>
      </section>
    );
  }

  if (studyToday && !bells.data && !bells.isError) {
    return <HeroSkeleton dateLine={dateLine} withFocus={Boolean(group)} className={className} />;
  }

  if (studyToday && bells.isError && covered) return null;
  if (studyToday && bells.isError) {
    return (
      <section aria-label="Стан навчального дня" className={cn(HERO, className)}>
        <p className="text-body text-secondary">{dateLine}</p>
        <ErrorState
          error={bells.error}
          size="sm"
          align="start"
          title="Дзвінки на сьогодні не завантажилися"
          onRetry={() => void bells.refetch()}
          retrying={bells.isFetching}
        />
      </section>
    );
  }

  const days = groupSchedule.data?.days ?? [];
  const groupToday = days.find((d) => d.date === data.today);
  const groupNextDay = nextDay ? days.find((d) => d.date === nextDay) : undefined;

  // «У 4КІ1 зараз / Іноземна мова / ауд. 16 · Крисак Л.В.»; на цьому дзвінку в групи пари немає —
  // її наступне заняття («У 2КІ1 далі о 13:30»), а коли в неї на сьогодні все — перша пара наступного дня
  const plan = group
    ? groupFocusPlan({ status: live.status, groupToday, groupNextDay, today: data.today, nextDay })
    : null;
  // розклад групи ще йде — місце блоку тримає силует тієї ж висоти, щоб на телефоні доріжка не з'їжджала вниз
  const focus =
    group && plan && plan.lessons.length > 0 ? (
      <GroupFocus group={group} lessons={plan.lessons} when={plan.when} />
    ) : group && groupSchedule.isPending && (live.status?.kind === 'in-pair' || live.status?.kind === 'break' || live.status?.kind === 'before-day') ? (
      <FocusSkeleton />
    ) : null;

  // після пар чи у вихідний — коли наступного разу: «Завтра перша о 09:40 — ЕУ, ауд. 22»
  let afterDay: string | undefined;
  if (nextDay) {
    const when = relativeDayLabel(nextDay, data.today) ?? capitalize(formatWeekdayDateAfterNa(nextDay).split(',')[0]);
    const first = firstLesson(groupNextDay);
    if (group && first?.startsAt) {
      afterDay = `${when === 'Завтра' ? 'Завтра' : `У ${when.toLocaleLowerCase('uk')}`} у ${group.label} перша о ${first.startsAt} — ${lessonLine(first)}.`;
    } else {
      afterDay = `Наступний навчальний день — ${formatWeekdayDate(nextDay).toLocaleLowerCase('uk')}.`;
    }
  }

  return (
    <NowIndicator
      variant="hero"
      bells={studyToday ? (slots ?? []) : []}
      groupPairs={group && groupToday ? activePairs(groupToday) : undefined}
      date={data.today}
      dateLine={dateLine}
      focus={focus}
      afterDay={afterDay}
      className={className}
    />
  );
}
