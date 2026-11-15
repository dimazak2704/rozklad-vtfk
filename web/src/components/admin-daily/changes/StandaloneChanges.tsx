import { UserRoundPen, Undo2 } from 'lucide-react';
import type { ChangeDto } from '@/api/types';
import { Button, MoreMenu } from '@/components/ui';
import { cn } from '@/lib/cn';
import { decisionSummary } from '../format';
import { prefillFromDeclined, standaloneHeading, type NewChangePrefill } from './new-change';
import type { TeacherLabel } from '../teacher-label';
import { useTeacherLabel } from '../useTeacherLabel';

const STATUS_WORD: Record<ChangeDto['status'], string> = {
  DRAFT: 'чернетка',
  PENDING_CONFIRMATION: 'чекає відповіді',
  CONFIRMED: 'підтверджено',
  DECLINED: 'відмова',
  CANCELLED: 'скасовано',
  APPLIED: 'у розпорядженні',
};

interface StandaloneChangesProps {
  changes: readonly ChangeDto[];
  showDates: boolean;
  onCancel: (change: ChangeDto) => void;
  /** Після відмови викладача: та сама пара, інший викладач (форма «Нова зміна» з усім, крім викладача). */
  onReplace: (prefill: NewChangePrefill) => void;
}

/**
 * Список «Інші зміни»: додаткові пари, заміни аудиторій і ручні заміни, що не повʼязані з відсутністю.
 * Без нього створена через «Нову зміну» пара не була б видна на цій сторінці — лише в розпорядженні.
 */
export function StandaloneChanges({ changes, showDates, onCancel, onReplace }: StandaloneChangesProps) {
  const name: TeacherLabel = useTeacherLabel();
  if (changes.length === 0) return null;
  return (
    <section aria-labelledby="standalone-heading" className="flex flex-col gap-1">
      <h2 id="standalone-heading" className="px-3 pt-2 text-ui font-[650] text-secondary">
        Інші зміни
      </h2>
      <ul className="flex flex-col gap-0.5">
        {changes.map((change) => {
          const declined = change.status === 'DECLINED';
          // відмова: пари немає, доки диспетчер не обере іншого викладача чи не прибере її
          const retry = declined ? prefillFromDeclined(change) : null;
          return (
            <li key={change.id} className="flex flex-col gap-2 rounded-popover px-3 py-2">
              <span className="flex items-start gap-2">
                <span className="flex min-w-0 flex-1 flex-col gap-0.5">
                  <span className="text-cell font-[650] text-primary">{standaloneHeading(change, showDates)}</span>
                  <span className="text-caption text-secondary">
                    {decisionSummary(change, name)} ·{' '}
                    <span className={cn(declined ? 'font-semibold text-change' : 'text-muted')}>{STATUS_WORD[change.status]}</span>
                  </span>
                </span>
                {change.status !== 'APPLIED' ? (
                  <MoreMenu
                    label={`Дії зі зміною: ${standaloneHeading(change, true)}`}
                    items={[
                      {
                        label: declined ? 'Прибрати зміну' : 'Скасувати зміну',
                        icon: <Undo2 aria-hidden="true" />,
                        tone: 'danger',
                        onSelect: () => onCancel(change),
                      },
                    ]}
                  />
                ) : null}
              </span>
              {retry ? (
                <Button
                  size="sm"
                  variant="secondary"
                  className="self-start"
                  iconStart={<UserRoundPen aria-hidden="true" className="size-4" />}
                  onClick={() => onReplace(retry)}
                >
                  Інший викладач…
                </Button>
              ) : null}
            </li>
          );
        })}
      </ul>
    </section>
  );
}
