import { CalendarPlus, FilePlus2, FileText } from 'lucide-react';
import { Link } from 'react-router';
import type { IsoDate } from '@/api/types';
import { Button, buttonClass, PageHeader, SegmentedControl, Skeleton, Tooltip } from '@/components/ui';
import { tallyEntries, type LessonEntry } from '../model';
import { ResolvedProgress } from '../ResolvedProgress';
import type { Period } from './useChangesWorkspace';

interface ChangesHeaderProps {
  date: IsoDate;
  period: Period;
  onPeriodChange: (period: Period) => void;
  /** Лічильник «вирішено»: прихований (вихідний, ніхто не відсутній), силует (дані ще їдуть) чи готовий. */
  progress: 'hidden' | 'settling' | 'ready';
  entries: readonly LessonEntry[];
  /** Телефон на рівні пар: «Нова відсутність» доступна лише з меню `⋯`. */
  newAbsenceInMenu: boolean;
  /** Вузький екран: «Нова зміна» й «Розпорядження» переїжджають у меню `⋯`. */
  narrow: boolean;
  onNewAbsence: () => void;
  onNewChange: () => void;
}

/** Шапка екрана замін: період, «Нова зміна» й перехід до розпорядження дня. */
export function ChangesHeader({ date, period, onPeriodChange, progress, entries, newAbsenceInMenu, narrow, onNewAbsence, onNewChange }: ChangesHeaderProps) {
  const orderUrl = `/admin/orders?date=${date}`;
  const tally = tallyEntries(entries);
  const meta =
    progress === 'hidden' ? null : progress === 'settling' ? (
      // поки пари й рішення їдуть, лічильник збрехав би («1 з 3» замість «4 з 8») — тримаємо місце
      <span aria-hidden="true" className="flex h-[1.375rem] items-center">
        <Skeleton className="h-3 w-48" />
      </span>
    ) : entries.length > 0 ? (
      <ResolvedProgress done={tally.done} waiting={tally.waiting} total={tally.total} />
    ) : null;
  return (
    <PageHeader
      title="Заміни"
      meta={meta}
      controls={
        <>
          <SegmentedControl<Period>
            aria-label="Період"
            value={period}
            onValueChange={onPeriodChange}
            options={[
              { value: 'day', label: 'День' },
              { value: 'week', label: 'Тиждень' },
            ]}
          />
          <Tooltip content="Додаткова пара, інша аудиторія чи заміна без відсутності" shortcut="c">
            <Button variant="secondary" onClick={onNewChange} iconStart={<FilePlus2 aria-hidden="true" className="size-4" />} className="max-sm:hidden">
              Нова зміна
            </Button>
          </Tooltip>
          <Tooltip content="Відкрити розпорядження на цей день">
            <Link
              to={orderUrl}
              className={buttonClass({
                variant: 'secondary',
                className: 'max-sm:hidden',
              })}
            >
              <FileText aria-hidden="true" className="size-4" />
              Розпорядження
            </Link>
          </Tooltip>
        </>
      }
      // `⋯` тримає лише те, чого на екрані немає: «Додати відсутність» видно в списку відсутніх, тож у меню
      // вона потрібна тільки на телефоні на рівні пар; кнопка «Розпорядження» ховається на вузькому
      more={[
        ...(newAbsenceInMenu
          ? [
              {
                label: 'Нова відсутність',
                icon: <CalendarPlus aria-hidden="true" />,
                onSelect: onNewAbsence,
              },
            ]
          : []),
        ...(narrow
          ? [
              {
                label: 'Нова зміна',
                icon: <FilePlus2 aria-hidden="true" />,
                onSelect: onNewChange,
              },
              {
                label: 'Розпорядження',
                icon: <FileText aria-hidden="true" />,
                to: orderUrl,
              },
            ]
          : []),
      ]}
    />
  );
}
