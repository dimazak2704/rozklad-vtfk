import { Fragment, useMemo, useState, type ReactNode } from 'react';
import type { BellSlotDto, IsoDate } from '@/api/types';
import { useNow } from '@/hooks/useNow';
import { describePairStatus, pairStatus, toTimedSlots, type PairStatus, type TimedSlot } from '@/lib/bells';
import { cn } from '@/lib/cn';
import { durationLabel } from '@/lib/labels';
import { pairOrdinalGenitive, pairOrdinalLabel } from '@/lib/ordinal';
import { Tooltip } from '../ui/Tooltip';
import { segmentHint, type SegmentState } from './track-hint';

export interface NowIndicatorProps {
  /** Дзвінки дня (профіль, що діє). */
  bells: readonly BellSlotDto[];
  /** Пари, які в людини справді є: доріжка йде від першої до останньої з них. */
  lessonPairs?: readonly number[];
  /** День на екрані; живий стан — лише для сьогодні. */
  date?: IsoDate | null;
  /**
   * `hero` — головна: тонований блок з відліком-заголовком, реченням «що далі» і доріжкою дня;
   * `strip` — сторінка дзвінків: відлік і доріжка дня без тла;
   * `compact` — телефон над денним списком: відлік і одна смуга поточної пари;
   * `inline` — один рядок у рядку даних заголовка (десктоп).
   */
  variant?: 'hero' | 'strip' | 'compact' | 'inline';
  /** Рядок дати над заголовком героя: «Середа, 11 листопада · ◐ чисельник». */
  dateLine?: ReactNode;
  /** Додатковий рядок у герої: «У 4КІ1 зараз Прогр., ауд. 301, Стець». */
  aside?: ReactNode;
  /** Речення, коли пари скінчилися або їх немає: «Завтра перша о 09:40, ЕУ, ауд. 22». */
  afterDay?: ReactNode;
  /** Зафіксований момент (хвилини від півночі) — для демо й тестів. */
  at?: number;
  className?: string;
}

/** Текст для aria-live: змінюється лише зі зміною стану, а не щохвилини. */
function liveText(status: PairStatus): string {
  if (status.kind === 'in-pair') return `Зараз ${pairOrdinalLabel(status.current.pair)}`;
  if (status.kind === 'break') return `Перерва до ${status.next.startsAt}`;
  return describePairStatus(status);
}

/** Один рядок: «Зараз 2-га пара · ще 35 хв». */
function inlineText(status: PairStatus): string {
  switch (status.kind) {
    case 'in-pair':
      return `Зараз ${pairOrdinalLabel(status.current.pair)} · ще ${status.minutesLeft}\u00a0хв`;
    case 'break':
      return `Перерва · ${pairOrdinalLabel(status.next.pair)} о\u00a0${status.next.startsAt}`;
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

/**
 * Відлік, що «котиться»: коли хвилина змінюється, стара цифра йде вгору на 4 px і гасне, а нова заходить
 * знизу (200 мс, ease-out). Рухаються лише ті цифри, що змінилися (ключ — позиція від кінця й цифра).
 * Без руху стара цифра не показується, а нова стає на місце одразу.
 */
export function Countdown({ value, className }: { value: number; className?: string }) {
  const digits = String(value).split('');
  // попереднє значення — щоб показати, яка цифра йде; оновлюється під час рендеру, без ефекту
  const [shown, setShown] = useState(value);
  const [previous, setPrevious] = useState<string | null>(null);
  if (value !== shown) {
    setPrevious(String(shown));
    setShown(value);
  }
  const old = previous ? previous.split('') : [];
  return (
    <span className={cn('inline-flex figures', className)} aria-hidden="true">
      {digits.map((digit, index) => {
        const fromEnd = digits.length - index;
        const before = old[old.length - fromEnd];
        const leaving = before !== undefined && before !== digit ? before : null;
        return (
          <span key={`${fromEnd}-${digit}`} className="relative inline-block">
            <span className="anim-digit">{digit}</span>
            {leaving ? (
              <span data-leaving="" className="anim-digit-out absolute inset-0">
                {leaving}
              </span>
            ) : null}
          </span>
        );
      })}
    </span>
  );
}

/** Заголовок не рветься «Перша пара о / 08:30»: рядки вирівнюються, а прийменник тримається часу (о 08:30). */
const DISPLAY = 'font-display text-[2.125rem] leading-[2.375rem] font-semibold tracking-[-0.025em] text-balance md:text-display';
const NUMERAL = 'font-display text-[4rem] leading-[3.75rem] font-light tracking-[-0.04em] md:text-numeral';

/** Заголовок дня одним рядком на спільній базовій лінії: «4 пара  38 хв до дзвінка». */
function Headline({ status, afterDay, compact }: { status: PairStatus; afterDay?: ReactNode; compact: boolean }) {
  const wrap = cn('flex min-w-0 flex-wrap items-baseline gap-x-5', compact ? 'gap-y-0' : 'gap-y-1');
  switch (status.kind) {
    case 'in-pair':
      return (
        <p className={wrap}>
          <span className={cn(DISPLAY, 'text-primary')}>{status.current.pair} пара</span>
          <span className="inline-flex items-baseline gap-2.5">
            <Countdown value={status.minutesLeft} className={cn(NUMERAL, 'text-accent')} />
            <span className="sr-only">{status.minutesLeft} </span>
            <span className="text-body text-secondary">хв до дзвінка</span>
          </span>
        </p>
      );
    case 'break':
      return (
        <p className={wrap}>
          <span className={cn(DISPLAY, 'text-primary')}>Перерва</span>
          <span className="inline-flex items-baseline gap-2.5">
            <Countdown value={status.minutesLeft} className={cn(NUMERAL, 'text-accent')} />
            <span className="sr-only">{status.minutesLeft} </span>
            <span className="text-body text-secondary">хв до {pairOrdinalGenitive(status.next.pair)} пари</span>
          </span>
        </p>
      );
    case 'before-day':
      return (
        <p className={wrap}>
          <span className={cn(DISPLAY, 'text-primary')}>
            Перша пара <span className="whitespace-nowrap">о <span className="figures">{status.next.startsAt}</span></span>
          </span>
          {status.minutesUntil <= 180 ? (
            <span className="text-body text-secondary">за {durationLabel(status.minutesUntil)}</span>
          ) : null}
        </p>
      );
    case 'after-day':
      return (
        <div className="flex flex-col gap-1">
          <p className={cn(DISPLAY, 'text-primary')}>На сьогодні все</p>
          <p className="text-body text-secondary">{afterDay ?? `Остання пара закінчилася о\u00a0${status.last.endsAt}.`}</p>
        </div>
      );
    case 'no-classes':
      return (
        <div className="flex flex-col gap-1">
          <p className={cn(DISPLAY, 'text-primary')}>Сьогодні пар немає</p>
          {afterDay ? <p className="text-body text-secondary">{afterDay}</p> : null}
        </div>
      );
  }
}

/** Що далі — реченням, а не вісьмома часами: «Далі перерва 20 хв, 5 пара о 13:30». */
function NextSentence({ status }: { status: PairStatus }) {
  if (status.kind === 'in-pair') {
    if (!status.next) return <>Це остання пара сьогодні</>;
    const gap = Math.round(status.next.start - status.current.end);
    return (
      <>
        Далі перерва {gap}&nbsp;хв,{' '}
        <span className="font-[650] whitespace-nowrap text-primary">
          {status.next.pair} пара о <span className="figures">{status.next.startsAt}</span>
        </span>
      </>
    );
  }
  if (status.kind === 'break') {
    return (
      <>
        Почнеться о{'\u00a0'}
        <span className="font-[650] text-primary figures">{status.next.startsAt}</span>, до{'\u00a0'}
        <span className="figures">{status.next.endsAt}</span>
      </>
    );
  }
  return null;
}


/**
 * Ширина доріжки, вужче за яку підпис часу вже не вміщається у свою колонку (≈ 3.25rem на пару).
 * Тоді підписи стоять одним рядком під доріжкою: поточна пара ліворуч, наступна праворуч.
 * Класи — буквальні рядки, щоб Tailwind їх побачив.
 */
const NARROW: Record<number, { columns: string; row: string }> = {
  1: { columns: '@max-[4rem]/track:hidden', row: '@max-[4rem]/track:flex' },
  2: { columns: '@max-[7rem]/track:hidden', row: '@max-[7rem]/track:flex' },
  3: { columns: '@max-[10rem]/track:hidden', row: '@max-[10rem]/track:flex' },
  4: { columns: '@max-[14rem]/track:hidden', row: '@max-[14rem]/track:flex' },
  5: { columns: '@max-[17rem]/track:hidden', row: '@max-[17rem]/track:flex' },
  6: { columns: '@max-[20rem]/track:hidden', row: '@max-[20rem]/track:flex' },
  7: { columns: '@max-[24rem]/track:hidden', row: '@max-[24rem]/track:flex' },
  8: { columns: '@max-[27rem]/track:hidden', row: '@max-[27rem]/track:flex' },
};
const NARROW_MAX = { columns: '@max-[32rem]/track:hidden', row: '@max-[32rem]/track:flex' };

function segmentState(slot: TimedSlot, minutes: number): SegmentState {
  if (minutes >= slot.end) return 'past';
  if (minutes >= slot.start) return 'current';
  return 'future';
}

/**
 * Доріжка дня на одній сітці з однаковими колонками для трьох рядків: пігулка «зараз», смуги пар,
 * підписи. Пари однакової ширини, проміжок 8 px (довга перерва ≥ 20 хв — 20 px): ритм без осі часу.
 * Підписів щонайбільше два, кожен у своїй колонці й обрізається в її межах — накластися вони не можуть.
 */
export function DayTrack({
  slots,
  status,
  minutes,
  ownPairs,
  className,
}: {
  slots: readonly TimedSlot[];
  status: PairStatus;
  minutes: number;
  ownPairs?: ReadonlySet<number> | null;
  className?: string;
}) {
  // колонки: пара, проміжок, пара, проміжок, …, пара
  const template = slots
    .map((slot, index) => {
      const next = slots[index + 1];
      if (!next) return 'minmax(0,1fr)';
      return `minmax(0,1fr) ${next.start - slot.end >= 20 ? '20px' : '8px'}`;
    })
    .join(' ');
  const column = (index: number) => index * 2 + 1;

  const currentIndex = status.kind === 'in-pair' ? slots.findIndex((slot) => slot.pair === status.current.pair) : -1;
  const breakIndex = status.kind === 'break' ? slots.findIndex((slot) => slot.pair === status.previous.pair) : -1;
  const nextIndex =
    status.kind === 'in-pair' && status.next
      ? slots.findIndex((slot) => slot.pair === status.next?.pair)
      : status.kind === 'break' || status.kind === 'before-day'
        ? slots.findIndex((slot) => slot.pair === status.next.pair)
        : -1;
  const progress = status.kind === 'in-pair' ? status.progress : 0;
  const clock = `${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(Math.floor(minutes % 60)).padStart(2, '0')}`;
  const showPill = currentIndex >= 0 || breakIndex >= 0;
  const narrow = NARROW[slots.length] ?? NARROW_MAX;
  const nextLabelTone = status.kind === 'break' || status.kind === 'before-day' ? 'font-semibold text-accent' : 'text-muted';

  return (
    <div aria-hidden="true" className={cn('@container/track flex flex-col gap-1.5', className)}>
      <div className="grid grid-rows-[1.25rem_0.5rem_1rem] gap-y-1.5" style={{ gridTemplateColumns: template }}>
        {/* рядок пігулки: лише «зараз», усередині колонки поточної пари або проміжку перерви */}
        {showPill ? (
          <span
            className="relative row-start-1 min-w-0"
            style={{ gridColumn: currentIndex >= 0 ? column(currentIndex) : column(breakIndex) + 1 }}
          >
            {currentIndex >= 0 ? (
              // доріжка пігулки — колонка без її ширини: зсув на частку пари лишає пігулку в межах колонки,
              // а ковзає вона лише transform (1 с лінійно), без перерахунку макета
              <span
                data-now-pill-track=""
                className="absolute inset-y-0 start-0 w-[calc(100%-3.5rem)] transition-transform duration-1000 ease-linear"
                style={{ transform: `translateX(${progress * 100}%)` }}
              >
                <span className="absolute start-0 top-0 flex h-5 w-14 items-center justify-center rounded-full bg-accent text-meta font-semibold text-on-accent figures">
                  {clock}
                </span>
              </span>
            ) : (
              <span className="absolute top-0 left-1/2 flex h-5 w-14 -translate-x-1/2 items-center justify-center rounded-full bg-accent text-meta font-semibold text-on-accent figures">
                {clock}
              </span>
            )}
          </span>
        ) : null}

        {slots.map((slot, index) => {
          const state = segmentState(slot, minutes);
          const isWindow = ownPairs ? !ownPairs.has(slot.pair) : false;
          const next = slots[index + 1];
          return (
            <Fragment key={slot.pair}>
              {/* наведення мишею на смугу називає пару й час (ціль вища за саму смугу: 8 px зверху й знизу) */}
              <Tooltip content={segmentHint(slot, state, isWindow)} side="bottom">
                <span data-track-segment="" className="row-start-2 -my-2 min-w-0 py-2" style={{ gridColumn: column(index) }}>
                  <span
                    className={cn(
                      'block h-2 overflow-hidden rounded-full',
                      state === 'past' && 'bg-[var(--fill-track-past)]',
                      state === 'current' && 'bg-selected',
                      state === 'future' && (isWindow ? 'border border-dashed border-default' : 'bg-[var(--fill-track-future)]'),
                    )}
                  >
                    {state === 'current' ? (
                      <span
                        className="block h-full origin-left rounded-full bg-accent transition-transform duration-1000 ease-linear rtl:origin-right"
                        style={{ transform: `scaleX(${Math.max(0.04, progress)})` }}
                      />
                    ) : null}
                  </span>
                </span>
              </Tooltip>
              {/* перерва зараз: проміжок між смугами світиться акцентом */}
              {next && index === breakIndex ? (
                <span className="row-start-2 h-2 rounded-full bg-accent" style={{ gridColumn: column(index) + 1 }} />
              ) : null}
            </Fragment>
          );
        })}

        {/* підписи: поточна пара (акцентом) і наступна (тихо), кожна — у своїй колонці */}
        {currentIndex >= 0 ? (
          <span
            className={cn(
              '@container row-start-3 min-w-0 overflow-clip text-caption leading-4 font-semibold whitespace-nowrap text-accent figures',
              narrow.columns,
            )}
            style={{ gridColumn: column(currentIndex) }}
          >
            {slots[currentIndex]?.startsAt}
            <span className="hidden @[5.25rem]:inline">–{slots[currentIndex]?.endsAt}</span>
          </span>
        ) : null}
        {nextIndex >= 0 ? (
          <span
            className={cn('row-start-3 min-w-0 overflow-clip text-caption leading-4 whitespace-nowrap figures', nextLabelTone, narrow.columns)}
            style={{ gridColumn: column(nextIndex) }}
          >
            {slots[nextIndex]?.startsAt}
          </span>
        ) : null}
      </div>
      {/* вузька доріжка: ті самі два підписи одним рядком — поточна пара ліворуч, наступна праворуч */}
      {currentIndex >= 0 || nextIndex >= 0 ? (
        <div className={cn('-mt-[1.375rem] hidden justify-between gap-3 text-caption leading-4 whitespace-nowrap figures', narrow.row)}>
          {currentIndex >= 0 ? (
            <span className="font-semibold text-accent">
              {slots[currentIndex]?.startsAt}–{slots[currentIndex]?.endsAt}
            </span>
          ) : (
            <span />
          )}
          {nextIndex >= 0 ? <span className={nextLabelTone}>далі {slots[nextIndex]?.startsAt}</span> : null}
        </div>
      ) : null}
    </div>
  );
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
  date = null,
  variant = 'strip',
  dateLine,
  aside,
  afterDay,
  at,
  className,
}: NowIndicatorProps) {
  const ownPairs = useMemo(() => (lessonPairs ? new Set(lessonPairs) : null), [lessonPairs]);
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

  const track =
    slots.length > 0 ? (
      <div role="img" aria-label={label}>
        <DayTrack slots={slots} status={shown} minutes={minutes} ownPairs={ownPairs} />
      </div>
    ) : null;

  if (variant === 'strip') {
    return (
      <section aria-label="Стан навчального дня" className={cn('flex w-full flex-col gap-5', className)}>
        <div className="flex flex-wrap items-end justify-between gap-x-8 gap-y-2">
          <Headline status={shown} afterDay={afterDay} compact={false} />
          {hasSentence ? <p className="text-body text-secondary">{sentence}</p> : null}
        </div>
        {track}
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
        <div className="flex flex-wrap items-end justify-between gap-x-10 gap-y-3">
          <Headline status={shown} afterDay={afterDay} compact={false} />
          {hasSentence ? <p className="hidden text-body text-secondary md:block lg:max-w-60 lg:text-end">{sentence}</p> : null}
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
