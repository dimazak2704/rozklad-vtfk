import { useState, type ReactNode } from 'react';
import type { PairStatus } from '@/lib/bells';
import { cn } from '@/lib/cn';
import { durationLabel } from '@/lib/labels';
import { pairOrdinalGenitive } from '@/lib/ordinal';

/**
 * Відлік, що «котиться»: коли хвилина змінюється, стара цифра йде вгору на 0,12em і гасне (150 мс, ease-in),
 * а нова заходить знизу (200 мс, ease-out). Рухаються лише ті цифри, що змінилися (ключ — позиція від кінця й цифра).
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
/** Одиниця біля числа — помітна, але не більша за чверть цифри (88 → 18 px), щільно біля неї. */
const UNIT = 'text-body font-medium text-secondary md:text-heading md:font-medium';

/** Заголовок дня одним рядком на спільній базовій лінії: «4 пара  38 хв до дзвінка». */
export function Headline({ status, afterDay, compact }: { status: PairStatus; afterDay?: ReactNode; compact: boolean }) {
  const wrap = cn('flex min-w-0 flex-wrap items-baseline gap-x-5', compact ? 'gap-y-0' : 'gap-y-1');
  switch (status.kind) {
    case 'in-pair':
      return (
        <p className={wrap}>
          <span className={cn(DISPLAY, 'text-primary')}>{status.current.pair} пара</span>
          <span className="inline-flex items-baseline gap-2">
            <Countdown value={status.minutesLeft} className={cn(NUMERAL, 'text-accent')} />
            <span className="sr-only">{status.minutesLeft} </span>
            <span className={UNIT}>хв до дзвінка</span>
          </span>
        </p>
      );
    case 'break':
      return (
        <p className={wrap}>
          <span className={cn(DISPLAY, 'text-primary')}>Перерва</span>
          <span className="inline-flex items-baseline gap-2">
            <Countdown value={status.minutesLeft} className={cn(NUMERAL, 'text-accent')} />
            <span className="sr-only">{status.minutesLeft} </span>
            <span className={UNIT}>хв до {pairOrdinalGenitive(status.next.pair)} пари</span>
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
export function NextSentence({ status }: { status: PairStatus }) {
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
