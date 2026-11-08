import { useMemo, useState } from 'react';
import { useDayPlan } from '@/api/admin-daily';
import type { AbsenceDto, IsoDate, PlannedLessonDto, Uuid } from '@/api/types';
import { Button, Dialog, DialogClose, DialogContent, ErrorState, LoadingRegion, Plate, Select, Skeleton } from '@/components/ui';
import { formatWeekdayDate } from '@/lib/date';
import { subgroupLabel } from '@/lib/labels';
import { optionKey, optionTitle, planActionLabel, planSummary, type PlanChoice } from '../model';
import { OptionChips } from './OptionRow';

const SKIP = 'skip';

/** Вибір за замовчуванням: пропозиція сервера, для минулої пари чи без пропозиції — «не вирішувати». */
function proposedKey(item: PlannedLessonDto): string {
  const proposed = item.proposedIndex !== null ? item.options[item.proposedIndex] : undefined;
  return proposed && !item.past ? optionKey(proposed) : SKIP;
}

interface DayPlanDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  absence: AbsenceDto;
  date: IsoDate;
  approvalMode: string | null | undefined;
  busy: boolean;
  onApply: (choices: PlanChoice[]) => void;
}

/**
 * «Запропонувати план дня»: сервер пропонує по варіанту на кожну невирішену пару відсутнього цього дня,
 * диспетчер переглядає й міняє будь-який вибір. Нічого не створено й не надіслано до кнопки внизу; після неї —
 * одна транзакція (усе або нічого) і тост зі «Скасувати».
 */
export function DayPlanDialog({ open, onOpenChange, absence, date, approvalMode, busy, onApply }: DayPlanDialogProps) {
  const plan = useDayPlan(open ? absence.id : null, date);
  const [overrides, setOverrides] = useState<Record<Uuid, string>>({});
  const lessons = useMemo(() => plan.data?.lessons ?? [], [plan.data]);

  const choices = useMemo(
    () =>
      lessons.flatMap((item) => {
        const key = overrides[item.lesson.id] ?? proposedKey(item);
        const option = item.options.find((o) => optionKey(o) === key);
        return option ? [{ lesson: item.lesson, option }] : [];
      }),
    [lessons, overrides],
  );
  const summary = planSummary(choices, approvalMode);

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next) setOverrides({});
        onOpenChange(next);
      }}
    >
      <DialogContent
        size="lg"
        title={`План дня: ${absence.teacher.shortName}`}
        description={`${formatWeekdayDate(date)}. Це пропозиція: нічого не надіслано, доки ви не підтвердите.`}
        footer={
          <>
            <DialogClose asChild>
              <Button variant="secondary">Закрити</Button>
            </DialogClose>
            <Button variant="primary" disabled={choices.length === 0} loading={busy} onClick={() => onApply(choices)}>
              {planActionLabel(choices.length, approvalMode)}
            </Button>
          </>
        }
      >
        {plan.isPending ? (
          <LoadingRegion label="Складаємо план…">
            <div className="flex flex-col gap-3">
              {[0, 1, 2].map((key) => (
                <Skeleton key={key} className="h-14 w-full" />
              ))}
            </div>
          </LoadingRegion>
        ) : plan.isError ? (
          <ErrorState error={plan.error} onRetry={() => void plan.refetch()} size="sm" />
        ) : lessons.length === 0 ? (
          <p className="text-ui text-secondary">Невирішених пар цього дня немає.</p>
        ) : (
          <div className="flex flex-col gap-4">
            <ol className="flex flex-col" aria-label="Пари дня">
              {lessons.map((item) => {
                const key = overrides[item.lesson.id] ?? proposedKey(item);
                const chosen = item.options.find((o) => optionKey(o) === key) ?? null;
                const subgroup = subgroupLabel(item.lesson.subgroup);
                const label = `Рішення для ${item.lesson.pair} пари, ${item.lesson.group.code}`;
                return (
                  <li
                    key={item.lesson.id}
                    className="grid gap-x-4 gap-y-2 border-b border-separator py-3 first:pt-0 last:border-b-0 sm:grid-cols-[minmax(0,12rem)_minmax(0,1fr)]"
                  >
                    <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
                      <span className="font-condensed text-heading font-bold text-primary figures">{item.lesson.pair}</span>
                      <span className="text-caption text-muted">пара</span>
                      <Plate size="sm">{item.lesson.group.code}</Plate>
                      {subgroup ? <span className="text-caption text-secondary">{subgroup}</span> : null}
                      <span className="w-full text-caption text-secondary">{item.lesson.discipline.shortName}</span>
                    </div>
                    <div className="flex min-w-0 flex-col gap-1.5">
                      {item.past ? <p className="text-caption text-muted">Заняття вже минуло — позначте його окремо.</p> : null}
                      <Select<string>
                        aria-label={label}
                        size="sm"
                        value={key}
                        onValueChange={(value) => setOverrides((prev) => ({ ...prev, [item.lesson.id]: value }))}
                        options={[
                          ...item.options.map((option) => ({ value: optionKey(option), label: optionTitle(option) })),
                          { value: SKIP, label: 'Не вирішувати зараз' },
                        ]}
                      />
                      {chosen ? <OptionChips option={chosen} /> : null}
                    </div>
                  </li>
                );
              })}
            </ol>
            {summary.length > 0 ? (
              <div className="flex flex-col gap-0.5 rounded-control bg-sunken px-3 py-2 text-caption text-secondary" aria-live="polite">
                {summary.map((line) => (
                  <p key={line}>{line}</p>
                ))}
              </div>
            ) : null}
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}
