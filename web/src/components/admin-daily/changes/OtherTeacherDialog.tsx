import { Search } from 'lucide-react';
import { useDeferredValue, useState } from 'react';
import { useTeacherAvailability } from '@/api/admin-daily';
import type { TeacherAvailabilityDto, Uuid } from '@/api/types';
import { Button, Dialog, DialogClose, DialogContent, ErrorState, Input, LoadingRegion, RadioGroup, Skeleton } from '@/components/ui';
import { assignLabel, availabilityLabel } from '../model';

interface OtherTeacherDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  lessonId: Uuid;
  pair: number;
  approvalMode: string | null | undefined;
  busy: boolean;
  onAssign: (teacher: TeacherAvailabilityDto['teacher']) => void;
}

/**
 * Ручне «Інший викладач…»: будь-який активний викладач зі станом на цій парі. Не рекомендація, а виняток —
 * тому без порядку «кращий-гірший»: вільні першими, далі за абеткою (так віддає сервер). Вибір не надсилає
 * нічого; дія — окрема кнопка внизу.
 */
export function OtherTeacherDialog({ open, onOpenChange, lessonId, pair, approvalMode, busy, onAssign }: OtherTeacherDialogProps) {
  const [query, setQuery] = useState('');
  const [chosen, setChosen] = useState<Uuid | null>(null);
  const deferred = useDeferredValue(query);
  const list = useTeacherAvailability(open ? lessonId : null, deferred);
  const items = list.data ?? [];
  const teacher = items.find((item) => item.teacher.id === chosen && item.status === 'FREE')?.teacher ?? null;

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next) {
          setQuery('');
          setChosen(null);
        }
        onOpenChange(next);
      }}
    >
      <DialogContent
        title={`Інший викладач на ${pair} пару`}
        description="Предмет пари не зміниться. Обрати можна лише вільних."
        footer={
          <>
            <p className="me-auto text-caption text-muted">Ручне призначення — лише як виняток.</p>
            <DialogClose asChild>
              <Button variant="secondary">Закрити</Button>
            </DialogClose>
            <Button variant="primary" disabled={!teacher} loading={busy} onClick={() => teacher && onAssign(teacher)}>
              {assignLabel(approvalMode)}
            </Button>
          </>
        }
      >
        <div className="flex flex-col gap-3">
          <Input
            type="search"
            aria-label="Пошук викладача"
            placeholder="Прізвище"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            iconStart={<Search aria-hidden="true" className="size-4" />}
            className="text-body sm:text-ui"
            autoComplete="off"
          />
          {list.isPending ? (
            <LoadingRegion label="Завантажуємо викладачів…">
              <div className="flex flex-col gap-2">
                {[0, 1, 2, 3, 4].map((key) => (
                  <Skeleton key={key} className="h-8 w-full" />
                ))}
              </div>
            </LoadingRegion>
          ) : list.isError ? (
            <ErrorState error={list.error} onRetry={() => void list.refetch()} size="sm" />
          ) : items.length === 0 ? (
            <p className="text-ui text-secondary">Нікого не знайдено. Спробуйте інше прізвище.</p>
          ) : (
            <RadioGroup<Uuid>
              aria-label="Викладачі"
              value={chosen}
              onValueChange={setChosen}
              options={items.map((item) => ({
                value: item.teacher.id,
                label: item.teacher.displayName || item.teacher.shortName,
                description: availabilityLabel(item),
                disabled: item.status !== 'FREE',
              }))}
            />
          )}
        </div>
      </DialogContent>
    </Dialog>
  );
}
