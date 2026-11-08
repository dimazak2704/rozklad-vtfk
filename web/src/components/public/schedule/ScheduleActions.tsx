import {
  CalendarArrowDown,
  CalendarPlus,
  ChevronDown,
  Copy,
  Ellipsis,
  Link2,
  Printer,
  QrCode,
  Share2,
  Smartphone,
  Star,
} from 'lucide-react';
import { DropdownMenu } from 'radix-ui';
import type { ReactNode } from 'react';
import { downloadIcs, icsUrls, type IcsOwner } from '@/api/public';
import type { Uuid } from '@/api/types';
import { Button, IconButton, notify } from '@/components/ui';
import { cn } from '@/lib/cn';
import { useFavoritesStore, type ScheduleTarget } from '@/stores/favorites';

const MENU =
  'anim-pop z-50 min-w-60 max-w-[calc(100vw-1rem)] rounded-panel border border-default bg-raised p-1 text-primary shadow-popover';
const ITEM =
  'flex cursor-default select-none items-center gap-2.5 rounded-control px-2.5 py-2 text-ui text-primary outline-none data-highlighted:bg-hover';
const LABEL = 'px-2.5 pt-1.5 pb-1 text-caption font-semibold text-muted';
const SEPARATOR = 'my-1 h-px bg-[var(--border-color-separator)]';

/** Додати в обране / прибрати: обране видно на головній і в пошуку. */
export function FavoriteButton({ target }: { target: ScheduleTarget }) {
  const favorite = useFavoritesStore((state) => state.favorites.some((f) => f.type === target.type && f.id === target.id));
  const toggle = useFavoritesStore((state) => state.toggleFavorite);
  return (
    <IconButton
      variant="secondary"
      label={favorite ? `Прибрати ${target.label} з обраного` : `Додати ${target.label} в обране`}
      pressed={favorite}
      icon={<Star aria-hidden="true" className={cn('size-4', favorite && 'fill-current')} />}
      onClick={() => {
        toggle(target);
        if (favorite) {
          notify.undo(`${target.label} прибрано з обраного`, { onUndo: () => toggle(target) });
        } else {
          notify.success(`${target.label} в обраному`, { description: 'Розклад буде одразу на головній сторінці.' });
        }
      }}
    />
  );
}

interface ShareButtonProps {
  title: string;
  /** Текст повідомлення (розклад дня); рахується в момент натискання. */
  text: () => string;
  url: string;
  className?: string;
}

/** Web Share API на телефоні; де його немає — копіюємо розклад дня з посиланням у буфер. */
export function ShareButton({ title, text, url, className }: ShareButtonProps) {
  const share = async () => {
    const body = text();
    const data: ShareData = { title, text: body, url };
    if (typeof navigator.share === 'function' && (typeof navigator.canShare !== 'function' || navigator.canShare(data))) {
      try {
        await navigator.share(data);
        return;
      } catch (error) {
        // людина закрила вікно «Поділитися» — це не помилка
        if (error instanceof DOMException && error.name === 'AbortError') return;
      }
    }
    try {
      await navigator.clipboard.writeText(`${body}\n${url}`);
      notify.success('Розклад скопійовано', { description: 'Вставте його в чат групи чи нотатки.' });
    } catch {
      notify.error('Не вдалося скопіювати: браузер не дав доступу до буфера обміну. Скопіюйте адресу сторінки вручну.');
    }
  };
  return (
    <>
      <Button
        variant="secondary"
        iconStart={<Share2 aria-hidden="true" className="size-4" />}
        onClick={() => void share()}
        className={cn('hidden sm:inline-flex', className)}
      >
        Поділитися
      </Button>
      <IconButton
        variant="secondary"
        label="Поділитися розкладом"
        icon={<Share2 aria-hidden="true" className="size-4" />}
        onClick={() => void share()}
        className="sm:hidden"
      />
    </>
  );
}

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

interface ScheduleActionsProps {
  target: ScheduleTarget;
  share: Omit<ShareButtonProps, 'className'>;
  /** Календар є лише для груп і викладачів (CONTRACTS §1). */
  ics?: IcsTarget | null;
  onPrintWeek: () => void;
  /** Табличка з QR на двері (групи й аудиторії). */
  onQr?: (() => void) | null;
  /** Додаткові кнопки перед меню. */
  children?: ReactNode;
}

/** Дії сторінки розкладу: обране, поділитися, календар, друк, QR. На телефоні частина — у меню «Ще». */
export function ScheduleActions({ target, share, ics, onPrintWeek, onQr, children }: ScheduleActionsProps) {
  return (
    <div className="flex items-center gap-2">
      <FavoriteButton target={target} />
      <ShareButton {...share} />
      {children}
      {ics ? <CalendarMenuButton ics={ics} className="hidden md:inline-flex" /> : null}
      <DropdownMenu.Root>
        <DropdownMenu.Trigger asChild>
          <IconButton variant="secondary" label="Ще дії" icon={<Ellipsis aria-hidden="true" className="size-4" />} />
        </DropdownMenu.Trigger>
        <DropdownMenu.Portal>
          <DropdownMenu.Content align="end" sideOffset={6} className={MENU}>
            {ics ? (
              <div className="md:hidden">
                <DropdownMenu.Label className={LABEL}>Пари в особистому календарі</DropdownMenu.Label>
                <CalendarItems ics={ics} />
                <DropdownMenu.Separator className={SEPARATOR} />
              </div>
            ) : null}
            <DropdownMenu.Item className={ITEM} onSelect={onPrintWeek}>
              <Printer aria-hidden="true" className="size-4 text-muted" />
              <span className="flex-1">Друкувати тиждень (A4)</span>
              <span className="text-caption text-muted">Ctrl+P</span>
            </DropdownMenu.Item>
            {onQr ? (
              <DropdownMenu.Item className={ITEM} onSelect={onQr}>
                <QrCode aria-hidden="true" className="size-4 text-muted" />
                QR-код для дверей
              </DropdownMenu.Item>
            ) : null}
            <DropdownMenu.Item
              className={ITEM}
              onSelect={() => {
                void navigator.clipboard.writeText(share.url).then(
                  () => notify.success('Посилання скопійовано'),
                  () => notify.error('Не вдалося скопіювати посилання.'),
                );
              }}
            >
              <Link2 aria-hidden="true" className="size-4 text-muted" />
              Копіювати посилання
            </DropdownMenu.Item>
          </DropdownMenu.Content>
        </DropdownMenu.Portal>
      </DropdownMenu.Root>
    </div>
  );
}
