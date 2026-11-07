import type { EventStreamState } from '@/api/useEventStream';
import { cn } from '@/lib/cn';
import { StatusDot } from '../ui/StatusDot';
import { Tooltip } from '../ui/Tooltip';

const TEXT: Record<EventStreamState['status'], { label: string; hint: string }> = {
  idle: { label: 'Без оновлень', hint: 'Живі оновлення вимкнено' },
  connecting: { label: 'Підключення…', hint: 'Підключаємося до живих оновлень' },
  open: { label: 'Наживо', hint: 'Підтвердження і зміни зʼявляються без оновлення сторінки' },
  reconnecting: { label: 'Перепідключення…', hint: 'Звʼязок перервався, пробуємо знову' },
  closed: { label: 'Офлайн', hint: 'Живі оновлення недоступні. Дані оновляться при поверненні на вкладку.' },
};

/** Індикатор живого зʼєднання (SSE) у верхній панелі. Підпис видно на широких екранах, інакше — у підказці. */
export function LiveStatus({ state, className }: { state: EventStreamState; className?: string }) {
  const text = TEXT[state.status];
  return (
    <Tooltip content={`${text.label}. ${text.hint}`}>
      <span
        tabIndex={0}
        aria-label={text.label}
        className={cn(
          'inline-flex h-9 min-w-9 items-center justify-center gap-1.5 rounded-control px-2 text-caption font-semibold whitespace-nowrap text-secondary',
          'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus',
          className,
        )}
      >
        <StatusDot
          tone={state.status === 'open' ? 'live' : state.status === 'closed' ? 'cancel' : 'neutral'}
          pulse={state.status === 'open'}
          label={text.label}
        />
        <span aria-hidden="true" className="hidden xl:inline">
          {text.label}
        </span>
      </span>
    </Tooltip>
  );
}
