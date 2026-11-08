import { zodResolver } from '@hookform/resolvers/zod';
import { FileText, X } from 'lucide-react';
import { useId, useState } from 'react';
import { Controller, useForm, useWatch } from 'react-hook-form';
import { z } from 'zod';
import { useSaveCalendarPeriod, type CalendarPeriod, type CalendarSemester } from '@/api/calendar';
import { useCatalogOptions } from '@/api/catalog';
import type { CalendarPeriodKind, PeriodScope, StudyMode } from '@/api/types';
import { applyServerErrors, blankToNull } from '@/components/catalog/forms';
import { FormAlert, FormDialog } from '@/components/catalog/FormParts';
import { Button, Chip, Combobox, Field, IconButton, Input, Plate, RadioGroup, Select, Textarea, notify } from '@/components/ui';
import { formatNumericDate, isIsoDate } from '@/lib/date';
import { PERIOD_KIND_EFFECT, PERIOD_KIND_OPTIONS, SCOPE_LABEL, STUDY_MODE_LABEL } from './labels';
import { composeOrderNote } from './notes';

const periodSchema = z
  .object({
    kind: z.enum(['HOLIDAY', 'NO_CLASSES', 'PRACTICE', 'STUDY_MODE', 'EXAM_SESSION']),
    title: z.string().trim().min(1, 'Назвіть період: «Осінні канікули», «Технологічна практика»').max(300, 'Не довше за 300 символів'),
    startsOn: z.string().refine(isIsoDate, 'Вкажіть дату початку'),
    endsOn: z.string().refine(isIsoDate, 'Вкажіть дату кінця'),
    studyMode: z.enum(['OFFLINE', 'ONLINE', 'MIXED']).nullable(),
    scope: z.enum(['ALL', 'COURSES', 'GROUPS']),
    courses: z.array(z.number()),
    groupIds: z.array(z.string()),
    orderNote: z.string().trim().max(500, 'Не довше за 500 символів'),
  })
  .superRefine((value, ctx) => {
    if (isIsoDate(value.startsOn) && isIsoDate(value.endsOn) && value.endsOn < value.startsOn) {
      ctx.addIssue({ code: 'custom', path: ['endsOn'], message: 'Кінець не може бути раніше за початок' });
    }
    if (value.kind === 'STUDY_MODE' && !value.studyMode) {
      ctx.addIssue({ code: 'custom', path: ['studyMode'], message: 'Оберіть форму навчання' });
    }
    if (value.scope === 'COURSES' && value.courses.length === 0) {
      ctx.addIssue({ code: 'custom', path: ['courses'], message: 'Позначте хоча б один курс' });
    }
    if (value.scope === 'GROUPS' && value.groupIds.length === 0) {
      ctx.addIssue({ code: 'custom', path: ['groupIds'], message: 'Додайте хоча б одну групу' });
    }
  });

type PeriodValues = z.infer<typeof periodSchema>;
const FIELDS = ['kind', 'title', 'startsOn', 'endsOn', 'studyMode', 'scope', 'courses', 'groupIds', 'orderNote'] as const;
const COURSES = [1, 2, 3, 4] as const;

interface PeriodFormDialogProps {
  semester: CalendarSemester;
  period: CalendarPeriod | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

/**
 * Період календаря: канікули, практика, режим навчання. Кого стосується (усі, курси, групи) і примітка,
 * яку система додасть у розпорядження на ці дати, — з попереднім переглядом на «аркуші».
 */
export function PeriodFormDialog({ semester, period, open, onOpenChange }: PeriodFormDialogProps) {
  const formId = useId();
  const save = useSaveCalendarPeriod();
  const groups = useCatalogOptions('groups', { active: true });
  const [formError, setFormError] = useState<string | null>(null);
  const form = useForm<PeriodValues>({
    resolver: zodResolver(periodSchema),
    defaultValues: {
      kind: period?.kind ?? 'PRACTICE',
      title: period?.title ?? '',
      startsOn: period?.startsOn ?? '',
      endsOn: period?.endsOn ?? '',
      studyMode: period?.studyMode ?? null,
      scope: period?.scope ?? 'GROUPS',
      courses: period?.courses ?? [],
      groupIds: period?.groupIds ?? [],
      orderNote: period?.orderNote ?? '',
    },
  });
  const { errors, isDirty } = form.formState;
  const values = useWatch({ control: form.control }) as PeriodValues;
  const groupById = new Map((groups.items ?? []).map((g) => [g.id, g]));
  const chosenGroups = values.groupIds.map((id) => groupById.get(id)).filter((g) => g !== undefined);
  const draftNote = composeOrderNote({
    kind: values.kind,
    title: values.title ?? '',
    startsOn: isIsoDate(values.startsOn) ? values.startsOn : '',
    endsOn: isIsoDate(values.endsOn) ? values.endsOn : '',
    scope: values.scope,
    courses: values.courses,
    groupCodes: chosenGroups.map((g) => g.code),
    studyMode: values.studyMode,
  });

  const submit = form.handleSubmit(async (data) => {
    setFormError(null);
    try {
      const saved = await save.mutateAsync({
        id: period?.id,
        body: {
          semesterId: semester.id,
          kind: data.kind,
          title: data.title.trim(),
          startsOn: data.startsOn,
          endsOn: data.endsOn,
          studyMode: data.kind === 'STUDY_MODE' ? data.studyMode : null,
          scope: data.scope,
          courses: data.scope === 'COURSES' ? data.courses : null,
          groupIds: data.scope === 'GROUPS' ? data.groupIds : null,
          orderNote: blankToNull(data.orderNote),
          version: period?.version,
        },
      });
      notify.success(period ? `Період «${saved.title}» збережено` : `Період «${saved.title}» додано`);
      onOpenChange(false);
    } catch (error) {
      setFormError(applyServerErrors(error, form.setError, FIELDS, { 'groupIds[0]': 'groupIds' }));
    }
  });

  const groupOptions = (groups.items ?? [])
    .filter((g) => !values.groupIds.includes(g.id))
    .map((g) => ({ value: g.id, label: g.code, description: `${g.course} курс` }));

  return (
    <FormDialog
      open={open}
      onOpenChange={onOpenChange}
      title={period ? period.title : 'Новий період'}
      description={`${semester.name}: ${formatNumericDate(semester.startsOn)} – ${formatNumericDate(semester.endsOn)}`}
      formId={formId}
      submitLabel={period ? 'Зберегти' : 'Додати період'}
      pending={save.isPending}
      dirty={isDirty}
      size="xl"
    >
      <form id={formId} noValidate onSubmit={(event) => void submit(event)} className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_19rem]">
        <div className="flex min-w-0 flex-col gap-4">
          <FormAlert message={formError} />
          <div className="grid gap-4 sm:grid-cols-[14rem_minmax(0,1fr)]">
            <Field label="Вид" required error={errors.kind?.message} hint={PERIOD_KIND_EFFECT[values.kind]}>
              <Controller
                control={form.control}
                name="kind"
                render={({ field }) => (
                  <Select<CalendarPeriodKind>
                    value={field.value}
                    onValueChange={(next) => {
                      field.onChange(next);
                      if (next !== 'STUDY_MODE') form.setValue('studyMode', null);
                    }}
                    options={PERIOD_KIND_OPTIONS}
                  />
                )}
              />
            </Field>
            <Field label="Назва" required error={errors.title?.message}>
              <Input {...form.register('title')} autoComplete="off" autoFocus={!period} placeholder="Електрорадіомонтажна практика" />
            </Field>
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="З" required error={errors.startsOn?.message}>
              <Input type="date" min={semester.startsOn} max={semester.endsOn} {...form.register('startsOn')} />
            </Field>
            <Field label="По (включно)" required error={errors.endsOn?.message}>
              <Input type="date" min={semester.startsOn} max={semester.endsOn} {...form.register('endsOn')} />
            </Field>
          </div>
          {values.kind === 'STUDY_MODE' ? (
            <Controller
              control={form.control}
              name="studyMode"
              render={({ field }) => (
                <div className="flex flex-col gap-1">
                  <RadioGroup<StudyMode>
                    legend="Форма навчання"
                    orientation="horizontal"
                    value={field.value}
                    onValueChange={field.onChange}
                    options={(['ONLINE', 'MIXED', 'OFFLINE'] as const).map((mode) => ({ value: mode, label: STUDY_MODE_LABEL[mode] }))}
                  />
                  {errors.studyMode ? <p className="text-caption font-medium text-danger">{errors.studyMode.message}</p> : null}
                </div>
              )}
            />
          ) : null}
          <Controller
            control={form.control}
            name="scope"
            render={({ field }) => (
              <RadioGroup<PeriodScope>
                legend="Кого стосується"
                orientation="horizontal"
                value={field.value}
                onValueChange={field.onChange}
                options={(['ALL', 'COURSES', 'GROUPS'] as const).map((scope) => ({ value: scope, label: SCOPE_LABEL[scope] }))}
              />
            )}
          />
          {values.scope === 'COURSES' ? (
            <Controller
              control={form.control}
              name="courses"
              render={({ field }) => (
                <fieldset className="flex flex-col gap-2">
                  <legend className="sr-only">Курси</legend>
                  <div className="flex flex-wrap gap-2">
                    {COURSES.map((course) => {
                      const selected = field.value.includes(course);
                      return (
                        <Chip
                          key={course}
                          selected={selected}
                          onToggle={() =>
                            field.onChange(selected ? field.value.filter((c) => c !== course) : [...field.value, course].sort((a, b) => a - b))
                          }
                        >
                          {course} курс
                        </Chip>
                      );
                    })}
                  </div>
                  {errors.courses ? <p className="text-caption font-medium text-danger">{errors.courses.message}</p> : null}
                </fieldset>
              )}
            />
          ) : null}
          {values.scope === 'GROUPS' ? (
            <Controller
              control={form.control}
              name="groupIds"
              render={({ field }) => (
                <Field label="Групи" required error={errors.groupIds?.message}>
                  <div className="flex flex-col gap-2">
                    <Combobox
                      value={null}
                      onValueChange={(id) => {
                        if (id) field.onChange([...field.value, id]);
                      }}
                      options={groupOptions}
                      placeholder="Додати групу…"
                      searchPlaceholder="Код групи: 2КІ3 або 2ki3"
                      loading={groups.isPending}
                    />
                    {chosenGroups.length > 0 ? (
                      <ul className="flex flex-wrap gap-1.5" aria-label="Вибрані групи">
                        {chosenGroups.map((group) => (
                          <li key={group.id} className="inline-flex items-center gap-0.5">
                            <Plate size="md">{group.code}</Plate>
                            <IconButton
                              label={`Прибрати групу ${group.code}`}
                              icon={<X aria-hidden="true" className="size-3.5" />}
                              size="sm"
                              className="size-7"
                              onClick={() => field.onChange(field.value.filter((id) => id !== group.id))}
                            />
                          </li>
                        ))}
                      </ul>
                    ) : null}
                  </div>
                </Field>
              )}
            />
          ) : null}
        </div>

        <div className="flex min-w-0 flex-col gap-3 lg:border-s lg:border-separator lg:ps-5">
          <Field
            label="Примітка в розпорядженні"
            error={errors.orderNote?.message}
            hint="Друкується жирним унизу кожного розпорядження на дати періоду. Порожньо — без примітки."
          >
            <Textarea {...form.register('orderNote')} rows={3} placeholder={draftNote || '2КІ3 – Електрорадіомонтажна практика з 19.10.26р. по 30.10.26 р.'} />
          </Field>
          <Button
            size="sm"
            variant="secondary"
            className="self-start"
            iconStart={<FileText aria-hidden="true" className="size-4" />}
            disabled={!draftNote}
            onClick={() => form.setValue('orderNote', draftNote, { shouldDirty: true, shouldValidate: true })}
          >
            Скласти з полів
          </Button>
          <figure className="flex flex-col gap-1.5">
            <figcaption className="text-caption text-muted">Так виглядатиме під таблицею розпорядження</figcaption>
            <div data-theme="light" className="rounded-plate border border-default bg-surface px-4 py-3 text-primary">
              {/* нижній край таблиці розпорядження, під яким друкується примітка */}
              <div aria-hidden="true" className="mb-2.5 h-px bg-[var(--border-color-strong)]" />
              <p className="text-ui font-bold">
                {values.orderNote?.trim() || <span className="font-normal text-muted">Примітки не буде.</span>}
              </p>
            </div>
          </figure>
        </div>
      </form>
    </FormDialog>
  );
}
