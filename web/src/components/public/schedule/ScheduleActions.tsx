import { CalendarArrowDown, CalendarPlus, ChevronDown, Copy, Smartphone } from 'lucide-react';
import { DropdownMenu } from 'radix-ui';
import { downloadIcs, icsUrls, type IcsOwner } from '@/api/public';
import type { Uuid } from '@/api/types';
import { Button, notify } from '@/components/ui';

const MENU =
  'anim-pop z-50 min-w-60 max-w-[calc(100vw-1rem)] rounded-panel border border-default bg-raised p-1 text-primary shadow-popover';
const ITEM =
  'flex cursor-default select-none items-center gap-2.5 rounded-control px-2.5 py-2 text-ui text-primary outline-none data-highlighted:bg-hover';
const LABEL = 'px-2.5 pt-1.5 pb-1 text-caption font-semibold text-muted';

export interface IcsTarget {
  owner: IcsOwner;
  id: Uuid;
  /** Імʼя файлу: `rozklad-4КІ1.ics`. */
  fileName: string;
}

/** Пункти календаря: файл, підписка на телефоні, Google Календар, копія адреси. */
function CalendarItems({ ics }: { ics: IcsTarget }) {
  const urls = icsUrls(ics.owner, ics.id);
  const download = async () => {
    try {
      await downloadIcs(ics.owner, ics.id, ics.fileName);
      notify.success(`Файл ${ics.fileName} завантажено`, { description: 'Відкрийте його — календар запропонує додати пари.' });
    } catch (error) {
      notify.error(error, 'Не вдалося завантажити календар');
    }
  };
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(urls.https);
      notify.success('Адресу календаря скопійовано', { description: 'Додайте її в календарі як «підписку за URL».' });
    } catch {
      notify.error('Не вдалося скопіювати адресу календаря.');
    }
  };
  return (
    <>
      <DropdownMenu.Item className={ITEM} onSelect={() => void download()}>
        <CalendarArrowDown aria-hidden="true" className="size-4 text-muted" />
        Завантажити файл .ics
      </DropdownMenu.Item>
      <DropdownMenu.Item asChild className={ITEM}>
        <a href={urls.webcal}>
          <Smartphone aria-hidden="true" className="size-4 text-muted" />
          Підписатися в календарі телефону
        </a>
      </DropdownMenu.Item>
      <DropdownMenu.Item asChild className={ITEM}>
        <a href={`https://calendar.google.com/calendar/render?cid=${encodeURIComponent(urls.webcal)}`} target="_blank" rel="noreferrer">
          <CalendarPlus aria-hidden="true" className="size-4 text-muted" />
          Додати в Google Календар
        </a>
      </DropdownMenu.Item>
      <DropdownMenu.Item className={ITEM} onSelect={() => void copy()}>
        <Copy aria-hidden="true" className="size-4 text-muted" />
        Копіювати адресу календаря
      </DropdownMenu.Item>
      <p className="max-w-64 px-2.5 pt-1 pb-1.5 text-caption text-muted">
        Підписка підтягує зміни сама (зазвичай раз на кілька годин). Файл — знімок на зараз.
      </p>
    </>
  );
}

/** Кнопка «У календар» з меню: файл .ics, підписка на телефоні, Google Календар, адреса. */
export function CalendarMenuButton({ ics, className }: { ics: IcsTarget; className?: string }) {
  return (
    <DropdownMenu.Root>
      <DropdownMenu.Trigger asChild>
        <Button
          variant="secondary"
          iconStart={<CalendarPlus aria-hidden="true" className="size-4" />}
          iconEnd={<ChevronDown aria-hidden="true" className="size-4 text-muted" />}
          className={className}
        >
          У календар
        </Button>
      </DropdownMenu.Trigger>
      <DropdownMenu.Portal>
        <DropdownMenu.Content align="end" sideOffset={6} className={MENU}>
          <DropdownMenu.Label className={LABEL}>Пари в особистому календарі</DropdownMenu.Label>
          <CalendarItems ics={ics} />
        </DropdownMenu.Content>
      </DropdownMenu.Portal>
    </DropdownMenu.Root>
  );
}
