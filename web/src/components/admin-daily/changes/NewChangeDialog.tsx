import { TriangleExclamationPoint } from 'lucide-react';
import type { ChangeDto, IsoDate, SemesterRef } from '@/api/types';
import { DateInput } from '@/components/calendar/DateInput';
import { Button, Combobox, Dialog, DialogContent, Field, SegmentedControl, Select } from '@/components/ui';
import { countLabel } from '@/lib/labels';
import { assignLabel } from '../model';
import { NEW_CHANGE_MODES, type NewChangeMode, type NewChangePrefill } from './new-change';
import { AdditionalFields, LessonChangeFields } from './NewChangeFields';
import { useNewChangeForm } from './useNewChangeForm';

interface NewChangeDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Робоча дата сторінки — дата зміни за замовчуванням. */
  date: IsoDate;
  semester: SemesterRef | null;
  /** З «Обліку годин»: вид, група й дисципліна вже обрані — диспетчер лише ставить дату й пару. */
  prefill: NewChangePrefill | null;
  approvalMode: string | null | undefined;
  onCreated?: (change: ChangeDto) => void;
}

const MODE_OPTIONS = NEW_CHANGE_MODES.map((m) => ({
  value: m.value,
  label: m.label,
}));

/**
 * «Нова зміна» — зміна розкладу без відсутності викладача: додаткова пара (відпрацювання), інша аудиторія,
 * інший викладач на пару групи, вичитана дисципліна. Пари відсутніх вирішують у списку «Відсутні» —
 * там сервер підбирає варіанти; тут диспетчер усе обирає сам.
 */
export function NewChangeDialog({ open, onOpenChange, date, semester, prefill, approvalMode, onCreated }: NewChangeDialogProps) {
  const form = useNewChangeForm({ open, date, semester, prefill, onCreated, onOpenChange });
  const { state, patch, setMode, errors, formError, submit, reset, pending, declinedName } = form;

  const modeHint = NEW_CHANGE_MODES.find((m) => m.value === state.mode)?.hint;
  const submitLabel = state.mode === 'teacher' ? assignLabel(approvalMode) : state.mode === 'additional' ? 'Додати пару' : 'Зберегти зміну';

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (pending) return;
        if (!next) reset();
        onOpenChange(next);
      }}
    >
      <DialogContent
        title="Нова зміна"
        description="Зміна без відсутності: додаткова пара, інша аудиторія, інший викладач чи вичитана дисципліна. Пари відсутніх викладачів вирішуйте у списку «Відсутні» — там є підібрані варіанти."
        size="lg"
        footer={
          <>
            <Button variant="secondary" onClick={() => onOpenChange(false)} disabled={pending}>
              Скасувати
            </Button>
            <Button variant="primary" type="submit" form="new-change-form" loading={pending}>
              {submitLabel}
            </Button>
          </>
        }
      >
        <form id="new-change-form" noValidate onSubmit={(event) => void submit(event)} className="flex flex-col gap-4">
          {state.mode === 'additional' && prefill?.missingLessons ? (
            <p role="note" className="rounded-control bg-sunken px-3 py-2 text-ui text-primary">
              За обліком годин бракує {countLabel(prefill.missingLessons, ['пари', 'пар', 'пар'])}.
              {prefill.missingLessons > 1 ? ' Одна зміна — пари одного дня підряд; решту додайте ще однією зміною на інший день.' : null}
            </p>
          ) : null}
          {prefill?.replaces ? (
            <p role="note" className="rounded-control bg-change-subtle px-3 py-2 text-ui text-primary">
              {declinedName ?? 'Викладач'} не може провести цю пару. Оберіть іншого викладача — зміну з відмовою приберемо, щойно збережете нову.
            </p>
          ) : null}
          <div className="flex flex-col gap-1.5">
            {/* чотири види в один ряд не вміщаються на телефоні — там список вибору */}
            <SegmentedControl<NewChangeMode>
              aria-label="Що змінити"
              value={state.mode}
              onValueChange={setMode}
              options={MODE_OPTIONS}
              className="self-start max-sm:hidden"
            />
            <div className="sm:hidden">
              <Select<NewChangeMode>
                aria-label="Що змінити"
                value={state.mode}
                onValueChange={setMode}
                options={MODE_OPTIONS}
              />
            </div>
            {modeHint ? <p className="text-caption text-secondary">{modeHint}</p> : null}
          </div>

          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Група" required error={errors.groupId}>
              <Combobox
                value={state.groupId}
                onValueChange={(groupId) =>
                  patch({
                    groupId,
                    lessonId: null,
                    teacherId: null,
                    subgroup: null,
                  })
                }
                options={form.options.group}
                loading={form.loading.groups}
                placeholder="Оберіть групу"
                searchPlaceholder="Код групи, напр. 4КІ1"
              />
            </Field>
            <Field label="Дата" required error={errors.date}>
              <DateInput
                value={state.date}
                min={semester?.startsOn}
                max={semester?.endsOn}
                onChange={(next) => patch({ date: next, lessonId: null })}
              />
            </Field>
          </div>

          {state.mode === 'additional' ? <AdditionalFields form={form} /> : <LessonChangeFields form={form} />}

          {formError ? (
            <p role="alert" className="flex items-start gap-2 rounded-control border border-cancel bg-danger-subtle px-3 py-2 text-ui text-danger">
              <TriangleExclamationPoint aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
              {formError}
            </p>
          ) : null}
        </form>
      </DialogContent>
    </Dialog>
  );
}
