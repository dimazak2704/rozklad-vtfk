import { zodResolver } from '@hookform/resolvers/zod';
import { useId, useState } from 'react';
import { Controller, useForm, useWatch } from 'react-hook-form';
import { z } from 'zod';
import type { CalendarSemester } from '@/api/calendar';
import { useCatalogOptions } from '@/api/catalog';
import { useSaveLoadItem, type LoadItem } from '@/api/load';
import type { ControlForm, LessonKind, RoomType } from '@/api/types';
import { applyServerErrors, blankToNull } from '@/components/catalog/forms';
import { FormAlert, FormDialog } from '@/components/catalog/FormParts';
import { ROOM_TYPE_OPTIONS } from '@/components/catalog/labels';
import { Checkbox, Combobox, Field, NumberInput, RadioGroup, Select, Textarea, notify } from '@/components/ui';
import { CONTROL_FORM_LABEL, LESSON_KIND_OPTIONS } from './labels';
import { DEFAULT_HOURS_PER_PAIR, formatHours, loadPattern, patternDescription } from './pattern';
import { WeeklyPatternView } from './WeeklyPatternView';

const loadSchema = z
  .object({
    groupId: z.string({ error: 'Оберіть групу' }).min(1, 'Оберіть групу'),
    disciplineId: z.string({ error: 'Оберіть дисципліну' }).min(1, 'Оберіть дисципліну'),
    lessonKind: z.enum(['LECTURE', 'PRACTICE', 'LAB', 'SEMINAR', 'MIXED']),
    hoursPerWeek: z
      .number({ error: 'Вкажіть години на тиждень' })
      .gt(0, 'Більше 0')
      .max(40, 'Не більше 40 годин на тиждень')
      .refine((value) => Math.round(value * 10) === value * 10, 'Один знак після коми, наприклад 1,5'),
    plannedHours: z.number().int('Ціле число').min(0, 'Не менше 0').max(2000, 'Не більше 2000').nullable(),
    splitSubgroups: z.boolean(),
    /** підгрупа → викладач (0 — уся група) */
    teachers: z.record(z.string(), z.string().nullable()),
    requiredRoomType: z.string().nullable(),
    preferredRoomId: z.string().nullable(),
    controlForm: z.enum(['EXAM', 'CREDIT', 'NONE']),
    note: z.string().trim().max(1000, 'Не довше за 1000 символів'),
  })
  .superRefine((value, ctx) => {
    if (value.splitSubgroups && Object.values(value.teachers).every((teacher) => !teacher)) {
      ctx.addIssue({ code: 'custom', path: ['teachers'], message: 'Вкажіть викладача хоча б для однієї підгрупи' });
    }
  });

type LoadValues = z.infer<typeof loadSchema>;
const FIELDS = [
  'groupId',
  'disciplineId',
  'lessonKind',
  'hoursPerWeek',
  'plannedHours',
  'splitSubgroups',
  'teachers',
  'requiredRoomType',
  'preferredRoomId',
  'controlForm',
  'note',
] as const;
const ANY = 'any';

interface LoadFormDialogProps {
  semester: CalendarSemester;
  item: LoadItem | null;
  /** Група, вибрана у фільтрі сторінки, — для нового запису. */
  defaultGroupId: string | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

/** Запис навантаження: група × дисципліна, години на тиждень з наочним шаблоном «1 + ½», викладачі за підгрупами. */
export function LoadFormDialog({ semester, item, defaultGroupId, open, onOpenChange }: LoadFormDialogProps) {
  const formId = useId();
  const save = useSaveLoadItem();
  const groups = useCatalogOptions('groups', { active: true });
  const disciplines = useCatalogOptions('disciplines', { active: true });
  const teachers = useCatalogOptions('teachers', { active: true });
  const rooms = useCatalogOptions('rooms', { active: true });
  const [formError, setFormError] = useState<string | null>(null);
  const form = useForm<LoadValues>({
    resolver: zodResolver(loadSchema),
    defaultValues: {
      groupId: item?.group.id ?? defaultGroupId ?? '',
      disciplineId: item?.discipline.id ?? '',
      lessonKind: item?.lessonKind ?? 'MIXED',
      hoursPerWeek: item?.hoursPerWeek ?? 2,
      plannedHours: item?.plannedHours ?? null,
      splitSubgroups: item?.splitSubgroups ?? false,
      teachers: Object.fromEntries((item?.teachers ?? []).map((t) => [String(t.subgroup), t.teacher.id])),
      requiredRoomType: item?.requiredRoomType ?? null,
      preferredRoomId: item?.preferredRoom?.id ?? null,
      controlForm: item?.controlForm ?? 'NONE',
      note: item?.note ?? '',
    },
  });
  const { errors, isDirty } = form.formState;
  const [groupId, hoursPerWeek, split] = useWatch({ control: form.control, name: ['groupId', 'hoursPerWeek', 'splitSubgroups'] });
  const group = groups.items?.find((g) => g.id === groupId);
  const subgroupNumbers = (group?.subgroups ?? []).map((s) => s.number);
  const pattern = loadPattern(hoursPerWeek ?? 0);
  // помилка всього словника «підгрупа → викладач» (не окремої підгрупи)
  const teachersError = typeof errors.teachers?.message === 'string' ? errors.teachers.message : undefined;
  const plannedDefault = hoursPerWeek ? Math.round(hoursPerWeek * semester.teachingWeeks) : null;

  const submit = form.handleSubmit(async (values) => {
    setFormError(null);
    const assigned = Object.entries(values.teachers)
      .filter(([subgroup, teacher]) => teacher && (values.splitSubgroups ? subgroup !== '0' && subgroupNumbers.includes(Number(subgroup)) : subgroup === '0'))
      .map(([subgroup, teacher]) => ({ subgroup: Number(subgroup), teacherId: teacher as string }));
    try {
      const saved = await save.mutateAsync({
        id: item?.id,
        body: {
          semesterId: semester.id,
          groupId: values.groupId,
          disciplineId: values.disciplineId,
          lessonKind: values.lessonKind,
          hoursPerWeek: values.hoursPerWeek,
          plannedHours: values.plannedHours,
          splitSubgroups: values.splitSubgroups,
          teachers: assigned,
          requiredRoomType: values.requiredRoomType as RoomType | null,
          preferredRoomId: values.preferredRoomId,
          controlForm: values.controlForm,
          note: blankToNull(values.note),
          version: item?.version,
        },
      });
      notify.success(`${saved.group.code} · ${saved.discipline.shortName}: ${item ? 'навантаження збережено' : 'навантаження додано'}`);
      onOpenChange(false);
    } catch (error) {
      setFormError(applyServerErrors(error, form.setError, FIELDS, { 'teachers[0].teacherId': 'teachers', 'teachers[0].subgroup': 'teachers' }));
    }
  });

  const teacherOptions = (teachers.items ?? []).map((t) => ({ value: t.id, label: t.shortName, keywords: [t.displayName] }));
  const teacherField = (subgroup: number, label: string) => (
    <Controller
      key={subgroup}
      control={form.control}
      name={`teachers.${subgroup}`}
      render={({ field }) => (
        <Field label={label}>
          <Combobox
            value={field.value ?? null}
            onValueChange={field.onChange}
            options={teacherOptions}
            placeholder="Оберіть викладача"
            searchPlaceholder="Прізвище"
            loading={teachers.isPending}
            clearable
          />
        </Field>
      )}
    />
  );

  return (
    <FormDialog
      open={open}
      onOpenChange={onOpenChange}
      title={item ? `${item.group.code} · ${item.discipline.shortName}` : 'Нове навантаження'}
      description={`${semester.name}, ${semester.teachingWeeks} навчальних тижнів`}
      formId={formId}
      submitLabel={item ? 'Зберегти' : 'Додати навантаження'}
      pending={save.isPending}
      dirty={isDirty}
      size="xl"
    >
      <form id={formId} noValidate onSubmit={(event) => void submit(event)} className="flex flex-col gap-4">
        <FormAlert message={formError} />
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-[10rem_minmax(0,1fr)_12rem]">
          <Field label="Група" required error={errors.groupId?.message} hint={item ? 'Групу не змінюють: створіть новий запис' : undefined}>
            <Controller
              control={form.control}
              name="groupId"
              render={({ field }) => (
                <Combobox
                  value={field.value || null}
                  onValueChange={(value) => field.onChange(value ?? '')}
                  options={(groups.items ?? []).map((g) => ({ value: g.id, label: g.code, description: `${g.course} курс` }))}
                  placeholder="Група"
                  searchPlaceholder="Код групи"
                  loading={groups.isPending}
                  disabled={Boolean(item)}
                />
              )}
            />
          </Field>
          <Field label="Дисципліна" required error={errors.disciplineId?.message}>
            <Controller
              control={form.control}
              name="disciplineId"
              render={({ field }) => (
                <Combobox
                  value={field.value || null}
                  onValueChange={(value) => field.onChange(value ?? '')}
                  options={(disciplines.items ?? []).map((d) => ({ value: d.id, label: d.shortName, description: d.name, keywords: [d.name] }))}
                  placeholder="Оберіть дисципліну"
                  searchPlaceholder="Скорочення або назва"
                  loading={disciplines.isPending}
                />
              )}
            />
          </Field>
          <Field label="Вид занять" error={errors.lessonKind?.message}>
            <Controller
              control={form.control}
              name="lessonKind"
              render={({ field }) => <Select<LessonKind> value={field.value} onValueChange={field.onChange} options={LESSON_KIND_OPTIONS} />}
            />
          </Field>
        </div>

        <div className="grid gap-4 rounded-panel border border-default bg-sunken p-4 sm:grid-cols-[11rem_11rem_minmax(0,1fr)]">
          <Field label="Годин на тиждень" required error={errors.hoursPerWeek?.message}>
            <Controller
              control={form.control}
              name="hoursPerWeek"
              render={({ field }) => (
                <NumberInput value={field.value} onValueChange={(value) => field.onChange(value ?? 0)} min={0} max={40} step={0.5} precision={1} unit="год" />
              )}
            />
          </Field>
          <Field
            label="За семестр"
            error={errors.plannedHours?.message}
            hint={plannedDefault !== null ? `Порожньо — ${formatHours(hoursPerWeek)} × ${semester.teachingWeeks} = ${plannedDefault} год` : undefined}
          >
            <Controller
              control={form.control}
              name="plannedHours"
              render={({ field }) => <NumberInput value={field.value} onValueChange={field.onChange} min={0} max={2000} unit="год" />}
            />
          </Field>
          <div className="flex min-w-0 flex-col gap-1.5" aria-live="polite">
            <span className="text-ui font-semibold text-primary">У розкладі</span>
            <WeeklyPatternView pattern={pattern} size="md" />
            <p className="text-caption text-secondary">
              {patternDescription(pattern)}{' '}
              <span className="text-muted">Розраховано для {DEFAULT_HOURS_PER_PAIR} академічних годин на пару.</span>
            </p>
          </div>
        </div>

        <div className="flex flex-col gap-3">
          <Controller
            control={form.control}
            name="splitSubgroups"
            render={({ field }) => (
              <Checkbox
                checked={field.value}
                onCheckedChange={(checked) => field.onChange(checked === true)}
                disabled={subgroupNumbers.length === 0}
                label="Ділити групу на підгрупи"
                description={
                  subgroupNumbers.length === 0
                    ? group
                      ? `Група ${group.code} не поділена — підгрупи додають у довіднику груп.`
                      : 'Спершу оберіть групу.'
                    : 'Кожна підгрупа має своїх викладачів і свої пари.'
                }
              />
            )}
          />
          <div className="grid gap-4 sm:grid-cols-2">
            {split && subgroupNumbers.length > 0
              ? subgroupNumbers.map((number) => teacherField(number, `Викладач ${number} підгрупи`))
              : teacherField(0, 'Викладач')}
          </div>
          {teachersError ? <p className="text-caption font-medium text-danger">{teachersError}</p> : null}
        </div>

        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Потрібен тип аудиторії" hint="Генератор не поставить пару в інший тип">
            <Controller
              control={form.control}
              name="requiredRoomType"
              render={({ field }) => (
                <Select<string>
                  value={field.value ?? ANY}
                  onValueChange={(value) => field.onChange(value === ANY ? null : value)}
                  options={[{ value: ANY, label: 'Будь-який' }, ...ROOM_TYPE_OPTIONS]}
                />
              )}
            />
          </Field>
          <Field label="Бажана аудиторія">
            <Controller
              control={form.control}
              name="preferredRoomId"
              render={({ field }) => (
                <Combobox
                  value={field.value}
                  onValueChange={field.onChange}
                  options={(rooms.items ?? []).map((r) => ({ value: r.id, label: r.name }))}
                  placeholder="Не важливо"
                  searchPlaceholder="Номер"
                  loading={rooms.isPending}
                  clearable
                />
              )}
            />
          </Field>
        </div>
        <Controller
          control={form.control}
          name="controlForm"
          render={({ field }) => (
            <RadioGroup<ControlForm>
              legend="Форма контролю"
              orientation="horizontal"
              value={field.value}
              onValueChange={field.onChange}
              options={(['EXAM', 'CREDIT', 'NONE'] as const).map((value) => ({ value, label: CONTROL_FORM_LABEL[value] }))}
            />
          )}
        />
        <Field label="Примітка" error={errors.note?.message}>
          <Textarea {...form.register('note')} rows={2} placeholder="Звідки години: програма, план, домовленість" />
        </Field>
      </form>
    </FormDialog>
  );
}
