import { zodResolver } from '@hookform/resolvers/zod';
import { useId, useState } from 'react';
import { Controller, useForm, useWatch } from 'react-hook-form';
import { Link } from 'react-router';
import { z } from 'zod';
import { useCatalogOptions, useSaveCatalogItem, type CatalogTeacher } from '@/api/catalog';
import { Checkbox, Field, Input, RadioGroup, Select, notify } from '@/components/ui';
import { AliasEditor } from './AliasEditor';
import { CompetenceEditor } from './CompetenceEditor';
import { applyServerErrors, blankToNull } from './forms';
import { FormAlert, FormDialog } from './FormParts';
import { EMPLOYMENT_OPTIONS } from './labels';
import { shortNameOf } from './names';

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const optionalName = z.string().trim().max(100, 'Не довше за 100 символів');

const teacherSchema = z.object({
  lastName: z.string().trim().min(1, 'Вкажіть прізвище').max(100, 'Не довше за 100 символів'),
  firstName: optionalName,
  middleName: optionalName,
  shortName: optionalName,
  displayName: optionalName,
  cycleCommissionId: z.string().nullable(),
  employment: z.enum(['STAFF', 'PART_TIME']),
  email: z
    .string()
    .trim()
    .max(254, 'Не довше за 254 символи')
    .refine((value) => value === '' || EMAIL_RE.test(value), 'Некоректна адреса електронної пошти'),
  needsReview: z.boolean(),
  active: z.boolean(),
});

type TeacherValues = z.infer<typeof teacherSchema>;

const FIELDS = [
  'lastName',
  'firstName',
  'middleName',
  'shortName',
  'displayName',
  'cycleCommissionId',
  'employment',
  'email',
  'needsReview',
  'active',
] as const;

const NONE = 'none';

interface TeacherFormDialogProps {
  teacher: CatalogTeacher | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

/**
 * Викладач: імʼя повністю (для документів), коротке «Кренцін М.Д.» (як у розкладі), публічне
 * «Кренцін» (на сайті), циклова комісія, псевдоніми з таблиць і дисципліни для ранжування замін.
 */
export function TeacherFormDialog({ teacher, open, onOpenChange }: TeacherFormDialogProps) {
  const formId = useId();
  const save = useSaveCatalogItem('teachers');
  const commissions = useCatalogOptions('cycle-commissions');
  const [formError, setFormError] = useState<string | null>(null);
  const form = useForm<TeacherValues>({
    resolver: zodResolver(teacherSchema),
    defaultValues: {
      lastName: teacher?.lastName ?? '',
      firstName: teacher?.firstName ?? '',
      middleName: teacher?.middleName ?? '',
      shortName: teacher?.shortName ?? '',
      displayName: teacher?.displayName ?? '',
      cycleCommissionId: teacher?.cycleCommission?.id ?? null,
      employment: teacher?.employment ?? 'STAFF',
      email: teacher?.email ?? '',
      needsReview: teacher?.needsReview ?? false,
      active: teacher?.active ?? true,
    },
  });
  const { errors, isDirty } = form.formState;
  const [lastName, firstName, middleName] = useWatch({ control: form.control, name: ['lastName', 'firstName', 'middleName'] });
  const suggestedShort = shortNameOf(lastName.trim(), blankToNull(firstName), blankToNull(middleName));

  const submit = form.handleSubmit(async (values) => {
    setFormError(null);
    try {
      const saved = await save.mutateAsync({
        id: teacher?.id,
        body: {
          lastName: values.lastName.trim(),
          firstName: blankToNull(values.firstName),
          middleName: blankToNull(values.middleName),
          shortName: blankToNull(values.shortName),
          displayName: blankToNull(values.displayName),
          cycleCommissionId: values.cycleCommissionId,
          employment: values.employment,
          email: blankToNull(values.email),
          needsReview: values.needsReview,
          active: values.active,
          version: teacher?.version,
        },
      });
      notify.success(teacher ? `Дані викладача ${saved.shortName} збережено` : `Викладача ${saved.shortName} додано`);
      onOpenChange(false);
    } catch (error) {
      setFormError(applyServerErrors(error, form.setError, FIELDS));
    }
  });

  const commissionOptions = [
    { value: NONE, label: 'Не вказано' },
    ...(commissions.items ?? []).map((c) => ({ value: c.id, label: c.name })),
  ];

  return (
    <FormDialog
      open={open}
      onOpenChange={onOpenChange}
      title={teacher ? teacher.shortName : 'Новий викладач'}
      description={
        teacher
          ? teacher.linked
            ? 'Має обліковий запис: бачить свій розклад і підтверджує заміни.'
            : 'Облікового запису ще немає: викладач отримає його за запрошенням.'
          : 'Достатньо прізвища: коротке ім’я складемо з ініціалів.'
      }
      formId={formId}
      submitLabel={teacher ? 'Зберегти' : 'Додати викладача'}
      pending={save.isPending}
      dirty={isDirty}
      size="lg"
    >
      <form id={formId} noValidate onSubmit={(event) => void submit(event)} className="flex flex-col gap-4">
        <FormAlert message={formError} />
        <div className="grid gap-4 sm:grid-cols-3">
          <Field label="Прізвище" required error={errors.lastName?.message}>
            <Input {...form.register('lastName')} autoComplete="off" autoFocus={!teacher} />
          </Field>
          <Field label="Імʼя" error={errors.firstName?.message}>
            <Input {...form.register('firstName')} autoComplete="off" />
          </Field>
          <Field label="По батькові" error={errors.middleName?.message}>
            <Input {...form.register('middleName')} autoComplete="off" />
          </Field>
        </div>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field
            label="Як у розкладі"
            error={errors.shortName?.message}
            hint={suggestedShort ? `Порожньо — буде «${suggestedShort}»` : 'Прізвище та ініціали: «Кренцін М.Д.»'}
          >
            <Input {...form.register('shortName')} placeholder={suggestedShort || 'Кренцін М.Д.'} autoComplete="off" />
          </Field>
          <Field
            label="Публічне імʼя"
            error={errors.displayName?.message}
            hint="Його бачать студенти на сайті; порожньо — прізвище"
          >
            <Input {...form.register('displayName')} placeholder={lastName.trim() || 'Кренцін'} autoComplete="off" />
          </Field>
          <Field
            label="Циклова комісія"
            error={errors.cycleCommissionId?.message}
            hint={
              commissions.items && commissions.items.length === 0 ? (
                <>
                  Комісій ще немає — додайте їх у{' '}
                  <Link to="/admin/catalog/structure?tab=commissions" className="font-semibold text-accent underline-offset-2 hover:underline">
                    «Структурі коледжу»
                  </Link>
                  .
                </>
              ) : (
                'Та сама комісія дає кандидату на заміну +20 балів'
              )
            }
          >
            <Controller
              control={form.control}
              name="cycleCommissionId"
              render={({ field }) => (
                <Select
                  value={field.value ?? NONE}
                  onValueChange={(value) => field.onChange(value === NONE ? null : value)}
                  options={commissionOptions}
                  disabled={commissionOptions.length === 1}
                />
              )}
            />
          </Field>
          <Field label="Електронна пошта" error={errors.email?.message} hint="Для запрошення до системи">
            <Input {...form.register('email')} type="email" inputMode="email" autoComplete="off" spellCheck={false} />
          </Field>
        </div>
        <Controller
          control={form.control}
          name="employment"
          render={({ field }) => (
            <RadioGroup legend="Зайнятість" orientation="horizontal" value={field.value} onValueChange={field.onChange} options={EMPLOYMENT_OPTIONS} />
          )}
        />
        <div className="flex flex-col gap-3 sm:flex-row sm:gap-8">
          <Controller
            control={form.control}
            name="needsReview"
            render={({ field }) => (
              <Checkbox
                checked={field.value}
                onCheckedChange={(checked) => field.onChange(checked === true)}
                label="Дані треба перевірити"
                description="Ставить імпорт, коли імені чи ініціалів немає в документах."
              />
            )}
          />
          <Controller
            control={form.control}
            name="active"
            render={({ field }) => (
              <Checkbox
                checked={field.value}
                onCheckedChange={(checked) => field.onChange(checked === true)}
                label="Працює в коледжі"
                description="Неактивного не пропонують у замінах і навантаженні."
              />
            )}
          />
        </div>
      </form>
      {teacher ? (
        <div className="mt-4 flex flex-col gap-4">
          <AliasEditor owner="teachers" ownerId={teacher.id} ownerLabel={teacher.shortName} example="Хорченко О." />
          <CompetenceEditor teacherId={teacher.id} teacherLabel={teacher.shortName} />
        </div>
      ) : null}
    </FormDialog>
  );
}
