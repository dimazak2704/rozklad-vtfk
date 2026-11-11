import { Ellipsis } from 'lucide-react';
import { DropdownMenu } from 'radix-ui';
import type { ReactNode } from 'react';
import { Link } from 'react-router';
import { cn } from '@/lib/cn';
import { IconButton } from './IconButton';

export interface MoreMenuItem {
  label: string;
  icon?: ReactNode;
  /** Дія. Або `to` (перехід у застосунку), або `href` (зовнішнє посилання, нова вкладка). */
  onSelect?: () => void;
  to?: string;
  href?: string;
  tone?: 'default' | 'danger';
  disabled?: boolean;
  /** Розділювач перед цим пунктом. */
  separatorBefore?: boolean;
}

export interface MoreMenuProps {
  items: readonly MoreMenuItem[];
  /** Підпис кнопки для читача екрана й підказки. */
  label?: string;
  align?: 'start' | 'end';
  className?: string;
}

const ITEM = cn(
  'flex min-h-9 cursor-default select-none items-center gap-2.5 rounded-control px-2.5 py-2 text-ui text-primary outline-none',
  'data-highlighted:bg-hover data-disabled:text-disabled pointer-coarse:min-h-11',
);

/**
 * Меню «⋯» — усе другорядне, що не потрібне в першу секунду: поділитися, календар, QR, друк, фільтри.
 * Одне на заголовок сторінки чи панелі (правило «одна головна дія + ⋯»).
 */
export function MoreMenu({ items, label = 'Ще дії', align = 'end', className }: MoreMenuProps) {
  const visible = items.filter(Boolean);
  if (visible.length === 0) return null;
  return (
    <DropdownMenu.Root>
      <DropdownMenu.Trigger asChild>
        <IconButton label={label} icon={<Ellipsis aria-hidden="true" className="size-5" />} className={className} />
      </DropdownMenu.Trigger>
      <DropdownMenu.Portal>
        <DropdownMenu.Content
          align={align}
          sideOffset={6}
          collisionPadding={8}
          className="anim-pop z-50 min-w-56 rounded-popover border border-default bg-raised p-1.5 text-primary shadow-popover"
        >
          {visible.map((item) => {
            const content = (
              <>
                {item.icon ? <span className="text-muted [&_svg]:size-4">{item.icon}</span> : null}
                <span className="min-w-0 flex-1">{item.label}</span>
              </>
            );
            const tone = item.tone === 'danger' && 'text-danger data-highlighted:bg-danger-subtle [&_span]:text-danger';
            return (
              <div key={item.label}>
                {item.separatorBefore ? <DropdownMenu.Separator className="my-1 h-px bg-[var(--border-color-separator)]" /> : null}
                {item.to ? (
                  <DropdownMenu.Item asChild disabled={item.disabled} className={cn(ITEM, tone)}>
                    <Link to={item.to}>{content}</Link>
                  </DropdownMenu.Item>
                ) : item.href ? (
                  <DropdownMenu.Item asChild disabled={item.disabled} className={cn(ITEM, tone)}>
                    <a href={item.href} target="_blank" rel="noreferrer">
                      {content}
                    </a>
                  </DropdownMenu.Item>
                ) : (
                  <DropdownMenu.Item disabled={item.disabled} onSelect={item.onSelect} className={cn(ITEM, tone)}>
                    {content}
                  </DropdownMenu.Item>
                )}
              </div>
            );
          })}
        </DropdownMenu.Content>
      </DropdownMenu.Portal>
    </DropdownMenu.Root>
  );
}
