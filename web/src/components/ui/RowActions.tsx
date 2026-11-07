import { Ellipsis } from 'lucide-react';
import { DropdownMenu } from 'radix-ui';
import type { ReactNode } from 'react';
import { cn } from '@/lib/cn';
import { IconButton } from './IconButton';

export interface RowAction {
  label: string;
  icon?: ReactNode;
  onSelect: () => void;
  tone?: 'default' | 'danger';
  disabled?: boolean;
  /** Розділювач перед цим пунктом. */
  separatorBefore?: boolean;
}

interface RowActionsProps {
  actions: readonly RowAction[];
  /** Назва рядка для підпису кнопки: «Дії: Кренцін М.Д.». */
  rowLabel: string;
}

/** Меню дій рядка таблиці. Небезпечні дії — останніми й червоним, з підтвердженням у самій дії. */
export function RowActions({ actions, rowLabel }: RowActionsProps) {
  if (actions.length === 0) return null;
  return (
    <DropdownMenu.Root>
      <DropdownMenu.Trigger asChild>
        <IconButton label={`Дії: ${rowLabel}`} icon={<Ellipsis aria-hidden="true" className="size-4" />} size="sm" />
      </DropdownMenu.Trigger>
      <DropdownMenu.Portal>
        <DropdownMenu.Content
          align="end"
          sideOffset={4}
          collisionPadding={8}
          className="anim-pop z-50 min-w-48 rounded-panel border border-default bg-raised p-1 text-primary shadow-popover"
        >
          {actions.map((action) => (
            <div key={action.label}>
              {action.separatorBefore ? <DropdownMenu.Separator className="my-1 h-px bg-[var(--border-color-separator)]" /> : null}
              <DropdownMenu.Item
                disabled={action.disabled}
                onSelect={action.onSelect}
                className={cn(
                  'flex cursor-default select-none items-center gap-2 rounded-control px-2.5 py-2 text-ui outline-none',
                  'data-highlighted:bg-hover data-disabled:text-disabled',
                  action.tone === 'danger' && 'text-danger data-highlighted:bg-danger-subtle',
                )}
              >
                {action.icon ? <span className="[&_svg]:size-4">{action.icon}</span> : null}
                {action.label}
              </DropdownMenu.Item>
            </div>
          ))}
        </DropdownMenu.Content>
      </DropdownMenu.Portal>
    </DropdownMenu.Root>
  );
}
