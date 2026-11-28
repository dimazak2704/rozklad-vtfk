import { zodResolver } from '@hookform/resolvers/zod';
import { CircleExclamationPoint, Send } from 'lucide-react';
import { useEffect, useId, useMemo, useRef, useState, type BaseSyntheticEvent } from 'react';
import { Controller, useForm, useWatch, type FieldErrors } from 'react-hook-form';
import { isApiError } from '@/api/errors';
import { useCreateAbsence, useMySchedule } from '@/api/me';
import type { IsoDate, Uuid } from '@/api/types';
import { Button, Checkbox, RadioGroup, notify } from '@/components/ui';
import { compareIsoDates, diffDays, formatDateRange, isIsoDate, nearestStudyDay } from '@/lib/date';
import { ABSENCE_REASON_LABEL } from '@/lib/labels';
import type { DaySelection } from './absence-days';
import { AbsenceDayPicker } from './AbsenceDayPicker';
import { absenceSchema, ALL_PAIRS, REASONS, toAbsenceRequest, type AbsenceFormValues } from './absence-schema';
import { useAssignedSchedule } from './useAssignedSchedule';
import { choiceText, pairChoices } from './absence-choices';
import { AffectedPreview, CommentField, OtherPairs } from './AbsenceFormParts';

const FIELDS: ReadonlyArray<keyof AbsenceFormValues> = ['dateFrom', 'dateTo', 'pairs', 'reasonCategory', 'comment'];
/** Найдовший діапазон, який віддає GET /me/schedule (core і мок: «не більше 31 дня від from»). */
const PREVIEW_MAX_DAYS = 31;

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


interface AbsenceFormProps {
  teacherId: Uuid;
  today: IsoDate;
  /** Поставити фокус на форму (перехід «Повідомити про відсутність»). */
  autoFocus?: boolean;
}

/**
 * Нова відсутність: дні в календарі, увесь день чи окремі пари, причина (подробиці бачить лише диспетчер, публічно — лише «Відп.» чи «Відр.») і коментар.
 * Для причини «Інше» коментар — це «Що саме?»: він стоїть одразу під варіантами, окремого поля немає.
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
  const reasonError = errors.reasonCategory?.message;
  const reasonErrorId = useId();
  const reasonHelpId = useId();
  const [dateFrom, dateTo, wholeDay, reason] = useWatch({ control: form.control, name: ['dateFrom', 'dateTo', 'wholeDay', 'reasonCategory'] });
  // «Інше»: коментар стає поясненням причини — те саме поле, лише під варіантами й з іншим підписом
  const otherReason = reason === 'OTHER';
  const commentRef = useRef<HTMLTextAreaElement | null>(null);
  // фокус у поле — лише коли «Інше» обрали мишею: стрілки клавіатури мають і далі ходити варіантами,
  // а на дотику клавіатура телефона закрила б форму заради необовʼязкового поля
  const reasonByPointer = useRef(false);
  const focusComment = useRef(false);
  const reasonsBox = useRef<HTMLDivElement>(null);
  useEffect(() => {
    // слухаємо напряму, а не через JSX: обгортка не стає «інтерактивним» елементом, кнопки — у RadioGroup
    const box = reasonsBox.current;
    if (!box) return;
    const onPointer = (event: PointerEvent) => {
      reasonByPointer.current = event.pointerType !== 'touch';
    };
    const onKey = () => {
      reasonByPointer.current = false;
    };
    box.addEventListener('pointerdown', onPointer);
    box.addEventListener('keydown', onKey);
    return () => {
      box.removeEventListener('pointerdown', onPointer);
      box.removeEventListener('keydown', onKey);
    };
  }, []);
  useEffect(() => {
    if (!otherReason || !focusComment.current) return;
    focusComment.current = false;
    commentRef.current?.focus();
  }, [otherReason]);
  const rangeValid = isIsoDate(dateFrom) && isIsoDate(dateTo) && compareIsoDates(dateFrom, dateTo) <= 0;
  // GET /me/schedule віддає щонайбільше 31 день: довшу відсутність (core приймає до року) не перевіряємо
  const tooLong = rangeValid && diffDays(dateFrom, dateTo) >= PREVIEW_MAX_DAYS;
  const previewable = rangeValid && !tooLong;
  const preview = useMySchedule(previewable ? dateFrom : today, previewable ? dateTo : today, { enabled: previewable });

  useEffect(() => {
    if (!autoFocus) return;
    // сторінка ставить форму першою (і на телефоні), тож прокручувати не треба — лише фокус на календар
    container.current?.querySelector<HTMLElement>('[data-autofocus]')?.focus({ preventScroll: true });
  }, [autoFocus]);

  // useMySchedule тримає попередню відповідь, поки вантажиться нова: її дні — не ті, що обрано
  // пари, які зачепить відсутність, — і ті, що я мав узяти за ще не опублікованою заміною (DOMAIN §7)
  const previewData = useAssignedSchedule(previewable && !preview.isPlaceholderData ? preview.data : undefined);
  // перевірити розклад не вийде: номери пар 1–8 і чесне речення замість «Перевіряємо…»
  const previewFailed = previewable && preview.isError && !preview.isPlaceholderData;
  const unchecked = tooLong || previewFailed;
  const own = useMemo(() => {
    if (!previewData) return [];
    return previewData.days.flatMap((day) =>
      day.lessons.filter((lesson) => lesson.teacher?.id === teacherId && lesson.kind !== 'CANCELLED').map((lesson) => ({ day, lesson })),
    );
  }, [previewData, teacherId]);
  const choices = useMemo(() => pairChoices(own), [own]);
  const oneDay = dateFrom === dateTo;

  // перше поле з помилкою отримує фокус: Controller-поля (календар, пари, причина) самі цього не вміють
  const focusFirstError = (invalid: FieldErrors<AbsenceFormValues>, event?: BaseSyntheticEvent) => {
    const root = event?.target instanceof HTMLFormElement ? event.target : null;
    if (!root) return;
    const target = invalid.dateFrom || invalid.dateTo
      ? root.querySelector<HTMLElement>('[data-autofocus]')
      : invalid.pairs
        ? root.querySelector<HTMLElement>('[data-pairs] button')
        : invalid.reasonCategory
          ? root.querySelector<HTMLElement>('[data-reasons] [role="radio"]')
          : null;
    target?.focus();
  };

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
  }, focusFirstError);

  // дні в календарі: форма тримає from/to, а «чекаємо кінця» — лише стан календаря (DaySelection)
  const days: DaySelection | null = rangeValid ? { from: dateFrom, to: dateTo, awaitingEnd } : null;
  const pickDays = (next: DaySelection) => {
    setAwaitingEnd(next.awaitingEnd);
    form.setValue('dateFrom', next.from, { shouldValidate: form.formState.isSubmitted, shouldDirty: true });
    form.setValue('dateTo', next.to, { shouldValidate: form.formState.isSubmitted, shouldDirty: true });
  };

  return (
    <form ref={container} noValidate onSubmit={(event) => void submit(event)} className="@container flex scroll-mt-24 flex-col gap-5" aria-labelledby="absence-form-heading">
      <h2 id="absence-form-heading" className="text-title text-primary">
        Нова відсутність
      </h2>

      <div className="flex flex-col gap-5 @3xl:grid @3xl:grid-cols-[auto_minmax(0,1fr)] @3xl:items-start @3xl:gap-x-10">
        <fieldset className="flex flex-col gap-2">
          <legend className="mb-2 text-ui font-[650] text-primary">Дні</legend>
          <AbsenceDayPicker value={days} onChange={pickDays} today={today} defaultMonth={first} />
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
                render={({ field }) => {
                  const toggle = (pair: number, on: boolean) =>
                    field.onChange(on ? [...new Set([...field.value, pair])].sort((a, b) => a - b) : field.value.filter((p) => p !== pair));
                  if (!previewData) {
                    return (
                      <div className="flex flex-col gap-1" data-pairs>
                        <p className="text-ui text-secondary" aria-live="polite">
                          {tooLong
                            ? 'Розклад перевіряємо лише на 31 день — оберіть номери пар нижче.'
                            : previewFailed
                              ? 'Не вдалося перевірити ваш розклад — оберіть номери пар нижче.'
                              : rangeValid
                                ? 'Перевіряємо ваші пари на ці дні…'
                                : 'Спершу оберіть дні в календарі.'}
                        </p>
                        {unchecked ? (
                          <OtherPairs pairs={ALL_PAIRS} selected={field.value} open onToggle={toggle} label="Номери пар" caption={null} />
                        ) : null}
                      </div>
                    );
                  }
                  const listed = new Set(choices.map((choice) => choice.pair));
                  const others = ALL_PAIRS.filter((pair) => !listed.has(pair));
                  return (
                    <div className="flex flex-col gap-1" data-pairs>
                      {choices.length > 0 ? (
                        <div role="group" aria-label="Ваші пари в ці дні" className="flex flex-col">
                          {choices.map((choice) => (
                            <Checkbox
                              key={choice.pair}
                              checked={field.value.includes(choice.pair)}
                              onCheckedChange={(checked) => toggle(choice.pair, checked === true)}
                              label={
                                <>
                                  <span className="font-[650] figures">
                                    {choice.pair} пара · {choice.startsAt}
                                  </span>
                                  {oneDay ? <span className="text-secondary"> · {choiceText(choice, true)}</span> : null}
                                </>
                              }
                              description={oneDay ? undefined : choiceText(choice, false)}
                            />
                          ))}
                          {/* core задає номери пар на весь діапазон: позначене стосується кожного дня, де ця пара є */}
                          {oneDay ? null : <p className="pt-1 text-caption text-muted">Позначена пара — в усі обрані дні, де вона у вас є.</p>}
                        </div>
                      ) : (
                        <p className="text-ui text-secondary">У ці дні за розкладом у вас пар немає.</p>
                      )}
                      {/* рідкісний випадок (консультація, пара поза розкладом) — номери пар за запитом */}
                      <OtherPairs
                        pairs={others}
                        selected={field.value}
                        open={choices.length === 0 || field.value.some((pair) => !listed.has(pair))}
                        onToggle={toggle}
                      />
                    </div>
                  );
                }}
              />
            ) : null}
            {errors.pairs?.message ? (
              <p className="flex items-start gap-1.5 text-caption font-medium text-danger">
                <CircleExclamationPoint aria-hidden="true" className="mt-px size-3.5" />
                {errors.pairs.message}
              </p>
            ) : null}
            {rangeValid && wholeDay ? (
              <div className="rounded-popover bg-sunken px-3.5 py-3 text-caption" aria-live="polite" aria-busy={!previewData && !unchecked}>
                {previewData ? (
                  <AffectedPreview own={own} />
                ) : tooLong ? (
                  <p className="text-secondary">Розклад перевіряємо лише на 31 день, тож пар тут не покажемо. Відсутність можна повідомити й так.</p>
                ) : previewFailed ? (
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
              // помилка стає на місце підказки, а не над нею (DESIGN §11.4); групу описує те, що зараз видно
              <fieldset className="flex flex-col gap-2" data-reasons aria-describedby={reasonError ? reasonErrorId : reasonHelpId}>
                <legend className="mb-2 text-ui font-[650] text-primary">Причина</legend>
                <div ref={reasonsBox}>
                  <RadioGroup
                    value={field.value ?? null}
                    onValueChange={(value) => {
                      focusComment.current = value === 'OTHER' && reasonByPointer.current;
                      field.onChange(value);
                    }}
                    options={REASONS.map((value) => ({ value, label: ABSENCE_REASON_LABEL[value] }))}
                  />
                </div>
                {otherReason ? <CommentField control={form.control} inputRef={commentRef} other error={errors.comment?.message} /> : null}
                {reasonError ? (
                  <p id={reasonErrorId} className="flex items-start gap-1.5 text-caption font-medium text-danger">
                    <CircleExclamationPoint aria-hidden="true" className="mt-px size-3.5" />
                    {reasonError}
                  </p>
                ) : (
                  <p id={reasonHelpId} className="text-caption text-muted">
                    Причину й коментар бачить лише диспетчер. На сайті й у розпорядженні буде тільки «відсутній» («Відп.») або «відрядження» («Відр.»).
                  </p>
                )}
              </fieldset>
            )}
          />

          {otherReason ? null : <CommentField control={form.control} inputRef={commentRef} error={errors.comment?.message} />}

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
