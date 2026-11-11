import { CalendarArrowDown, CalendarPlus, Check, Link2, Printer, QrCode, Share2, Smartphone, Star } from 'lucide-react';
import { downloadIcs, icsUrls } from '@/api/public';
import { notify, type MoreMenuItem } from '@/components/ui';
import { cn } from '@/lib/cn';
import { useFavoritesStore, type ScheduleTarget } from '@/stores/favorites';
import type { IcsTarget } from './ScheduleActions';

interface ShareOptions {
  title: string;
  /** Текст повідомлення (розклад дня); рахується в момент натискання. */
  text: () => string;
  url: string;
}

/**
 * Системне «Поділитися» — лише на телефоні (там воно звичне); на компʼютері копіюємо розклад дня з
 * посиланням у буфер і кажемо про це, щоб натискання ніколи не лишалося без відповіді.
 */
async function shareSchedule({ title, text, url }: ShareOptions) {
  const body = text();
  const data: ShareData = { title, text: body, url };
  const touch = window.matchMedia('(pointer: coarse)').matches;
  if (touch && typeof navigator.share === 'function' && (typeof navigator.canShare !== 'function' || navigator.canShare(data))) {
    try {
      await navigator.share(data);
      return;
    } catch (error) {
      // людина закрила вікно «Поділитися» — це не помилка
      if (error instanceof DOMException && error.name === 'AbortError') return;
    }
  }
  try {
    await navigator.clipboard.writeText(`${body}
${url}`);
    notify.success('Розклад дня скопійовано — вставте його в чат групи');
  } catch {
    notify.error('Не вдалося скопіювати: браузер не дав доступу до буфера обміну. Скопіюйте адресу сторінки вручну.');
  }
}

async function downloadCalendar(ics: IcsTarget) {
  try {
    await downloadIcs(ics.owner, ics.id, ics.fileName);
    notify.success(`Файл ${ics.fileName} завантажено — відкрийте його, і календар запропонує додати пари`);
  } catch (error) {
    notify.error(error, 'Не вдалося завантажити календар');
  }
}

interface ScheduleMenuOptions {
  target: ScheduleTarget;
  share: ShareOptions;
  /** Календар є лише для груп і викладачів (CONTRACTS §1). */
  ics?: IcsTarget | null;
  onPrintWeek: () => void;
  /** Табличка з QR на двері (групи й аудиторії). */
  onQr?: (() => void) | null;
  /** Підгрупи групи: фільтр «Лише 1 підгрупа» живе в меню, а не окремим перемикачем. */
  subgroups?: readonly number[];
  subgroup?: number | null;
  onSubgroup?: (subgroup: number | null) => void;
}

const ICON = 'size-4';

/**
 * Усе другорядне сторінки розкладу — в одному меню «⋯»: поділитися, обране, календар, друк, QR, підгрупа.
 * У першу секунду людині потрібен розклад, а не вісім кнопок над ним.
 */
export function useScheduleMenuItems({
  target,
  share,
  ics,
  onPrintWeek,
  onQr,
  subgroups = [],
  subgroup = null,
  onSubgroup,
}: ScheduleMenuOptions): MoreMenuItem[] {
  const favorite = useFavoritesStore((state) => state.favorites.some((f) => f.type === target.type && f.id === target.id));
  const toggle = useFavoritesStore((state) => state.toggleFavorite);
  const items: MoreMenuItem[] = [
    { label: 'Поділитися', icon: <Share2 aria-hidden="true" className={ICON} />, onSelect: () => void shareSchedule(share) },
    {
      label: 'Копіювати посилання',
      icon: <Link2 aria-hidden="true" className={ICON} />,
      onSelect: () => {
        void navigator.clipboard.writeText(share.url).then(
          () => notify.success('Посилання скопійовано'),
          () => notify.error('Не вдалося скопіювати посилання.'),
        );
      },
    },
    {
      label: favorite ? 'Прибрати з обраного' : 'Додати в обране',
      icon: <Star aria-hidden="true" className={cn(ICON, favorite && 'fill-current')} />,
      onSelect: () => {
        toggle(target);
        if (favorite) notify.undo(`${target.label} прибрано з обраного`, { onUndo: () => toggle(target) });
        else notify.success(`${target.label} в обраному — його видно на головній`);
      },
    },
  ];
  if (ics) {
    const urls = icsUrls(ics.owner, ics.id);
    items.push(
      { label: 'Підписатися в календарі телефону', icon: <Smartphone aria-hidden="true" className={ICON} />, href: urls.webcal, separatorBefore: true },
      {
        label: 'Додати в Google Календар',
        icon: <CalendarPlus aria-hidden="true" className={ICON} />,
        href: `https://calendar.google.com/calendar/render?cid=${encodeURIComponent(urls.webcal)}`,
      },
      { label: 'Завантажити файл .ics', icon: <CalendarArrowDown aria-hidden="true" className={ICON} />, onSelect: () => void downloadCalendar(ics) },
    );
  }
  items.push({ label: 'Друкувати тиждень', icon: <Printer aria-hidden="true" className={ICON} />, onSelect: onPrintWeek, separatorBefore: true });
  if (onQr) items.push({ label: 'QR-код для дверей', icon: <QrCode aria-hidden="true" className={ICON} />, onSelect: onQr });
  if (subgroups.length > 0 && onSubgroup) {
    const mark = (on: boolean) => (on ? <Check aria-hidden="true" className={ICON} /> : <span aria-hidden="true" className="inline-block size-4" />);
    items.push(
      { label: 'Уся група', icon: mark(subgroup === null), onSelect: () => onSubgroup(null), separatorBefore: true },
      ...subgroups.map((n) => ({ label: `Лише ${n} підгрупа`, icon: mark(subgroup === n), onSelect: () => onSubgroup(n) })),
    );
  }
  return items;
}
