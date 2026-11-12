import { Check } from 'lucide-react';
import { Dialog, DialogContent } from '@/components/ui';
import { cn } from '@/lib/cn';

interface SubgroupDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Код групи для заголовка: «Підгрупа 4КІ1». */
  groupLabel: string;
  subgroups: readonly number[];
  value: number | null;
  onChange: (subgroup: number | null) => void;
}

/**
 * Вибір підгрупи — коротким списком у вікні (на телефоні — шторка знизу). Натискання одразу застосовує
 * вибір і закриває вікно, як у системному аркуші дій: тут нічого підтверджувати, а змінити можна будь-коли.
 */
export function SubgroupDialog({ open, onOpenChange, groupLabel, subgroups, value, onChange }: SubgroupDialogProps) {
  const options: { value: number | null; label: string }[] = [
    { value: null, label: 'Уся група' },
    ...subgroups.map((n) => ({ value: n, label: `${n} підгрупа` })),
  ];
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        size="sm"
        title={`Підгрупа ${groupLabel}`}
        description="Пари іншої підгрупи сховаються. Вибір запамʼятається для цієї групи."
      >
        <ul className="-mx-1.5 flex flex-col">
          {options.map((option) => {
            const selected = option.value === value;
            return (
              <li key={option.label}>
                <button
                  type="button"
                  aria-pressed={selected}
                  onClick={() => {
                    onChange(option.value);
                    onOpenChange(false);
                  }}
                  className={cn(
                    'flex min-h-11 w-full items-center justify-between gap-3 rounded-control px-3 text-start text-body text-primary',
                    'transition-[background-color] duration-150 hover:bg-hover',
                    'focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus',
                    selected && 'font-semibold',
                  )}
                >
                  {option.label}
                  {selected ? <Check aria-hidden="true" className="size-4 text-accent" /> : null}
                </button>
              </li>
            );
          })}
        </ul>
      </DialogContent>
    </Dialog>
  );
}
