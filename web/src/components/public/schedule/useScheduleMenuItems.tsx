import { CalendarPlus, Link2, Printer, QrCode, Share2, Star } from 'lucide-react';
import { notify, type MoreMenuItem } from '@/components/ui';
import { cn } from '@/lib/cn';
import { useFavoritesStore, type ScheduleTarget } from '@/stores/favorites';

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

interface ScheduleMenuOptions {
  target: ScheduleTarget;
  share: ShareOptions;
  /** «Додати в календар…» — вікно з вибором (телефон, Google, файл). Календар є лише для груп і викладачів (CONTRACTS §1). */
  onCalendar?: (() => void) | null;
  onPrintWeek: () => void;
  /** Табличка з QR на двері (групи й аудиторії). */
  onQr?: (() => void) | null;
}

const ICON = 'size-4';

/**
 * Усе другорядне сторінки розкладу — в одному меню «⋯», не більше шести пунктів: поділитися, посилання,
 * обране, «Додати в календар…», друк, QR. Підгрупа — не тут, а перемикачем поруч із тижнем.
 * У першу секунду людині потрібен розклад, а не вісім кнопок над ним.
 */
export function useScheduleMenuItems({ target, share, onCalendar, onPrintWeek, onQr }: ScheduleMenuOptions): MoreMenuItem[] {
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
  if (onCalendar) {
    items.push({ label: 'Додати в календар…', icon: <CalendarPlus aria-hidden="true" className={ICON} />, onSelect: onCalendar, separatorBefore: true });
  }
  items.push({ label: 'Друкувати тиждень', icon: <Printer aria-hidden="true" className={ICON} />, onSelect: onPrintWeek, separatorBefore: !onCalendar });
  if (onQr) items.push({ label: 'QR-код для дверей', icon: <QrCode aria-hidden="true" className={ICON} />, onSelect: onQr });
  return items;
}
