import { RotateCcw } from 'lucide-react';
import { useEffect, useState } from 'react';
import type { EventStreamState } from '@/api/useEventStream';
import { cn } from '@/lib/cn';
import { Button } from '../ui/Button';
import { Popover, PopoverContent, PopoverTrigger } from '../ui/Popover';

const RECONNECTING_HINT = 'Оновлення на екрані можуть запізнюватися, доки звʼязок не повернеться.';
const CLOSED_HINT = 'Дані на екрані можуть бути застарілими.';
/**
 * Скільки перепідключення має тривати, щоб про нього сказати: обрив на секунду минає непомітно. Перший вхід
 * (поки потік ще жодного разу не відкрився) не рахується зовсім — інакше «Перепідключення…» блимало б на кожному
 * холодному завантаженні сторінки.
 */
export const RECONNECTING_GRACE_MS = 2_000;

/** true, лише коли `active` тримається довше за `delayMs` (і одразу false, щойно минуло). */
function useLasting(active: boolean, delayMs: number): boolean {
  const [lasting, setLasting] = useState(false);
  useEffect(() => {
    if (!active) return;
    const timer = window.setTimeout(() => setLasting(true), delayMs);
    return () => {
      window.clearTimeout(timer);
      setLasting(false);
    };
  }, [active, delayMs]);
  return active && lasting;
}

function RetryButton({ onRetry }: { onRetry?: () => void }) {
  return (
    <Button
      variant="ghost"
      size="sm"
      iconStart={<RotateCcw aria-hidden="true" className="size-4" />}
      onClick={() => (onRetry ? onRetry() : window.location.reload())}
    >
      Повторити
    </Button>
  );
}

/**
 * Стан живих оновлень (SSE) у верхній панелі — лише коли щось не так, як у Google Docs: спокійний рядок
 * «крапка + слово», а не банер. Здорове зʼєднання нічого не показує («Онлайн» не пишемо); перепідключення —
 * тихе слово; немає звʼязку — червона крапка й «Повторити». На телефоні слово не вміщається поруч із датою,
 * тож крапка стає кнопкою 44 px з назвою стану: дотик відкриває пояснення (і «Повторити»).
 */
export function LiveStatus({
  state,
  onRetry,
  className,
}: {
  state: EventStreamState;
  /** Повторити підключення; без нього сторінка перезавантажується. */
  onRetry?: () => void;
  className?: string;
}) {
  // до першого відкриття потоку перепідключення — це ще холодний старт (токен, перший запит), а не обрив:
  // про нього мовчимо, а якщо потік так і не відкриється, стан стане «closed» і скаже про себе сам
  const [everOpen, setEverOpen] = useState(state.status === 'open');
  if (!everOpen && state.status === 'open') setEverOpen(true);
  const reconnecting = useLasting(everOpen && state.status === 'reconnecting', RECONNECTING_GRACE_MS);
  const closed = state.status === 'closed';
  const dot = (
    <span aria-hidden="true" className={cn('size-2 shrink-0 rounded-full', reconnecting ? 'bg-change' : 'bg-[var(--fill-cancel)]')} />
  );

  // одна жива область на весь час: вміст змінюється, тож читач екрана почує «Немає звʼязку» чи «Перепідключення»
  return (
    <span aria-live="polite" data-live-status={state.status} className={cn('inline-flex items-center gap-1 empty:hidden', className)}>
      {reconnecting || closed ? (
        <>
          {/* телефон: крапка-кнопка з поясненням */}
          <Popover>
            <PopoverTrigger asChild>
              <button
                type="button"
                aria-label={reconnecting ? 'Немає звʼязку, перепідключаємось. Докладніше' : 'Немає звʼязку. Докладніше'}
                className="inline-flex size-11 items-center justify-center rounded-control hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus sm:hidden"
              >
                {dot}
              </button>
            </PopoverTrigger>
            <PopoverContent width="sm" align="end" className="flex flex-col items-start gap-2">
              <p className="inline-flex items-center gap-2 text-ui font-[650] text-primary">
                {dot}
                {reconnecting ? 'Перепідключення…' : 'Немає звʼязку'}
              </p>
              <p className="text-caption text-secondary">{reconnecting ? RECONNECTING_HINT : CLOSED_HINT}</p>
              {closed ? <RetryButton onRetry={onRetry} /> : null}
            </PopoverContent>
          </Popover>

          {/* ширше: крапка й слово прямо в панелі */}
          <span
            className={cn(
              'hidden items-center gap-2 text-caption sm:inline-flex',
              reconnecting ? 'text-muted' : 'font-[650] text-primary',
            )}
          >
            {dot}
            {reconnecting ? (
              'Перепідключення…'
            ) : (
              <span>
                Немає звʼязку<span className="hidden xl:inline"> — дані можуть бути застарілими</span>
              </span>
            )}
          </span>
          {closed ? (
            <span className="hidden sm:inline-flex">
              <RetryButton onRetry={onRetry} />
            </span>
          ) : null}
        </>
      ) : null}
    </span>
  );
}
