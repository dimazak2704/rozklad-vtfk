import { zodResolver } from '@hookform/resolvers/zod';
import { CalendarCheck, TriangleExclamationPoint } from 'lucide-react';
import { useMemo, useState } from 'react';
import { Controller, useForm, useWatch } from 'react-hook-form';
import { z } from 'zod';
import { useCreateAbsence, useUpdateAbsence } from '@/api/admin-daily';
import { usePublicSchedule, usePublicTeachers } from '@/api/public';
import { isApiError } from '@/api/errors';
import { useBells } from '@/api/meta';
import type { AbsenceDto, AbsenceReasonCategory, AbsenceRequest, IsoDate } from '@/api/types';
import { Button, Combobox, Dialog, DialogContent, Field, Input, SegmentedControl, Select, Textarea } from '@/components/ui';
import { cn } from '@/lib/cn';
import { compareIsoDates, diffDays, formatNumericDate, formatShortWeekdayDate } from '@/lib/date';
import { ABSENCE_REASON_LABEL } from '@/lib/labels';
import { pairsCount } from '../format';

const REASONS = Object.keys(ABSENCE_REASON_LABEL) as AbsenceReasonCategory[];
const PAIRS = [1, 2, 3, 4, 5, 6, 7, 8] as const;
const MAX_PREVIEW_DAYS = 31;

export interface SemesterBounds {
  startsOn: IsoDate;
  endsOn: IsoDate;
}

function buildSchema(semester: SemesterBounds | null) {
  return z
    .object({
      teacherId: z.string().min(1, { error: 'Оберіть викладача' }),
      dateFrom: z.iso.date({ error: 'Вкажіть дату початку' }),
      dateTo: z.iso.date({ error: 'Вкажіть дату завершення' }),
      wholeDay: z.boolean(),
      pairs: z.array(z.number().int().min(1).max(8)),
      reasonCategory: z.enum(REASONS as [AbsenceReasonCategory, ...AbsenceReasonCategory[]], { error: 'Оберіть причину' }),
      comment: z.string().max(500, { error: 'Не довше 500 символів' }),
    })
    .refine((v) => compareIsoDates(v.dateTo, v.dateFrom) >= 0, { error: 'Не раніше за дату початку', path: ['dateTo'] })
    .refine((v) => v.wholeDay || v.pairs.length > 0, { error: 'Оберіть хоча б одну пару', path: ['pairs'] })
    .refine(
      (v) =>
        !semester || (compareIsoDates(v.dateFrom, semester.startsOn) >= 0 && compareIsoDates(v.dateTo, semester.endsOn) <= 0),
      {
        error: semester
          ? `Дати мають бути в межах семестру: ${formatNumericDate(semester.startsOn)}–${formatNumericDate(semester.endsOn)}`
          : 'Дати поза семестром',
        path: ['dateFrom'],
      },
    );
}

type FormValues = z.infer<ReturnType<typeof buildSchema>>;

interface AbsenceDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Дата за замовчуванням (робоча дата диспетчера). */
  date: IsoDate;
  semester: SemesterBounds | null;
  /** Редагування наявної відсутності; без неї — нова. */
  absence?: AbsenceDto | null;
  onSaved: (absence: AbsenceDto, created: boolean) => void;
}

/**
 * Нова відсутність або зміна наявної: викладач, дати, увесь день чи окремі пари, причина.
 * Поруч — які пари зачепить, щоб помилку з датою чи викладачем було видно до збереження.
 */
export function AbsenceDialog({ open, onOpenChange, date, semester, absence, onSaved }: AbsenceDialogProps) {
  const editing = Boolean(absence);
  const schema = useMemo(() => buildSchema(semester), [semester]);
  const teachers = usePublicTeachers();
  const create = useCreateAbsence();
  const update = useUpdateAbsence();
  const [formError, setFormError] = useState<string | null>(null);

  const defaults = useMemo<FormValues>(
    () => ({
      teacherId: absence?.teacher.id ?? '',
      dateFrom: absence?.dateFrom ?? date,
      dateTo: absence?.dateTo ?? date,
      wholeDay: absence ? absence.pairs === null : true,
      pairs: absence?.pairs ?? [],
      reasonCategory: absence?.reasonCategory ?? 'SICK_LEAVE',
      comment: absence?.comment ?? '',
    }),
    [absence, date],
  );

  // форма щоразу починається з чистого аркуша: батько змінює `key` при кожному відкритті
  const form = useForm<FormValues>({ resolver: zodResolver(schema), defaultValues: defaults });
  const { control, handleSubmit, setError, formState } = form;

  const [teacherId, dateFrom, dateTo, wholeDay, pairs] = useWatch({ control, name: ['teacherId', 'dateFrom', 'dateTo', 'wholeDay', 'pairs'] });
  const bells = useBells(dateFrom || null);
  const previewRange =
    teacherId && dateFrom && dateTo && compareIsoDates(dateTo, dateFrom) >= 0 && diffDays(dateFrom, dateTo) < MAX_PREVIEW_DAYS;
  const schedule = usePublicSchedule('TEACHER', teacherId ?? undefined, dateFrom ?? '', dateTo ?? '', {
    enabled: Boolean(previewRange),
  });
  const affected = (schedule.data?.days ?? []).flatMap((day) =>
    day.lessons
      .filter((lesson) => lesson.teacher?.id === teacherId && (wholeDay || pairs.includes(lesson.pair)))
      .map((lesson) => ({ day, lesson })),
  );

  const submit = handleSubmit(async (values) => {
    setFormError(null);
    const body: AbsenceRequest = {
      teacherId: values.teacherId,
      dateFrom: values.dateFrom,
      dateTo: values.dateTo,
      pairs: values.wholeDay ? null : [...values.pairs].sort((a, b) => a - b),
      reasonCategory: values.reasonCategory,
      comment: values.comment.trim() || null,
    };
    try {
      const saved = absence ? await update.mutateAsync({ id: absence.id, body }) : await create.mutateAsync(body);
      onSaved(saved, !absence);
      onOpenChange(false);
    } catch (error) {
      if (isApiError(error) && error.errors.length > 0) {
        for (const [field, message] of Object.entries(error.fieldErrors())) {
          if (field in values) setError(field as keyof FormValues, { message });
        }
      }
      setFormError(isApiError(error) ? error.message : 'Не вдалося зберегти відсутність. Спробуйте ще раз.');
    }
  });

  const teacherOptions = useMemo(
    () =>
      (teachers.data ?? []).map((t) => ({
        value: t.id,
        label: t.shortName,
        keywords: [t.displayName],
        description: t.cycleCommission ?? undefined,
      })),
    [teachers.data],
  );
  const pending = formState.isSubmitting;

  return (
    <Dialog open={open} onOpenChange={(next) => !pending && onOpenChange(next)}>
      <DialogContent
        title={editing ? 'Змінити відсутність' : 'Нова відсутність'}
        description="Причину бачить лише диспетчер: у розпорядженні друкується «Відп.» чи «Відр.»."
        size="lg"
        footer={
          <>
            <Button variant="secondary" onClick={() => onOpenChange(false)} disabled={pending}>
              Скасувати
            </Button>
            <Button variant="primary" type="submit" form="absence-form" loading={pending}>
              {editing ? 'Зберегти зміни' : 'Додати відсутність'}
            </Button>
          </>
        }
      >
        <form id="absence-form" noValidate onSubmit={(event) => void submit(event)} className="grid gap-5 md:grid-cols-[minmax(0,1fr)_15rem]">
          <div className="flex min-w-0 flex-col gap-4">
            <Field label="Викладач" required error={formState.errors.teacherId?.message}>
              <Controller
                control={control}
                name="teacherId"
                render={({ field }) => (
                  <Combobox
                    value={field.value || null}
                    onValueChange={(value) => field.onChange(value ?? '')}
                    options={teacherOptions}
                    loading={teachers.isPending}
                    disabled={editing}
                    placeholder={teachers.isError ? 'Список викладачів не завантажився' : 'Почніть вводити прізвище'}
                    searchPlaceholder="Прізвище, напр. «кренц»"
                  />
                )}
              />
            </Field>

            <div className="grid gap-4 sm:grid-cols-2">
              <Field label="З" required error={formState.errors.dateFrom?.message}>
                <Controller
                  control={control}
                  name="dateFrom"
                  render={({ field }) => (
                    <Input
                      type="date"
                      value={field.value}
                      min={semester?.startsOn}
                      max={semester?.endsOn}
                      onChange={(event) => {
                        field.onChange(event.target.value);
                        // одноденна відсутність — найчастіша: «по» тягнеться за «з», якщо стало раніше
                        if (event.target.value && compareIsoDates(form.getValues('dateTo'), event.target.value) < 0) {
                          form.setValue('dateTo', event.target.value);
                        }
                      }}
                      onBlur={field.onBlur}
                      className="figures"
                    />
                  )}
                />
              </Field>
              <Field label="По" required error={formState.errors.dateTo?.message}>
                <Controller
                  control={control}
                  name="dateTo"
                  render={({ field }) => (
                    <Input
                      type="date"
                      value={field.value}
                      min={dateFrom || semester?.startsOn}
                      max={semester?.endsOn}
                      onChange={(event) => field.onChange(event.target.value)}
                      onBlur={field.onBlur}
                      className="figures"
                    />
                  )}
                />
              </Field>
            </div>

            <fieldset className="flex flex-col gap-2">
              <legend className="mb-1.5 text-ui font-semibold text-primary">Пари</legend>
              <Controller
                control={control}
                name="wholeDay"
                render={({ field }) => (
                  <SegmentedControl<'all' | 'some'>
                    aria-label="Які пари"
                    value={field.value ? 'all' : 'some'}
                    onValueChange={(value) => field.onChange(value === 'all')}
                    options={[
                      { value: 'all', label: 'Увесь день' },
                      { value: 'some', label: 'Окремі пари' },
                    ]}
                    className="self-start"
                  />
                )}
              />
              {!wholeDay ? (
                <Controller
                  control={control}
                  name="pairs"
                  render={({ field }) => (
                    <div role="group" aria-label="Номери пар" className="flex flex-wrap gap-1.5">
                      {PAIRS.map((pair) => {
                        const on = field.value.includes(pair);
                        const slot = bells.data?.slots.find((s) => s.pair === pair);
                        return (
                          <button
                            key={pair}
                            type="button"
                            aria-pressed={on}
                            aria-label={slot ? `${pair} пара, ${slot.startsAt}` : `${pair} пара`}
                            onClick={() => field.onChange(on ? field.value.filter((p) => p !== pair) : [...field.value, pair])}
                            className={cn(
                              'flex h-11 min-w-14 flex-col items-center justify-center rounded-control border px-2 leading-tight',
                              'transition-[background-color,border-color,color] duration-100 ease-out',
                              'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus',
                              on ? 'border-accent bg-selected text-accent' : 'border-control bg-surface text-primary hover:border-strong',
                            )}
                          >
                            <span className="font-condensed text-heading font-bold figures">{pair}</span>
                            {slot ? <span className="text-meta font-normal text-muted figures">{slot.startsAt}</span> : null}
                          </button>
                        );
                      })}
                    </div>
                  )}
                />
              ) : null}
              {formState.errors.pairs?.message ? (
                <p className="flex items-center gap-1.5 text-caption font-medium text-danger">
                  <TriangleExclamationPoint aria-hidden="true" className="size-3.5" />
                  {formState.errors.pairs.message}
                </p>
              ) : null}
            </fieldset>

            <Field label="Причина" required error={formState.errors.reasonCategory?.message}>
              <Controller
                control={control}
                name="reasonCategory"
                render={({ field }) => (
                  <Select<AbsenceReasonCategory>
                    value={field.value}
                    onValueChange={field.onChange}
                    options={REASONS.map((value) => ({ value, label: ABSENCE_REASON_LABEL[value] }))}
                  />
                )}
              />
            </Field>

            <Field label="Коментар" hint="Видно лише в панелі диспетчера, у розпорядження й Telegram не потрапляє." error={formState.errors.comment?.message}>
              <Controller
                control={control}
                name="comment"
                render={({ field }) => <Textarea {...field} rows={2} placeholder="Напр.: повідомлено телефоном о 7:40" />}
              />
            </Field>

            {formError ? (
              <p role="alert" className="flex items-start gap-2 rounded-control border border-cancel bg-danger-subtle px-3 py-2 text-ui text-danger">
                <TriangleExclamationPoint aria-hidden="true" className="mt-0.5 size-4" />
                {formError}
              </p>
            ) : null}
          </div>

          <aside aria-live="polite" className="flex min-w-0 flex-col gap-2 rounded-panel border border-separator bg-sunken p-3">
            <p className="flex items-center gap-2 text-ui font-semibold text-primary">
              <CalendarCheck aria-hidden="true" className="size-4 text-muted" />
              Які пари зачепить
            </p>
            {!teacherId ? (
              <p className="text-caption text-muted">Оберіть викладача — тут зʼявляться його пари на ці дати.</p>
            ) : !previewRange ? (
              <p className="text-caption text-muted">Перегляд доступний для проміжку до {MAX_PREVIEW_DAYS} днів.</p>
            ) : schedule.isPending ? (
              <p className="text-caption text-muted">Дивимося розклад…</p>
            ) : schedule.isError ? (
              <p className="text-caption text-muted">Розклад викладача зараз недоступний. Відсутність усе одно можна зберегти.</p>
            ) : affected.length === 0 ? (
              <p className="text-caption text-secondary">У ці дні пар у викладача немає — замін не знадобиться.</p>
            ) : (
              <>
                <p className="text-caption text-secondary">
                  Потрібні рішення для <span className="font-semibold text-primary">{pairsCount(affected.length)}</span>
                </p>
                <ul className="flex max-h-64 flex-col gap-1 overflow-y-auto">
                  {affected.map(({ day, lesson }) => (
                    <li key={`${day.date}-${lesson.id}`} className="grid grid-cols-[3.25rem_1.25rem_minmax(0,1fr)] items-baseline gap-1.5 text-caption">
                      <span className="text-muted figures">{formatShortWeekdayDate(day.date)}</span>
                      <span className="font-condensed font-bold text-primary figures">{lesson.pair}</span>
                      <span className="truncate text-primary">
                        {lesson.group.code} · {lesson.discipline.shortName}
                      </span>
                    </li>
                  ))}
                </ul>
              </>
            )}
          </aside>
        </form>
      </DialogContent>
    </Dialog>
  );
}
