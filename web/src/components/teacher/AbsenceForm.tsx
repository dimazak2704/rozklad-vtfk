import { zodResolver } from '@hookform/resolvers/zod';
import { CircleExclamationPoint, Send } from 'lucide-react';
import { useEffect, useMemo, useRef } from 'react';
import { DayPicker, type DateRange } from 'react-day-picker';
import { uk } from 'react-day-picker/locale/uk';
import { Controller, useForm, useWatch } from 'react-hook-form';
import { isApiError } from '@/api/errors';
import { useCreateAbsence, useMySchedule } from '@/api/me';
import type { IsoDate, Uuid } from '@/api/types';
import { Button, Field, RadioGroup, Textarea, notify } from '@/components/ui';
import { cn } from '@/lib/cn';
import {
  addDays,
  compareIsoDates,
  diffDays,
  formatDateRange,
  formatShortWeekdayDate,
  isIsoDate,
  isoFromLocalDate,
  isWeekend,
  localDateFromIso,
  nearestStudyDay,
} from '@/lib/date';
import { ABSENCE_REASON_LABEL, countLabel, PAIR_FORMS } from '@/lib/labels';
import { DAY_PICKER_CLASSES } from '../public/schedule/day-picker-classes';
import { absenceSchema, ALL_PAIRS, COMMENT_MAX, REASONS, toAbsenceRequest, type AbsenceFormValues } from './absence-schema';

const FIELDS: ReadonlyArray<keyof AbsenceFormValues> = ['dateFrom', 'dateTo', 'pairs', 'reasonCategory', 'comment'];

function studyDaysBetween(from: IsoDate, to: IsoDate): number {
  let count = 0;
  for (let cursor = from; compareIsoDates(cursor, to) <= 0; cursor = addDays(cursor, 1)) if (!isWeekend(cursor)) count += 1;
  return count;
}

interface AbsenceFormProps {
  teacherId: Uuid;
  today: IsoDate;
  /** Поставити фокус на форму (перехід «Повідомити про відсутність»). */
  autoFocus?: boolean;
}

/**
 * Нова відсутність: дні в календарі, увесь день чи окремі пари, причина (бачить лише диспетчер) і коментар.
 * Поки людина обирає, форма показує, які її пари це зачепить.
 */
export function AbsenceForm({ teacherId, today, autoFocus = false }: AbsenceFormProps) {
  const create = useCreateAbsence();
  const schema = useMemo(() => absenceSchema(today), [today]);
  const first = nearestStudyDay(today);
  const container = useRef<HTMLFormElement>(null);
  const form = useForm<AbsenceFormValues>({
    resolver: zodResolver(schema),
    defaultValues: { dateFrom: first, dateTo: first, wholeDay: true, pairs: [], comment: '' },
  });
  const { errors, isSubmitting } = form.formState;
  const [dateFrom, dateTo, wholeDay, pairs] = useWatch({ control: form.control, name: ['dateFrom', 'dateTo', 'wholeDay', 'pairs'] });
  const rangeValid = isIsoDate(dateFrom) && isIsoDate(dateTo) && compareIsoDates(dateFrom, dateTo) <= 0 && diffDays(dateFrom, dateTo) < 31;
  const preview = useMySchedule(rangeValid ? dateFrom : today, rangeValid ? dateTo : today, { enabled: rangeValid });

  useEffect(() => {
    if (!autoFocus) return;
    container.current?.scrollIntoView({ block: 'start', behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' });
    container.current?.querySelector<HTMLElement>('[data-autofocus]')?.focus({ preventScroll: true });
  }, [autoFocus]);

  const affected = useMemo(() => {
    if (!rangeValid || !preview.data) return [];
    return preview.data.days.flatMap((day) =>
      day.lessons
        .filter((lesson) => lesson.teacher?.id === teacherId && lesson.kind !== 'CANCELLED' && (wholeDay || pairs.includes(lesson.pair)))
        .map((lesson) => ({ day, lesson })),
    );
  }, [rangeValid, preview.data, teacherId, wholeDay, pairs]);

  const submit = form.handleSubmit(async (values) => {
    try {
      const created = await create.mutateAsync(toAbsenceRequest(values));
      notify.success('Відсутність повідомлено', {
        description: `${formatDateRange(created.dateFrom, created.dateTo)}: диспетчер підбере заміни, а ви побачите їх у кабінеті.`,
      });
      form.reset({ dateFrom: first, dateTo: first, wholeDay: true, pairs: [], comment: '', reasonCategory: values.reasonCategory });
    } catch (error) {
      if (!isApiError(error)) {
        form.setError('root.server', { message: 'Щось пішло не так. Спробуйте ще раз.' });
        return;
      }
      const fieldErrors = error.fieldErrors();
      let mapped = false;
      for (const field of FIELDS) {
        if (fieldErrors[field]) {
          form.setError(field, { message: fieldErrors[field] });
          mapped = true;
        }
      }
      if (!mapped) form.setError('root.server', { message: error.message });
    }
  });

  const selected: DateRange | undefined = isIsoDate(dateFrom)
    ? { from: localDateFromIso(dateFrom), to: isIsoDate(dateTo) ? localDateFromIso(dateTo) : undefined }
    : undefined;

  return (
    <form ref={container} noValidate onSubmit={(event) => void submit(event)} className="flex scroll-mt-24 flex-col gap-5" aria-labelledby="absence-form-heading">
      <h2 id="absence-form-heading" className="text-heading font-semibold text-primary">
        Нова відсутність
      </h2>

      <fieldset className="flex flex-col gap-2">
        <legend className="mb-2 text-ui font-semibold text-primary">
          Дні <span className="text-danger" aria-hidden="true">*</span>
        </legend>
        <div className="self-start rounded-panel border border-default bg-surface p-2" data-autofocus tabIndex={-1}>
          <DayPicker
            mode="range"
            locale={uk}
            selected={selected}
            defaultMonth={localDateFromIso(first)}
            resetOnSelect
            onSelect={(range) => {
              const from = range?.from ? isoFromLocalDate(range.from) : '';
              const to = range?.to ? isoFromLocalDate(range.to) : from;
              form.setValue('dateFrom', from, { shouldValidate: form.formState.isSubmitted, shouldDirty: true });
              form.setValue('dateTo', to, { shouldValidate: form.formState.isSubmitted, shouldDirty: true });
            }}
            disabled={{ before: localDateFromIso(today) }}
            modifiers={{ weekend: (date: Date) => isWeekend(isoFromLocalDate(date)), college_today: localDateFromIso(today) }}
            modifiersClassNames={{
              weekend: 'text-muted',
              college_today: '[&>button]:font-bold [&>button]:underline [&>button]:decoration-2 [&>button]:underline-offset-4',
            }}
            classNames={{ ...DAY_PICKER_CLASSES, selected: '' }}
            showOutsideDays
          />
        </div>
        <p className="text-ui text-primary figures" aria-live="polite">
          {rangeValid ? (
            <>
              <span className="font-semibold">{formatDateRange(dateFrom, dateTo)}</span>
              <span className="text-secondary"> · навчальних днів: {studyDaysBetween(dateFrom, dateTo)}</span>
            </>
          ) : (
            <span className="text-secondary">Оберіть перший і останній день у календарі.</span>
          )}
        </p>
        {errors.dateFrom?.message || errors.dateTo?.message ? (
          <p className="flex items-start gap-1.5 text-caption font-medium text-danger">
            <CircleExclamationPoint aria-hidden="true" className="mt-px size-3.5" />
            {errors.dateFrom?.message ?? errors.dateTo?.message}
          </p>
        ) : null}
      </fieldset>

      <fieldset className="flex flex-col gap-3">
        <legend className="mb-2 text-ui font-semibold text-primary">Пари</legend>
        <Controller
          control={form.control}
          name="wholeDay"
          render={({ field }) => (
            <RadioGroup
              aria-label="Які пари"
              orientation="horizontal"
              value={field.value ? 'all' : 'some'}
              onValueChange={(value) => field.onChange(value === 'all')}
              options={[
                { value: 'all', label: 'Увесь день' },
                { value: 'some', label: 'Окремі пари' },
              ]}
            />
          )}
        />
        {!wholeDay ? (
          <Controller
            control={form.control}
            name="pairs"
            render={({ field }) => (
              <div role="group" aria-label="Номери пар" className="flex flex-wrap gap-1.5">
                {ALL_PAIRS.map((pair) => {
                  const on = field.value.includes(pair);
                  return (
                    <button
                      key={pair}
                      type="button"
                      aria-pressed={on}
                      aria-label={`${pair} пара`}
                      onClick={() => field.onChange(on ? field.value.filter((p) => p !== pair) : [...field.value, pair].sort((a, b) => a - b))}
                      className={cn(
                        'inline-flex size-10 items-center justify-center rounded-control border font-condensed text-heading font-bold figures',
                        'transition-[background-color,border-color,color] duration-100 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus',
                        on ? 'border-accent bg-accent text-on-accent hover:bg-accent-hover' : 'border-control bg-surface text-primary hover:border-strong hover:bg-hover',
                      )}
                    >
                      {pair}
                    </button>
                  );
                })}
              </div>
            )}
          />
        ) : null}
        {errors.pairs?.message ? (
          <p className="flex items-start gap-1.5 text-caption font-medium text-danger">
            <CircleExclamationPoint aria-hidden="true" className="mt-px size-3.5" />
            {errors.pairs.message}
          </p>
        ) : null}
        {rangeValid && preview.data ? (
          <div className="rounded-control border border-separator bg-sunken px-3 py-2.5 text-caption" aria-live="polite">
            {affected.length === 0 ? (
              <p className="text-secondary">У ці дні за розкладом у вас пар немає.</p>
            ) : (
              <>
                <p className="font-semibold text-primary">Зачепить {countLabel(affected.length, PAIR_FORMS)}:</p>
                <ul className="mt-1 flex flex-col gap-0.5 text-secondary">
                  {affected.slice(0, 6).map(({ day, lesson }) => (
                    <li key={`${day.date}-${lesson.id}`} className="figures">
                      {formatShortWeekdayDate(day.date)} · {lesson.pair} пара — {lesson.group.code}, {lesson.discipline.shortName}
                    </li>
                  ))}
                  {affected.length > 6 ? <li>і ще {affected.length - 6}</li> : null}
                </ul>
              </>
            )}
          </div>
        ) : null}
      </fieldset>

      <Controller
        control={form.control}
        name="reasonCategory"
        render={({ field }) => (
          <div className="flex flex-col gap-2">
            <RadioGroup
              legend="Причина"
              value={field.value ?? null}
              onValueChange={field.onChange}
              options={REASONS.map((reason) => ({ value: reason, label: ABSENCE_REASON_LABEL[reason] }))}
            />
            {errors.reasonCategory?.message ? (
              <p className="flex items-start gap-1.5 text-caption font-medium text-danger">
                <CircleExclamationPoint aria-hidden="true" className="mt-px size-3.5" />
                {errors.reasonCategory.message}
              </p>
            ) : null}
            <p className="text-caption text-muted">
              Причину й коментар бачить лише диспетчер. У розпорядженні буде коротко: «Відп.» (або «Відр.» для відрядження).
            </p>
          </div>
        )}
      />

      <Field label="Коментар для диспетчера" error={errors.comment?.message} hint={`Необовʼязково, до ${COMMENT_MAX} символів.`}>
        <Textarea {...form.register('comment')} rows={2} maxLength={COMMENT_MAX} />
      </Field>

      {errors.root?.server?.message ? (
        <p role="alert" className="flex items-start gap-2 rounded-control border border-cancel bg-danger-subtle px-3 py-2 text-ui text-danger">
          <CircleExclamationPoint aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
          {errors.root.server.message}
        </p>
      ) : null}

      <Button type="submit" variant="primary" loading={isSubmitting} loadingLabel="Надсилаємо" iconStart={<Send aria-hidden="true" className="size-4" />} className="self-start">
        Повідомити диспетчера
      </Button>
    </form>
  );
}
