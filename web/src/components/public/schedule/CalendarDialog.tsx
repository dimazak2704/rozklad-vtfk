import { CalendarArrowDown, CalendarPlus, ChevronRight, Smartphone, type LucideIcon } from 'lucide-react';
import type { ReactNode } from 'react';
import { downloadIcs, icsUrls } from '@/api/public';
import { Dialog, DialogContent, notify } from '@/components/ui';
import { cn } from '@/lib/cn';
import type { IcsTarget } from './ScheduleActions';

const ROW = cn(
  'flex min-h-14 w-full items-center gap-3 rounded-control px-3 py-2.5 text-start transition-colors duration-100',
  'hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus',
);

function RowBody({ icon: Icon, title, caption }: { icon: LucideIcon; title: string; caption: ReactNode }) {
  return (
    <>
      <Icon aria-hidden="true" className="size-5 shrink-0 text-muted" />
      <span className="flex min-w-0 flex-1 flex-col">
        <span className="text-ui font-[650] text-primary">{title}</span>
        <span className="text-caption text-secondary">{caption}</span>
      </span>
      <ChevronRight aria-hidden="true" className="size-4 shrink-0 text-muted rtl:rotate-180" />
    </>
  );
}

/**
 * «Додати в календар…» — один пункт меню замість трьох: тут людина вибирає, куди саме.
 * Підписка оновлюється сама, файл — знімок на зараз; про це каже підпис рядка, а не окремий абзац.
 */
export function CalendarDialog({
  ics,
  label,
  open,
  onOpenChange,
}: {
  ics: IcsTarget;
  /** «4КІ1» — у заголовку вікна. */
  label: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const urls = icsUrls(ics.owner, ics.id);
  const close = () => onOpenChange(false);
  const download = async () => {
    close();
    try {
      await downloadIcs(ics.owner, ics.id, ics.fileName);
      notify.success(`Файл ${ics.fileName} завантажено — відкрийте його, і календар запропонує додати пари`);
    } catch (error) {
      notify.error(error, 'Не вдалося завантажити календар');
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent title={`Пари ${label} у календарі`} size="sm">
        <ul className="-mx-3 flex flex-col">
          <li>
            <a href={urls.webcal} className={ROW} onClick={close}>
              <RowBody icon={Smartphone} title="Календар телефону" caption="Підписка: заміни підтягуються самі" />
            </a>
          </li>
          <li>
            <a
              href={`https://calendar.google.com/calendar/render?cid=${encodeURIComponent(urls.webcal)}`}
              target="_blank"
              rel="noreferrer"
              className={ROW}
              onClick={close}
            >
              <RowBody
                icon={CalendarPlus}
                title="Google Календар"
                caption={
                  <>
                    Підписка в новій вкладці<span className="sr-only"> (відкриється в новій вкладці)</span>
                  </>
                }
              />
            </a>
          </li>
          <li>
            <button type="button" className={ROW} onClick={() => void download()}>
              <RowBody icon={CalendarArrowDown} title="Файл .ics" caption="Знімок на зараз, без оновлень" />
            </button>
          </li>
        </ul>
      </DialogContent>
    </Dialog>
  );
}
