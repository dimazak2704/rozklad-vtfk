import { zodResolver } from '@hookform/resolvers/zod';
import { CircleExclamationPoint, Send } from 'lucide-react';
import { useEffect, useMemo, useRef, useState } from 'react';
import { DayPicker, type DateRange } from 'react-day-picker';
import { uk } from 'react-day-picker/locale/uk';
import { Controller, useForm, useWatch } from 'react-hook-form';
import { isApiError } from '@/api/errors';
import { useCreateAbsence, useMySchedule } from '@/api/me';
import type { DayDto, IsoDate, LessonView, Uuid } from '@/api/types';
import { Button, Field, RadioGroup, Textarea, notify } from '@/components/ui';
import { cn } from '@/lib/cn';
import {
  addDays,
  compareIsoDates,
  diffDays,
  formatDateRange,
  formatShortWeekdayDate,
  formatWeekdayDate,
  isIsoDate,
  isoFromLocalDate,
  isWeekend,
  localDateFromIso,
  nearestStudyDay,
} from '@/lib/date';
import { ABSENCE_REASON_LABEL, countLabel, PAIR_FORMS, PAIR_FORMS_ACC } from '@/lib/labels';
import { DAY_PICKER_CLASSES } from '../public/schedule/day-picker-classes';
import { absenceSchema, ALL_PAIRS, COMMENT_MAX, REASONS, toAbsenceRequest, type AbsenceFormValues } from './absence-schema';

const FIELDS: ReadonlyArray<keyof AbsenceFormValues> = ['dateFrom', 'dateTo', 'pairs', 'reasonCategory', 'comment'];
const STUDY_DAY_FORMS = ['навчальний день', 'навчальні дні', 'навчальних днів'] as const;

/** Остання обрана причина: наступного разу вона вже обрана (зазвичай людина хворіє чи їде у відрядження знову так само). */
const REASON_STORAGE_KEY = 'rozklad.absenceReason';

function storedReason(): AbsenceFormValues['reasonCategory'] | undefined {
  try {
    const value = window.localStorage.getItem(REASON_STORAGE_KEY);
    return REASONS.find((reason) => reason === value);
  } catch {
    return undefined;
  }
}

function rememberReason(reason: AbsenceFormValues['reasonCategory']): void {
  try {
    window.localStorage.setItem(REASON_STORAGE_KEY, reason);
  } catch {
    // сховище вимкнене — просто не памʼятаємо
  }
}

/** Календар відсутностей: на дотику дні й стрілки — ціль 44 px. */
const CALENDAR_CLASSES = {
  ...DAY_PICKER_CLASSES,
  selected: '',
  month_caption: 'flex h-9 items-center justify-center font-[650] capitalize pointer-coarse:h-11',
  nav: 'absolute inset-x-0 top-0 flex h-9 items-center justify-between pointer-coarse:h-11',
  button_previous: `${DAY_PICKER_CLASSES.button_previous} pointer-coarse:size-11`,
  button_next: `${DAY_PICKER_CLASSES.button_next} pointer-coarse:size-11`,
  weekday: `${DAY_PICKER_CLASSES.weekday} pointer-coarse:w-11`,
  day_button: `${DAY_PICKER_CLASSES.day_button} pointer-coarse:size-11`,
};

type OwnLesson = { day: DayDto; lesson: LessonView };

function LessonLines({ items }: { items: readonly OwnLesson[] }) {
  return (
    <ul className="mt-1 flex flex-col gap-0.5 text-secondary">
      {items.slice(0, 6).map(({ day, lesson }) => (
        <li key={`${day.date}-${lesson.id}`} className="figures">
          {formatShortWeekdayDate(day.date)} · {lesson.pair} пара — {lesson.group.code}, {lesson.discipline.shortName}
        </li>
      ))}
      {items.length > 6 ? <li>і ще {items.length - 6}</li> : null}
    </ul>
  );
}

/**
 * Що зачепить відсутність. «Пар немає» — лише коли у викладача справді немає пар у ці дні; якщо пари є,
 * а обрані номери їх не зачіпають (або номери ще не обрано), показуємо його пари, щоб було видно, які обрати.
 */
function AffectedPreview({ own, affected, pairsChosen }: { own: readonly OwnLesson[]; affected: readonly OwnLesson[]; pairsChosen: boolean }) {
  if (own.length === 0) return <p className="text-secondary">У ці дні за розкладом у вас пар немає.</p>;
  if (!pairsChosen) {
    return (
      <>
        <p className="font-semibold text-primary">Оберіть пари вище. За розкладом у ці дні у вас {countLabel(own.length, PAIR_FORMS)}:</p>
        <LessonLines items={own} />
      </>
    );
  }
  if (affected.length === 0) {
    return (
      <>
        <p className="font-semibold text-primary">Обрані пари не зачеплять ваших занять. За розкладом у ці дні у вас {countLabel(own.length, PAIR_FORMS)}:</p>
        <LessonLines items={own} />
      </>
    );
  }
  return (
    <>
      <p className="font-semibold text-primary">Зачепить {countLabel(affected.length, PAIR_FORMS_ACC)}:</p>
      <LessonLines items={affected} />
    </>
  );
}

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
  const [awaitingEnd, setAwaitingEnd] = useState(false);
  const form = useForm<AbsenceFormValues>({
    resolver: zodResolver(schema),
    defaultValues: { dateFrom: first, dateTo: first, wholeDay: true, pairs: [], comment: '', reasonCategory: storedReason() },
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

  // useMySchedule тримає попередню відповідь, поки вантажиться нова: її дні — не ті, що обрано
  const previewData = rangeValid && !preview.isPlaceholderData ? preview.data : undefined;
  const own = useMemo(() => {
    if (!previewData) return [];
    return previewData.days.flatMap((day) =>
      day.lessons.filter((lesson) => lesson.teacher?.id === teacherId && lesson.kind !== 'CANCELLED').map((lesson) => ({ day, lesson })),
    );
  }, [previewData, teacherId]);
  const affected = useMemo(() => (wholeDay ? own : own.filter(({ lesson }) => pairs.includes(lesson.pair))), [own, wholeDay, pairs]);

  const submit = form.handleSubmit(async (values) => {
    try {
      const created = await create.mutateAsync(toAbsenceRequest(values));
      rememberReason(values.reasonCategory);
      notify.success('Відсутність повідомлено', {
        description: `${formatDateRange(created.dateFrom, created.dateTo)}: диспетчер підбере заміни, а ви побачите їх у кабінеті.`,
      });
      setAwaitingEnd(false);
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

  // перший клік обирає початок: календар має бачити діапазон без кінця, щоб другий клік його завершив
  // (форма тим часом уже має один день, і підсумок показує «навчальних днів: 1»)
  const selected: DateRange | undefined = isIsoDate(dateFrom)
    ? { from: localDateFromIso(dateFrom), to: isIsoDate(dateTo) && !awaitingEnd ? localDateFromIso(dateTo) : undefined }
    : undefined;

  return (
    <form ref={container} noValidate onSubmit={(event) => void submit(event)} className="@container flex scroll-mt-24 flex-col gap-5" aria-labelledby="absence-form-heading">
      <h2 id="absence-form-heading" className="text-title text-primary">
        Нова відсутність
      </h2>

      <div className="flex flex-col gap-5 @3xl:grid @3xl:grid-cols-[auto_minmax(0,1fr)] @3xl:items-start @3xl:gap-x-10">
        <fieldset className="flex flex-col gap-2">
          <legend className="mb-2 text-ui font-[650] text-primary">Дні</legend>
          <div className="-ms-px self-start rounded-popover focus-visible:outline-2 focus-visible:outline-focus" data-autofocus tabIndex={-1}>
            <DayPicker
              mode="range"
              locale={uk}
              selected={selected}
              defaultMonth={localDateFromIso(first)}
              resetOnSelect
              onSelect={(range) => {
                const from = range?.from ? isoFromLocalDate(range.from) : '';
                const to = range?.to ? isoFromLocalDate(range.to) : from;
                setAwaitingEnd(Boolean(range?.from) && !range?.to);
                form.setValue('dateFrom', from, { shouldValidate: form.formState.isSubmitted, shouldDirty: true });
                form.setValue('dateTo', to, { shouldValidate: form.formState.isSubmitted, shouldDirty: true });
              }}
              disabled={{ before: localDateFromIso(today) }}
              modifiers={{ weekend: (date: Date) => isWeekend(isoFromLocalDate(date)), college_today: localDateFromIso(today) }}
              modifiersClassNames={{
                weekend: 'text-muted',
                college_today: '[&>button]:font-bold [&>button]:underline [&>button]:decoration-2 [&>button]:underline-offset-4',
              }}
              classNames={CALENDAR_CLASSES}
              showOutsideDays
            />
          </div>
          <p role="status" className="text-ui text-primary figures">
            {rangeValid ? (
              <>
                <span className="font-[650]">{dateFrom === dateTo ? formatWeekdayDate(dateFrom) : formatDateRange(dateFrom, dateTo)}</span>
                {dateFrom === dateTo ? null : <span className="text-secondary">, {countLabel(studyDaysBetween(dateFrom, dateTo), STUDY_DAY_FORMS)}</span>}
              </>
            ) : (
              <span className="text-secondary">Оберіть день, а для кількох днів — ще й останній.</span>
            )}
          </p>
          {errors.dateFrom?.message || errors.dateTo?.message ? (
            <p className="flex items-start gap-1.5 text-caption font-medium text-danger">
              <CircleExclamationPoint aria-hidden="true" className="mt-px size-3.5" />
              {errors.dateFrom?.message ?? errors.dateTo?.message}
            </p>
          ) : null}
        </fieldset>

        <div className="flex min-w-0 flex-col gap-5">
          <fieldset className="flex flex-col gap-3">
            <legend className="mb-2 text-ui font-[650] text-primary">Пари</legend>
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
                            'inline-flex size-10 items-center justify-center rounded-control border text-cell font-[650] figures pointer-coarse:size-11',
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
            {rangeValid ? (
              <div className="rounded-popover bg-sunken px-3.5 py-3 text-caption" aria-live="polite" aria-busy={!previewData && !preview.isError}>
                {previewData ? (
                  <AffectedPreview own={own} affected={affected} pairsChosen={wholeDay || pairs.length > 0} />
                ) : preview.isError && !preview.isPlaceholderData ? (
                  <p className="text-secondary">Не вдалося перевірити ваш розклад на ці дні. Відсутність можна повідомити й так.</p>
                ) : (
                  <p className="text-secondary">Перевіряємо ваші пари на ці дні…</p>
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
                <p className="text-caption text-muted">Причину й коментар бачить лише диспетчер.</p>
              </div>
            )}
          />

          <Field label="Коментар для диспетчера" error={errors.comment?.message} hint="Необовʼязково.">
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
        </div>
      </div>
    </form>
  );
}
