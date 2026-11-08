import { Cpu, TriangleExclamationPoint } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router';
import { isApiError } from '@/api/errors';
import { usePublishVersion, type TimetableConflict } from '@/api/timetable';
import type { IsoDate, SemesterRef, TimetableVersionDto } from '@/api/types';
import { Button, buttonClass, Dialog, DialogClose, DialogContent, Field, Input, notify } from '@/components/ui';
import { compareIsoDates, formatLongDate, formatNumericDate, isIsoDate } from '@/lib/date';
import { defaultEffectiveFrom } from '../model/versions';

interface PublishDialogProps {
  version: TimetableVersionDto | null;
  onOpenChange: (open: boolean) => void;
  semester: SemesterRef;
  today: IsoDate;
  /** Жорсткі конфлікти версії (з GET …/conflicts). */
  hardConflicts: readonly TimetableConflict[] | undefined;
  onShowConflicts: () => void;
}

/**
 * Публікація чернетки: з якої дати діє. Жорсткі конфлікти не пускають далі (сервер теж відповість 409);
 * що станеться з попередньою версією й датами — написано до натискання.
 */
export function PublishDialog({ version, onOpenChange, semester, today, hardConflicts, onShowConflicts }: PublishDialogProps) {
  return (
    <Dialog open={version !== null} onOpenChange={onOpenChange}>
      {version ? (
        <PublishForm
          key={version.id}
          version={version}
          semester={semester}
          today={today}
          hardConflicts={hardConflicts}
          onShowConflicts={() => {
            onOpenChange(false);
            onShowConflicts();
          }}
          onDone={() => onOpenChange(false)}
        />
      ) : null}
    </Dialog>
  );
}

function PublishForm({
  version,
  semester,
  today,
  hardConflicts,
  onShowConflicts,
  onDone,
}: Omit<PublishDialogProps, 'version' | 'onOpenChange'> & { version: TimetableVersionDto; onDone: () => void }) {
  const publish = usePublishVersion();
  const [effectiveFrom, setEffectiveFrom] = useState<IsoDate>(() => defaultEffectiveFrom(today, semester));
  const [serverConflicts, setServerConflicts] = useState<TimetableConflict[] | null>(null);
  const [fieldError, setFieldError] = useState<string | null>(null);
  const minDate = compareIsoDates(today, semester.startsOn) > 0 ? today : semester.startsOn;
  const conflicts = serverConflicts ?? hardConflicts ?? [];
  const conflictCount = serverConflicts?.length ?? version.hardConflicts;
  const blocked = conflictCount > 0;

  const dateError = !isIsoDate(effectiveFrom)
    ? 'Вкажіть дату'
    : compareIsoDates(effectiveFrom, minDate) < 0
      ? `Не раніше ${formatNumericDate(minDate)}`
      : compareIsoDates(effectiveFrom, semester.endsOn) > 0
        ? `Не пізніше кінця семестру (${formatNumericDate(semester.endsOn)})`
        : null;

  const submit = async () => {
    setFieldError(null);
    try {
      const result = await publish.mutateAsync({ versionId: version.id, effectiveFrom });
      const kept = result.keptLessons > 0 ? ` Лишилось занять із чинних замін: ${result.keptLessons.toLocaleString('uk-UA')}.` : '';
      const description = `Діє з ${formatLongDate(effectiveFrom)}. Перебудовано занять на дати: ${result.materializedLessons.toLocaleString('uk-UA')}.${kept}`;
      const pending = result.pendingDecisions ?? [];
      if (result.conflicts.length > 0) {
        // core публікує, але повідомляє про конфлікти лишених замін з новою версією — диспетчер має їх побачити
        notify.warning(`Версію «${version.name}» опубліковано, але ${result.conflicts.length} замін конфліктують з новим розкладом`, {
          description: `${description} ${result.conflicts.map((c) => c.message).join('; ')}`,
        });
      } else if (pending.length > 0) {
        // заняття старої версії лишились через відкриті рішення: диспетчер вирішує, скасувати їх чи ні
        notify.warning(`Версію «${version.name}» опубліковано; ${pending.length} пар лишились зі старого розкладу через відкриті рішення`, {
          description: `${description} ${pending.map((c) => c.message).join('; ')}`,
        });
      } else {
        notify.success(`Версію «${version.name}» опубліковано`, { description });
      }
      onDone();
    } catch (error) {
      if (isApiError(error) && error.isConflict && error.conflicts.length > 0) {
        setServerConflicts(error.conflicts);
      } else if (isApiError(error) && error.fieldErrors().effectiveFrom) {
        setFieldError(error.fieldErrors().effectiveFrom ?? null);
      } else {
        notify.error(error, 'Версію не опубліковано');
      }
    }
  };

  return (
    <DialogContent
      // фокус — на дату (або на пояснення конфліктів), а не на хрестик закриття
      onOpenAutoFocus={(event) => {
        event.preventDefault();
        document.querySelector<HTMLElement>('[data-publish-focus]')?.focus();
      }}
      title="Опублікувати версію"
      description={version.name}
      footer={
        <>
          <DialogClose asChild>
            <Button variant="ghost">Скасувати</Button>
          </DialogClose>
          <Button variant="primary" onClick={() => void submit()} disabled={blocked || dateError !== null} loading={publish.isPending}>
            Опублікувати
          </Button>
        </>
      }
    >
      {blocked ? (
        <div role="alert" className="flex flex-col gap-3 rounded-control border border-danger bg-danger-subtle px-3 py-3">
          <p className="flex items-center gap-1.5 text-ui font-semibold text-danger">
            <TriangleExclamationPoint aria-hidden="true" className="size-4" />
            Опублікувати не можна: жорстких конфліктів — {conflictCount}
          </p>
          {conflicts.length > 0 ? (
            <ul className="flex list-disc flex-col gap-1 ps-5 text-caption text-primary">
              {conflicts.slice(0, 5).map((conflict, index) => (
                <li key={`${conflict.message}-${index}`}>{conflict.message}</li>
              ))}
              {conflicts.length > 5 ? <li className="list-none text-muted">і ще {conflicts.length - 5}</li> : null}
            </ul>
          ) : null}
          <div className="flex flex-wrap gap-2">
            <Button size="sm" data-publish-focus="" onClick={onShowConflicts}>
              Показати конфлікти в сітці
            </Button>
            <Link to="/admin/generation" className={buttonClass({ size: 'sm', variant: 'ghost' })}>
              <Cpu aria-hidden="true" className="size-3.5" />
              Розвʼязати генератором
            </Link>
          </div>
        </div>
      ) : (
        <div className="flex flex-col gap-4">
          <Field
            label="Діє з"
            required
            error={fieldError ?? dateError}
            hint={`Семестр: ${formatNumericDate(semester.startsOn)}–${formatNumericDate(semester.endsOn)}`}
          >
            <Input data-publish-focus="" type="date" value={effectiveFrom} min={minDate} max={semester.endsOn} onChange={(event) => setEffectiveFrom(event.target.value)} />
          </Field>
          <ul className="flex list-disc flex-col gap-1.5 ps-5 text-ui text-secondary">
            <li>Поточна опублікована версія піде в архів — її можна буде переглядати й копіювати.</li>
            <li>
              Заняття з {isIsoDate(effectiveFrom) ? formatLongDate(effectiveFrom) : '…'} до кінця семестру перебудуються за цією версією
              (чисельник і знаменник — за календарем).
            </li>
            <li>Заміни, уже внесені розпорядженнями, лишаються; якщо вони тепер конфліктують — їх буде показано.</li>
            {version.softWarnings > 0 ? <li>Мʼяких попереджень: {version.softWarnings} — вони не заважають публікації.</li> : null}
          </ul>
        </div>
      )}
    </DialogContent>
  );
}
