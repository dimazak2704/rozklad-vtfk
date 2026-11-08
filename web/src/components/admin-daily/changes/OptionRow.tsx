import { Check, CircleQuestionMark, TriangleExclamationPoint } from 'lucide-react';
import type { ChangeOption } from '@/api/admin-daily';
import { Badge, Button, Kbd, Popover, PopoverContent, PopoverTrigger, ScoreBar } from '@/components/ui';
import { cn } from '@/lib/cn';

/** Як рахується оцінка — коротко, словами DOMAIN §7 (для «Чому?»). */
const SCORING: Partial<Record<ChangeOption['kind'], readonly string[]>> = {
  REPLACE_TEACHER: [
    '+50 — веде цю дисципліну',
    '+20 — та сама циклова комісія',
    '+15 — закриває вікно в його дні',
    '+10 — уже має пари цього дня',
    '−8 за кожну заміну цього місяця (до −24)',
    '−15 — небажаний час за побажаннями',
  ],
  REPLACE_DISCIPLINE: ['Інша дисципліна групи, викладач якої вільний на цій парі', '+20 — якщо дисципліна відстає від плану'],
  SWAP: ['Пізніша пара групи стає на це місце', 'Краще, коли це остання пара: група піде раніше'],
  CANCEL: ['Краще, коли пара перша чи остання: група прийде пізніше або піде раніше', 'Гірше, якщо в середині дня утвориться вікно'],
  ADDITIONAL: ['Вільний час і групи, і викладача протягом двох тижнів', 'Поруч з іншими парами групи того дня'],
};

const MAX_INLINE_CHIPS = 3;

function WhyPopover({ option }: { option: ChangeOption }) {
  const rules = SCORING[option.kind] ?? [];
  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button
          variant="ghost"
          size="sm"
          iconStart={<CircleQuestionMark aria-hidden="true" className="size-4" />}
          aria-label={`Чому ${option.title}: оцінка ${option.score}`}
        >
          Чому?
        </Button>
      </PopoverTrigger>
      <PopoverContent width="md" align="end" title={`${option.title}: ${option.score} зі 100`}>
        <div className="flex flex-col gap-3 text-ui">
          {option.reasons.length > 0 ? (
            <ul className="flex flex-col gap-1.5" aria-label="На користь">
              {option.reasons.map((reason) => (
                <li key={reason} className="flex items-start gap-2">
                  <Check aria-hidden="true" className="mt-0.5 size-4 text-add" />
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
                  <TriangleExclamationPoint aria-hidden="true" className="mt-0.5 size-4 text-change" />
                  <span>{warning}</span>
                </li>
              ))}
            </ul>
          ) : null}
          {rules.length > 0 ? (
            <div className="border-t border-separator pt-2.5">
              <p className="text-caption font-semibold text-secondary">Як рахуємо</p>
              <ul className="mt-1 flex flex-col gap-0.5 text-caption text-muted">
                {rules.map((rule) => (
                  <li key={rule} className="figures">
                    {rule}
                  </li>
                ))}
              </ul>
            </div>
          ) : null}
        </div>
      </PopoverContent>
    </Popover>
  );
}

interface OptionRowProps {
  option: ChangeOption;
  /** Номер у списку: 1–9 — клавіша вибору. */
  rank: number;
  best: boolean;
  busy: boolean;
  disabled: boolean;
  /** Напис кнопки вибору: «Призначити», «Надіслати прохання» чи «Обрати» (model.pickLabel). */
  pickLabel?: string;
  onPick: (option: ChangeOption) => void;
}

/** Варіант рішення: хто чи що, оцінка смугою, причини чипами, «Чому?» і кнопка вибору. */
export function OptionRow({ option, rank, best, busy, disabled, pickLabel = 'Обрати', onPick }: OptionRowProps) {
  const chips = [
    ...option.reasons.map((text) => ({ text, warning: false })),
    ...option.warnings.map((text) => ({ text, warning: true })),
  ];
  const hidden = chips.length - MAX_INLINE_CHIPS;
  const incomplete = option.kind === 'ADDITIONAL' && (!option.date || !option.pair);
  return (
    <li
      className={cn(
        'grid grid-cols-[1.5rem_minmax(0,1fr)_auto] items-start gap-x-3 gap-y-1.5 rounded-control border px-3 py-2.5',
        best ? 'border-accent-subtle bg-selected/40' : 'border-separator',
      )}
    >
      <span className="pt-1" aria-hidden={rank > 9}>
        {rank <= 9 ? <Kbd className={best ? 'border-accent text-accent' : undefined}>{rank}</Kbd> : null}
      </span>
      <div className="flex min-w-0 flex-col gap-1 pt-0.5">
        <p className="flex min-w-0 flex-wrap items-baseline gap-x-2">
          <span className="truncate text-ui font-semibold text-primary">{option.title}</span>
          {best ? <span className="text-meta font-semibold text-accent">найкращий збіг</span> : null}
        </p>
        <ScoreBar value={option.score} size="sm" label={`Придатність: ${option.title}`} className="max-w-48" />
      </div>
      <div className="flex items-center gap-0.5">
        <WhyPopover option={option} />
        <Button
          size="sm"
          variant={best ? 'primary' : 'secondary'}
          loading={busy}
          disabled={disabled || incomplete}
          onClick={() => onPick(option)}
          aria-label={`${pickLabel}: ${option.title}`}
          aria-keyshortcuts={rank <= 9 ? String(rank) : undefined}
        >
          {pickLabel}
        </Button>
      </div>
      {chips.length > 0 ? (
        <ul className="col-span-2 col-start-2 flex flex-wrap gap-1" aria-label="Причини">
          {chips.slice(0, MAX_INLINE_CHIPS).map((chip) => (
            <li key={chip.text}>
              <Badge size="sm" tone={chip.warning ? 'warning' : 'neutral'}>
                {chip.text}
              </Badge>
            </li>
          ))}
          {hidden > 0 ? (
            <li>
              <Badge size="sm">ще {hidden}</Badge>
            </li>
          ) : null}
        </ul>
      ) : null}
    </li>
  );
}
