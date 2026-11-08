import { zodResolver } from '@hookform/resolvers/zod';
import { useId, useState } from 'react';
import { Controller, useForm, useWatch } from 'react-hook-form';
import { z } from 'zod';
import { useBellProfiles, useSaveSemester, type CalendarSemester } from '@/api/calendar';
import { applyServerErrors, blankToNull } from '@/components/catalog/forms';
import { FormAlert, FormDialog } from '@/components/catalog/FormParts';
import { Field, Input, NumberInput, RadioGroup, SegmentedControl, Select, notify } from '@/components/ui';
import { diffDays, isIsoDate, startOfWeek } from '@/lib/date';

const semesterSchema = z
  .object({
    number: z.enum(['1', '2']),
    academicYear: z.string().trim().refine((v) => v === '' || /^\d{4}\/\d{4}$/.test(v), 'Навчальний рік має вигляд 2026/2027'),
    name: z.string().trim().max(100, 'Не довше за 100 символів'),
    startsOn: z.string().refine(isIsoDate, 'Вкажіть дату початку'),
    endsOn: z.string().refine(isIsoDate, 'Вкажіть дату кінця'),
    firstWeekParity: z.enum(['NUMERATOR', 'DENOMINATOR']),
    weeksCount: z.number().int('Ціле число').min(1, 'Від 1').max(53, 'До 53').nullable(),
    bellProfileId: z.string().nullable(),
  })
  .refine((v) => !isIsoDate(v.startsOn) || !isIsoDate(v.endsOn) || v.endsOn > v.startsOn, {
    message: 'Кінець семестру має бути пізніше за початок',
    path: ['endsOn'],
  });

type SemesterValues = z.infer<typeof semesterSchema>;
const FIELDS = ['number', 'academicYear', 'name', 'startsOn', 'endsOn', 'firstWeekParity', 'weeksCount', 'bellProfileId'] as const;
const DEFAULT_PROFILE = 'default';
const ROMAN = { '1': 'I', '2': 'II' } as const;

interface SemesterFormDialogProps {
  semester: CalendarSemester | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onSaved?: (semester: CalendarSemester) => void;
}

/** Семестр: дати, парність першого тижня (від неї рахуються всі тижні), дзвінки. */
export function SemesterFormDialog({ semester, open, onOpenChange, onSaved }: SemesterFormDialogProps) {
  const formId = useId();
  const save = useSaveSemester();
  const profiles = useBellProfiles();
  const [formError, setFormError] = useState<string | null>(null);
  const form = useForm<SemesterValues>({
    resolver: zodResolver(semesterSchema),
    defaultValues: {
      number: semester ? (String(semester.number) as '1' | '2') : '1',
      academicYear: semester?.academicYear ?? '',
      name: semester?.name ?? '',
      startsOn: semester?.startsOn ?? '',
      endsOn: semester?.endsOn ?? '',
      firstWeekParity: semester?.firstWeekParity ?? 'NUMERATOR',
      weeksCount: semester?.weeksCount ?? null,
      bellProfileId: semester?.bellProfileId ?? null,
    },
  });
  const { errors, isDirty } = form.formState;
  const [number, academicYear, startsOn, endsOn] = useWatch({ control: form.control, name: ['number', 'academicYear', 'startsOn', 'endsOn'] });
  const startYear = isIsoDate(startsOn) ? Number(startsOn.slice(0, 4)) : null;
  const derivedYear = startYear ? (number === '2' ? `${startYear - 1}/${startYear}` : `${startYear}/${startYear + 1}`) : '';
  const year = academicYear.trim() || derivedYear;
  const spanWeeks =
    isIsoDate(startsOn) && isIsoDate(endsOn) && endsOn > startsOn ? Math.floor(diffDays(startOfWeek(startsOn), startOfWeek(endsOn)) / 7) + 1 : null;

  const submit = form.handleSubmit(async (values) => {
    setFormError(null);
    try {
      const saved = await save.mutateAsync({
        id: semester?.id,
        body: {
          number: Number(values.number),
          academicYear: blankToNull(values.academicYear),
          name: blankToNull(values.name),
          startsOn: values.startsOn,
          endsOn: values.endsOn,
          firstWeekParity: values.firstWeekParity,
          weeksCount: values.weeksCount,
          bellProfileId: values.bellProfileId,
          version: semester?.version,
        },
      });
      notify.success(semester ? `Семестр «${saved.name}» збережено` : `Семестр «${saved.name}» створено`);
      onSaved?.(saved);
      onOpenChange(false);
    } catch (error) {
      setFormError(applyServerErrors(error, form.setError, FIELDS));
    }
  });

  const profileOptions = [
    { value: DEFAULT_PROFILE, label: 'Типовий з налаштувань' },
    ...(profiles.data ?? []).map((p) => ({ value: p.id, label: p.name, description: p.defaultProfile ? 'Типовий' : undefined })),
  ];

  return (
    <FormDialog
      open={open}
      onOpenChange={onOpenChange}
      title={semester ? semester.name : 'Новий семестр'}
      description="Тиждень 1 — той, що містить дату початку; далі чисельник і знаменник чергуються."
      formId={formId}
      submitLabel={semester ? 'Зберегти' : 'Створити семестр'}
      pending={save.isPending}
      dirty={isDirty}
      size="lg"
    >
      <form id={formId} noValidate onSubmit={(event) => void submit(event)} className="flex flex-col gap-4">
        <FormAlert message={formError} />
        <div className="grid gap-4 sm:grid-cols-[auto_minmax(0,1fr)_minmax(0,1fr)]">
          <Controller
            control={form.control}
            name="number"
            render={({ field }) => (
              <RadioGroup
                legend="Семестр"
                orientation="horizontal"
                value={field.value}
                onValueChange={field.onChange}
                options={[
                  { value: '1', label: 'I (осінній)' },
                  { value: '2', label: 'II (весняний)' },
                ]}
              />
            )}
          />
          <Field label="Навчальний рік" error={errors.academicYear?.message} hint={derivedYear ? `Порожньо — ${derivedYear}` : 'Наприклад, 2026/2027'}>
            <Input {...form.register('academicYear')} placeholder={derivedYear || '2026/2027'} autoComplete="off" inputMode="numeric" />
          </Field>
          <Field label="Назва" error={errors.name?.message} hint="Порожньо — складемо автоматично">
            <Input {...form.register('name')} placeholder={year ? `${ROMAN[number]} семестр ${year}` : 'I семестр 2026/2027'} autoComplete="off" />
          </Field>
        </div>
        <div className="grid gap-4 sm:grid-cols-3">
          <Field label="Перший день" required error={errors.startsOn?.message}>
            <Input type="date" {...form.register('startsOn')} />
          </Field>
          <Field label="Останній день" required error={errors.endsOn?.message} hint={spanWeeks ? `${spanWeeks} тижнів за календарем` : undefined}>
            <Input type="date" {...form.register('endsOn')} />
          </Field>
          <Field label="Навчальних тижнів" error={errors.weeksCount?.message} hint="Порожньо — за датами">
            <Controller
              control={form.control}
              name="weeksCount"
              render={({ field }) => <NumberInput value={field.value} onValueChange={field.onChange} min={1} max={53} />}
            />
          </Field>
        </div>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Перший тиждень" hint="Від нього рахується чисельник і знаменник усього семестру">
            <Controller
              control={form.control}
              name="firstWeekParity"
              render={({ field }) => (
                <SegmentedControl
                  aria-label="Парність першого тижня"
                  value={field.value}
                  onValueChange={field.onChange}
                  options={[
                    { value: 'NUMERATOR', label: 'Чисельник' },
                    { value: 'DENOMINATOR', label: 'Знаменник' },
                  ]}
                />
              )}
            />
          </Field>
          <Field label="Дзвінки" error={errors.bellProfileId?.message}>
            <Controller
              control={form.control}
              name="bellProfileId"
              render={({ field }) => (
                <Select
                  value={field.value ?? DEFAULT_PROFILE}
                  onValueChange={(value) => field.onChange(value === DEFAULT_PROFILE ? null : value)}
                  options={profileOptions}
                />
              )}
            />
          </Field>
        </div>
      </form>
    </FormDialog>
  );
}
