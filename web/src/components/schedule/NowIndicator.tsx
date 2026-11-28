import { useMemo, type ReactNode } from 'react';
import type { BellSlotDto, IsoDate } from '@/api/types';
import { useNow } from '@/hooks/useNow';
import { describePairStatus, pairStatus, toTimedSlots, type PairStatus } from '@/lib/bells';
import { cn } from '@/lib/cn';
import { durationLabel } from '@/lib/labels';
import { DayTrack } from './DayTrack';
import { Headline, NextSentence } from './NowHeadline';

export interface NowIndicatorProps {
  /** Дзвінки дня (профіль, що діє). */
  bells: readonly BellSlotDto[];
  /** Пари, які в людини справді є: доріжка йде від першої до останньої з них. */
  lessonPairs?: readonly number[];
  /**
   * Пари запамʼятованої групи на головній: відлік і доріжка лишаються загальними (дзвінки коледжу), а смуги,
   * де в групи пари немає, позначено вікном — і підказка не каже «минула» про пару, якої не було.
   */
  groupPairs?: readonly number[];
  /** День на екрані; живий стан — лише для сьогодні. */
  date?: IsoDate | null;
  /**
   * `hero` — головна: тонований блок з відліком-заголовком, реченням «що далі» і доріжкою дня;
   * `strip` — сторінка дзвінків і огляд диспетчера: відлік, під ним речення «що далі» й доріжка дня, без тла;
   * `compact` — телефон над денним списком: відлік і одна смуга поточної пари;
   * `inline` — один рядок у рядку даних заголовка (десктоп).
   */
  variant?: 'hero' | 'strip' | 'compact' | 'inline';
  /** Рядок дати над заголовком героя: «Середа, 11 листопада · ◐ чисельник». */
  dateLine?: ReactNode;
  /** Додатковий рядок у герої: «Зараз у вас СТП, 4КІ1, ауд. 301». */
  aside?: ReactNode;
  /**
   * Блок героя праворуч (на вузькому — під заголовком): що саме в запамʼятованої групи зараз чи далі —
   * назва заняття, аудиторія, викладач. Найкорисніше після відліку, тож воно стоїть окремо, а не дрібним рядком.
   */
  focus?: ReactNode;
  /** Речення, коли пари скінчилися або їх немає: «Завтра перша о 09:40, ЕУ, ауд. 22». */
  afterDay?: ReactNode;
  /** Зафіксований момент (хвилини від півночі) — для демо й тестів. */
  at?: number;
  className?: string;
}

/** Текст для aria-live: змінюється лише зі зміною стану, а не щохвилини. */
function liveText(status: PairStatus): string {
  if (status.kind === 'in-pair') return `Зараз ${status.current.pair}\u00a0пара`;
  if (status.kind === 'break') return `Перерва до ${status.next.startsAt}`;
  return describePairStatus(status);
}

/** Один рядок: «Зараз 2 пара · ще 35 хв» — та сама форма «N пара», що й у герої, сітці та списку. */
function inlineText(status: PairStatus): string {
  switch (status.kind) {
    case 'in-pair':
      return `Зараз ${status.current.pair}\u00a0пара · ще ${status.minutesLeft}\u00a0хв`;
    case 'break':
      return `Перерва · ${status.next.pair}\u00a0пара о\u00a0${status.next.startsAt}`;
    case 'before-day':
      return status.minutesUntil <= 180
        ? `Перша пара о\u00a0${status.next.startsAt} · за\u00a0${durationLabel(status.minutesUntil)}`
        : `Перша пара о\u00a0${status.next.startsAt}`;
    case 'after-day':
      return `Пари закінчилися о\u00a0${status.last.endsAt}`;
    case 'no-classes':
      return 'Сьогодні пар немає';
  }
}

/** Одна смуга поточної пари (телефон): два підписи — початок ліворуч, кінець праворуч, окремим рядком. */
function PairBar({ status }: { status: PairStatus }) {
  if (status.kind !== 'in-pair' && status.kind !== 'break') return null;
  const from = status.kind === 'in-pair' ? status.current.startsAt : status.previous.endsAt;
  const to = status.kind === 'in-pair' ? status.current.endsAt : status.next.startsAt;
  return (
    <div aria-hidden="true" className="flex flex-col gap-1.5">
      <span className="block h-2 overflow-hidden rounded-full bg-[var(--fill-track-future)]">
        <span
          className="block h-full origin-left rounded-full bg-accent transition-transform duration-1000 ease-linear rtl:origin-right"
          style={{ transform: `scaleX(${Math.max(0.04, status.progress)})` }}
        />
      </span>
      <span className="flex justify-between text-caption font-semibold text-secondary figures">
        <span>{from}</span>
        <span>{to}</span>
      </span>
    </div>
  );
}

/**
 * «Живий» стан дня — єдине, що рухається на сторінці. Герой головної: відлік як заголовок, речення
 * «що далі» й доріжка дня, на якій підписи не можуть накластися (кожен — у своїй колонці).
 * Хвилина змінюється — цифри відліку «котяться», смуга поточної пари росте.
 */
export function NowIndicator({
  bells,
  lessonPairs,
  groupPairs,
  date = null,
  variant = 'strip',
  dateLine,
  aside,
  focus,
  afterDay,
  at,
  className,
}: NowIndicatorProps) {
  const ownPairs = useMemo(() => (lessonPairs ? new Set(lessonPairs) : null), [lessonPairs]);
  const groupSet = useMemo(() => (groupPairs ? new Set(groupPairs) : null), [groupPairs]);
  const live = useNow(at === undefined ? bells : null, { date, onlyPairs: ownPairs });
  const status: PairStatus | null = at !== undefined ? pairStatus(bells, at, ownPairs) : live.status;
  const minutes = at ?? live.clock.minutes + live.clock.seconds / 60;
  const showLive = at !== undefined || live.isToday;

  const slots = useMemo(() => {
    const all = toTimedSlots(bells);
    if (!ownPairs || ownPairs.size === 0) return all;
    const pairs = [...ownPairs];
    const first = Math.min(...pairs);
    const last = Math.max(...pairs);
    return all.filter((slot) => slot.pair >= first && slot.pair <= last);
  }, [bells, ownPairs]);

  if (!showLive) return null;
  // герой показує «Сьогодні пар немає», навіть коли дзвінків на сьогодні немає (вихідний, свято)
  const shown: PairStatus | null = status ?? (variant === 'hero' ? { kind: 'no-classes' } : null);
  if (!shown || (slots.length === 0 && variant !== 'hero')) return null;

  const announcement = (
    <span className="sr-only" aria-live="polite">
      {liveText(shown)}
    </span>
  );

  if (variant === 'inline') {
    return (
      <p className={cn('flex min-w-0 items-center gap-2 text-ui text-secondary', className)}>
        <span className="truncate">{inlineText(shown)}</span>
        {announcement}
      </p>
    );
  }

  const sentence = <NextSentence status={shown} />;
  const hasSentence = shown.kind === 'in-pair' || shown.kind === 'break';
  const label = `Стан навчального дня: ${describePairStatus(shown)}`;

  if (variant === 'compact') {
    return (
      <section aria-label="Стан навчального дня" className={cn('flex flex-col gap-4', className)}>
        <Headline status={shown} afterDay={afterDay} compact />
        <PairBar status={shown} />
        {hasSentence ? <p className="text-ui text-secondary">{sentence}</p> : null}
        {announcement}
      </section>
    );
  }

  // після останнього дзвінка доріжка — лише ряд однакових сірих смуг, схожий на силует завантаження:
  // день скінчився, тож її немає (як і смуги пари на телефоні); «На сьогодні все» каже заголовок
  const track =
    slots.length > 0 && shown.kind !== 'after-day' ? (
      <div role="img" aria-label={label}>
        <DayTrack slots={slots} status={shown} minutes={minutes} ownPairs={ownPairs ?? groupSet} />
      </div>
    ) : null;

  if (variant === 'strip') {
    // телефон: як у герої головної — одна смуга поточної пари з часом початку й кінця, під нею речення «що далі».
    // Доріжка з вісьмох смуг у 340 px дає крихітні сегменти, пігулку ширшу за пару й підписи впритул
    return (
      <section aria-label="Стан навчального дня" className={cn('flex w-full flex-col gap-5', className)}>
        {/* як у герої: речення «що далі» — одразу під відліком, а не на іншому краї рядка */}
        <div className="flex min-w-0 flex-col gap-1.5">
          <Headline status={shown} afterDay={afterDay} compact={false} />
          {hasSentence ? <p className="hidden text-body text-secondary md:block">{sentence}</p> : null}
        </div>
        {track ? <div className="hidden md:block">{track}</div> : null}
        {hasSentence ? (
          <div data-now-phone="" className="flex flex-col gap-3 md:hidden">
            <PairBar status={shown} />
            <p className="text-ui text-secondary">{sentence}</p>
          </div>
        ) : null}
        {announcement}
      </section>
    );
  }

  // hero: спокійне тонування без рамки й тіні; на телефоні — одна смуга поточної пари замість доріжки
  return (
    <section
      aria-label="Стан навчального дня"
      className={cn('flex flex-col gap-5 rounded-[20px] bg-today-wash p-5 md:gap-7 md:rounded-hero md:px-10 md:py-8', className)}
    >
      <div className="flex flex-col gap-2">
        {dateLine ? <p className="text-body text-secondary">{dateLine}</p> : null}
        {/* речення «що далі» — одразу під відліком, а не на іншому краї; праворуч — що саме в групи зараз */}
        <div className="flex flex-wrap items-end justify-between gap-x-10 gap-y-4">
          <div className="flex min-w-0 flex-col gap-1.5">
            <Headline status={shown} afterDay={afterDay} compact={false} />
            {hasSentence ? <p className="hidden text-body text-secondary md:block">{sentence}</p> : null}
          </div>
          {focus ? <div className="min-w-0 lg:max-w-[22rem]">{focus}</div> : null}
        </div>
        {aside ? <p className="text-caption text-secondary">{aside}</p> : null}
      </div>
      {track ? <div className="hidden md:block">{track}</div> : null}
      <div className="flex flex-col gap-3 md:hidden">
        <PairBar status={shown} />
        {hasSentence ? <p className="text-ui text-secondary">{sentence}</p> : null}
      </div>
      {announcement}
    </section>
  );
}
