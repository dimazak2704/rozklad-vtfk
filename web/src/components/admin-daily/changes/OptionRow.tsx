import { DoorOpen } from 'lucide-react';
import type { KeyboardEvent, ReactNode } from 'react';
import type { ChangeOption } from '@/api/admin-daily';
import { Button } from '@/components/ui';
import { cn } from '@/lib/cn';
import { NBSP } from '../format';
import { optionTitle, rowChips } from '../model';

/** Аудиторія, куди прийде група: своя аудиторія замінника чи аудиторія іншої підгрупи. */
function suggestedRoomName(option: ChangeOption): string | null {
  if (option.kind !== 'REPLACE_DISCIPLINE' && option.kind !== 'MERGE_SUBGROUPS') return null;
  return option.suggestedRoom?.name ?? null;
}

/** Застереження сервера: бурштинова крапка і слово (у відтінках сірого читається з «Увага:»). */
function Warning({ text }: { text: string }) {
  return (
    <span className="flex items-baseline gap-2 text-caption text-change">
      <span aria-hidden="true" className="inline-block size-1.5 shrink-0 translate-y-[-2px] rounded-full bg-[var(--fill-change)]" />
      <span>
        <span className="sr-only">Увага: </span>
        {text}
      </span>
    </span>
  );
}

/**
 * Коротко про варіант для плану дня: до трьох фактів сервера (перша причина, перше застереження, ще один)
 * одним рядком, застереження — бурштиновим.
 */
export function OptionChips({ option }: { option: ChangeOption }) {
  const chips = rowChips(option);
  if (chips.length === 0) return null;
  return (
    <ul className="flex flex-col gap-0.5" aria-label="Коротко про варіант">
      {chips.map((chip) =>
        chip.warning ? (
          <li key={chip.text}>
            <Warning text={chip.text} />
          </li>
        ) : (
          <li key={chip.text} className="text-caption text-secondary">
            {chip.text}
          </li>
        ),
      )}
    </ul>
  );
}

export interface OptionRowProps {
  option: ChangeOption;
  /** id кнопки вибору: на неї переводять фокус клавіші 1–9. */
  selectId: string;
  /** Клавіша вибору (1–9), якщо є. */
  hotkey: number | null;
  selected: boolean;
  /** Перший варіант, який сервер явно рекомендує: розгорнутий блок «Найкращий варіант». */
  best?: boolean;
  /** Створюємо зміну з цього варіанта. */
  busy: boolean;
  /** Поки створюється інша зміна, діяти не можна (вибирати — можна). */
  disabled: boolean;
  actionLabel: string;
  onSelect: (option: ChangeOption) => void;
  onAct: (option: ChangeOption) => void;
  /** Що показати в обраному рядку над кнопкою дії (причина в розпорядженні). */
  actionExtra?: ReactNode;
}

/**
 * Варіант рішення. У спокої — назва, одне речення причин і одне застереження. Клік чи клавіша лише ОБИРАЮТЬ
 * рядок: він розгортається (усі причини й застереження, причина в розпорядженні) і показує єдину кнопку дії;
 * дія — ця кнопка або Enter на обраному рядку. Без балів і номерів.
 */
export function OptionRow({ option, selectId, hotkey, selected, best = false, busy, disabled, actionLabel, onSelect, onAct, actionExtra }: OptionRowProps) {
  const title = optionTitle(option);
  const room = suggestedRoomName(option);
  const reasons = selected ? option.reasons : option.reasons.slice(0, 2);
  const warnings = selected ? option.warnings : option.warnings.slice(0, 1);
  const cancel = option.kind === 'CANCEL';

  const onKeyDown = (event: KeyboardEvent<HTMLButtonElement>) => {
    // Enter на вже обраному рядку — дія; на необраному — звичайний вибір (клік кнопки)
    if (event.key === 'Enter' && selected && !event.repeat) {
      event.preventDefault();
      if (!disabled) onAct(option);
    }
  };

  return (
    <div
      className={cn(
        'relative flex flex-col gap-1.5 rounded-popover px-3.5 py-3',
        'transition-[background-color] duration-150 ease-out',
        selected ? 'bg-selected' : 'hover:bg-hover',
      )}
    >
      <button
        type="button"
        id={selectId}
        aria-pressed={selected}
        aria-keyshortcuts={hotkey ? String(hotkey) : undefined}
        onClick={() => onSelect(option)}
        onKeyDown={onKeyDown}
        className={cn(
          // накладка after:inset-0 робить клікабельним увесь рядок; кнопка дії лежить над нею (z-10)
          'flex min-w-0 flex-col items-start gap-1 text-start after:absolute after:inset-0 after:rounded-popover',
          'focus-visible:outline-none focus-visible:after:outline-2 focus-visible:after:outline-offset-2 focus-visible:after:outline-focus',
        )}
      >
        {best ? <span className="text-meta font-semibold text-accent">Найкращий варіант</span> : null}
        <span
          className={cn(
            'text-pretty',
            best ? 'font-display text-heading font-semibold' : 'text-cell font-[650]',
            cancel ? 'text-cancel' : 'text-primary',
          )}
        >
          {title}
        </span>
        {room || reasons.length > 0 ? (
          <span className="flex items-start gap-1 text-caption text-secondary">
            {room ? <DoorOpen aria-hidden="true" className="mt-0.5 size-3.5 shrink-0 text-muted" /> : null}
            {/* одне речення: «·» тримається за попереднє слово (нерозривний пробіл), тож рядок не починається з нього */}
            <span>
              {room ? <span>ауд. {room}</span> : null}
              {reasons.map((reason, index) => (
                <span key={reason}>
                  {room || index > 0 ? `${NBSP}· ` : ''}
                  {reason}
                </span>
              ))}
            </span>
          </span>
        ) : null}
      </button>
      {warnings.length > 0 ? (
        <div className="flex flex-col gap-0.5">
          {warnings.map((warning) => (
            <Warning key={warning} text={warning} />
          ))}
        </div>
      ) : null}
      {selected ? (
        <div className="relative z-10 mt-1.5 flex flex-col gap-3">
          {actionExtra}
          <Button variant="primary" className="self-start" loading={busy} disabled={disabled && !busy} onClick={() => onAct(option)}>
            {actionLabel}
          </Button>
        </div>
      ) : null}
    </div>
  );
}
