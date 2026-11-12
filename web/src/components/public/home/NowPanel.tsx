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
 * Що в запамʼятованої групи зараз чи далі — окремим блоком героя: назва заняття (повна, рівнем заголовка),
 * під нею аудиторія й викладач. Це найкорисніше після відліку («куди йти»), тож не ховається дрібним рядком.
 */
function GroupFocus({ group, lessons, when }: { group: ScheduleTarget; lessons: readonly LessonView[]; when: 'зараз' | 'далі' }) {
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
            {repeated ? null : <p className="text-heading font-bold text-balance text-primary">{name}</p>}
            <p className="text-ui text-secondary">{placeLine(lesson)}</p>
          </Fragment>
        );
      })}
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
  if (meta.isPending) {
    return (
      <section aria-label="Стан навчального дня" aria-busy="true" className={cn(HERO, 'gap-4', className)}>
        <Skeleton className="h-5 w-56" />
        <Skeleton className="h-12 w-72 max-w-full" />
        <Skeleton className="mt-4 hidden h-2 w-full md:block" />
      </section>
    );
  }
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
    return (
      <section aria-label="Стан навчального дня" aria-busy="true" className={cn(HERO, 'gap-4', className)}>
        <p className="text-body text-secondary">{dateLine}</p>
        <Skeleton className="h-12 w-72 max-w-full" />
      </section>
    );
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

  // «У 4КІ1 зараз / Іноземна мова / ауд. 16 · Крисак Л.В.» — лише коли в групи справді є пара зараз чи наступна
  let focus = null;
  const status = live.status;
  if (group && groupToday && status && (status.kind === 'in-pair' || status.kind === 'break' || status.kind === 'before-day')) {
    const pair = status.kind === 'in-pair' ? status.current.pair : status.next.pair;
    const lessons = groupToday.lessons
      .filter((l) => l.pair === pair && l.kind !== 'CANCELLED')
      .sort((a, b) => a.subgroup - b.subgroup)
      .slice(0, 2);
    if (lessons.length > 0) {
      focus = (
        <GroupFocus group={group} lessons={lessons} when={status.kind === 'in-pair' ? 'зараз' : 'далі'} />
      );
    }
  }

  // після пар чи у вихідний — коли наступного разу: «Завтра перша о 09:40 — ЕУ, ауд. 22»
  let afterDay: string | undefined;
  if (nextDay) {
    const when = relativeDayLabel(nextDay, data.today) ?? capitalize(formatWeekdayDateAfterNa(nextDay).replace(/,.*$/, ''));
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
