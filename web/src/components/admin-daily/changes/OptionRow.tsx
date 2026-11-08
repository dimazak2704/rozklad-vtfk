import { Check, CircleQuestionMark, DoorOpen, TriangleExclamationPoint } from 'lucide-react';
import type { KeyboardEvent, ReactNode } from 'react';
import type { ChangeOption } from '@/api/admin-daily';
import { Button, Popover, PopoverContent, PopoverTrigger } from '@/components/ui';
import { cn } from '@/lib/cn';
import { OPTION_KIND_LABEL, optionTitle, rowChips } from '../model';

/** «Чому?»: усі причини й застереження сервера, без балів і формул. */
function WhyPopover({ option }: { option: ChangeOption }) {
  const title = optionTitle(option);
  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button
          variant="ghost"
          size="sm"
          className="relative z-10"
          iconStart={<CircleQuestionMark aria-hidden="true" className="size-4" />}
          aria-label={`Чому: ${title}`}
        >
          Чому?
        </Button>
      </PopoverTrigger>
      <PopoverContent width="md" align="end" title={title}>
        <div className="flex flex-col gap-3 text-ui">
          {option.reasons.length > 0 ? (
            <ul className="flex flex-col gap-1.5" aria-label="На користь">
              {option.reasons.map((reason) => (
                <li key={reason} className="flex items-start gap-2">
                  <Check aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-add" />
                  <span>{reason}</span>
                </li>
              ))}
            </ul>
          ) : (
            <p className="text-secondary">Особливих переваг немає: просто вільний на цій парі.</p>
          )}
          {option.warnings.length > 0 ? (
            <ul className="flex flex-col gap-1.5" aria-label="Варто врахувати">
              {option.warnings.map((warning) => (
                <li key={warning} className="flex items-start gap-2">
                  <TriangleExclamationPoint aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-change" />
                  <span>{warning}</span>
                </li>
              ))}
            </ul>
          ) : null}
        </div>
      </PopoverContent>
    </Popover>
  );
}

/** Аудиторія, куди прийде група: своя аудиторія замінника чи аудиторія іншої підгрупи. */
function suggestedRoomName(option: ChangeOption): string | null {
  if (option.kind !== 'REPLACE_DISCIPLINE' && option.kind !== 'MERGE_SUBGROUPS') return null;
  return option.suggestedRoom?.name ?? null;
}

/** До трьох чипів сервера: перша причина, перше «⚠», ще один. Довгі переносяться, а не обрізаються. */
export function OptionChips({ option }: { option: ChangeOption }) {
  const chips = rowChips(option);
  if (chips.length === 0) return null;
  return (
    <ul className="flex flex-wrap gap-1" aria-label="Коротко про варіант">
      {chips.map((chip) => (
        <li
          key={chip.text}
          className={cn(
            'inline-flex max-w-full items-start gap-1 rounded-plate px-1.5 py-0.5 text-meta font-medium text-pretty',
            chip.warning ? 'bg-change-subtle text-change' : 'bg-active text-secondary',
          )}
        >
          {chip.warning ? (
            <>
              <TriangleExclamationPoint aria-hidden="true" className="mt-px size-3.5 shrink-0" />
              <span className="sr-only">Увага: </span>
            </>
          ) : null}
          <span>{chip.text}</span>
        </li>
      ))}
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
 * Варіант рішення. Клік чи клавіша лише ОБИРАЮТЬ рядок; дія — окрема кнопка в обраному рядку або Enter на
 * ньому. Без балів і номерів: назва, вид, аудиторія і до трьох чипів сервера; решта — у «Чому?».
 */
export function OptionRow({ option, selectId, hotkey, selected, busy, disabled, actionLabel, onSelect, onAct, actionExtra }: OptionRowProps) {
  const title = optionTitle(option);
  const room = suggestedRoomName(option);
  const kindLabel = OPTION_KIND_LABEL[option.kind];

  const onKeyDown = (event: KeyboardEvent<HTMLButtonElement>) => {
    // Enter на вже обраному рядку — дія; на необраному — звичайний вибір (клік кнопки)
    if (event.key === 'Enter' && selected && !event.repeat) {
      event.preventDefault();
      if (!disabled) onAct(option);
    }
  };

  return (
    <li
      className={cn(
        'relative flex flex-col gap-2 rounded-control border px-3 py-2.5',
        'transition-[background-color,border-color] duration-100 ease-out',
        selected ? 'border-accent bg-selected' : 'border-default hover:border-control hover:bg-hover',
      )}
    >
      <div className="flex items-start gap-2">
        <button
          type="button"
          id={selectId}
          aria-pressed={selected}
          aria-keyshortcuts={hotkey ? String(hotkey) : undefined}
          onClick={() => onSelect(option)}
          onKeyDown={onKeyDown}
          className={cn(
            // накладка after:inset-0 робить клікабельним увесь рядок; «Чому?» і дія лежать над нею (z-10)
            'flex min-w-0 flex-1 flex-col items-start gap-0.5 text-start after:absolute after:inset-0 after:rounded-control',
            'focus-visible:outline-none focus-visible:after:outline-2 focus-visible:after:outline-offset-2 focus-visible:after:outline-focus',
          )}
        >
          <span className="flex flex-wrap items-baseline gap-x-2 text-meta text-muted">
            {kindLabel ? <span>{kindLabel}</span> : null}
            {option.recommended ? <span className="font-semibold text-accent">Рекомендовано</span> : null}
          </span>
          <span className="text-ui font-semibold text-pretty text-primary">{title}</span>
          {room ? (
            <span className="inline-flex items-center gap-1 text-caption text-secondary">
              <DoorOpen aria-hidden="true" className="size-3.5 text-muted" />
              ауд. {room}
            </span>
          ) : null}
        </button>
        <WhyPopover option={option} />
      </div>
      <OptionChips option={option} />
      {selected ? (
        <div className="relative z-10 flex flex-col gap-2 border-t border-separator pt-2.5">
          {actionExtra}
          <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
            <Button variant="primary" size="sm" loading={busy} disabled={disabled && !busy} onClick={() => onAct(option)}>
              {actionLabel}
            </Button>
            <span className="text-caption text-muted pointer-coarse:hidden">або Enter</span>
          </div>
        </div>
      ) : null}
    </li>
  );
}
