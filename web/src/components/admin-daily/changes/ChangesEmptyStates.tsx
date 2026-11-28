import { FilePlus2, Plus } from 'lucide-react';
import type { ReactNode } from 'react';
import type { AbsenceDto, IsoDate } from '@/api/types';
import { Button, EmptyState, LoadingRegion, Skeleton, Tooltip } from '@/components/ui';
import { formatWeekdayDate, formatWeekdayDateAfterDo, formatWeekdayDateAfterNa } from '@/lib/date';
import { useTeacherLabel } from '../useTeacherLabel';
import type { LessonsStatus, Period } from './useChangesWorkspace';

/** Вихідний: пар немає — дорога до найближчого навчального дня або до тижня. */
export function WeekendState({ date, nextStudyDay, onNextDay, onShowWeek }: { date: IsoDate; nextStudyDay: IsoDate; onNextDay: () => void; onShowWeek: () => void }) {
  return (
    <EmptyState
      title={`${formatWeekdayDate(date)} — вихідний, пар немає.`}
      description="Відсутності на вихідні не потрібні."
      action={
        <Button variant="secondary" onClick={onNextDay}>
          До {formatWeekdayDateAfterDo(nextStudyDay)}
        </Button>
      }
      secondaryAction={
        <Button variant="ghost" onClick={onShowWeek}>
          Показати тиждень
        </Button>
      }
    />
  );
}

interface NoAbsencesStateProps {
  date: IsoDate;
  period: Period;
  onNewAbsence: () => void;
  onNewChange: () => void;
  /** Окремі зміни без відсутності (якщо є) — під порожнім станом. */
  others: ReactNode;
}

/** Ніхто не відсутній: як зʼявляються відсутні й де додати зміну без відсутності. */
export function NoAbsencesState({ date, period, onNewAbsence, onNewChange, others }: NoAbsencesStateProps) {
  return (
    <div className="flex flex-col gap-4">
      <EmptyState
        title={period === 'day' ? `На ${formatWeekdayDateAfterNa(date)} ніхто не відсутній.` : 'Цього тижня ніхто не відсутній.'}
        description="Коли викладач повідомить про відсутність через бота чи кабінет, його пари зʼявляться тут разом з варіантами заміни. Додаткову пару, іншу аудиторію чи ручну заміну без відсутності додає «Нова зміна»."
        action={
          <Tooltip content={null} shortcut="n">
            <Button variant="ghost" className="-ms-3" onClick={onNewAbsence} iconStart={<Plus aria-hidden="true" className="size-4" />}>
              Додати відсутність
            </Button>
          </Tooltip>
        }
        secondaryAction={
          <Button variant="ghost" onClick={onNewChange} iconStart={<FilePlus2 aria-hidden="true" className="size-4" />}>
            Нова зміна
          </Button>
        }
      />
      {others ? <div className="rounded-panel border border-default bg-surface p-3">{others}</div> : null}
    </div>
  );
}

interface LessonsPlaceholderProps {
  absence: AbsenceDto | null;
  status: LessonsStatus | undefined;
  loading: boolean;
  before?: ReactNode;
}

/** Пари обраного відсутнього ще в дорозі (чи не прийшли) — не порожня колонка, а силует чи пояснення. */
export function LessonsPlaceholder({ absence, status, loading, before }: LessonsPlaceholderProps) {
  const name = useTeacherLabel();
  if (!absence && !loading) return null;
  return (
    <div className="flex flex-col gap-3">
      {before}
      {!absence || status === 'pending' ? (
        <LoadingRegion label="Завантажуємо пари…" className="flex flex-col gap-2 px-2">
          <Skeleton className="h-5 w-1/2" />
          {[0, 1, 2].map((key) => (
            <Skeleton key={key} className="h-16 w-full rounded-popover" />
          ))}
        </LoadingRegion>
      ) : (
        <p className="px-3 py-2 text-body text-secondary">
          {status === 'failed' ? `Пари ${name(absence.teacher)} не вдалося завантажити.` : `У ${name(absence.teacher)} пар у цей період немає.`}
        </p>
      )}
    </div>
  );
}
