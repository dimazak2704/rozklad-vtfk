import { zodResolver } from '@hookform/resolvers/zod';
import { useId, useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { z } from 'zod';
import {
  useCatalogOptions,
  useSaveCatalogItem,
  type CatalogBuilding,
  type CatalogCycleCommission,
  type CatalogDepartment,
  type CatalogSpecialty,
} from '@/api/catalog';
import { Field, Input, Select, notify } from '@/components/ui';
import { applyServerErrors, blankToNull } from './forms';
import { FormAlert, FormDialog } from './FormParts';

interface DialogProps<T> {
  item: T | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

// ───────────────────────── Відділення ─────────────────────────

const departmentSchema = z.object({
  name: z.string().trim().min(1, 'Вкажіть назву відділення').max(200, 'Не довше за 200 символів'),
  shortName: z.string().trim().max(50, 'Не довше за 50 символів'),
});

export function DepartmentFormDialog({ item, open, onOpenChange }: DialogProps<CatalogDepartment>) {
  const formId = useId();
  const save = useSaveCatalogItem('departments');
  const [formError, setFormError] = useState<string | null>(null);
  const form = useForm<z.infer<typeof departmentSchema>>({
    resolver: zodResolver(departmentSchema),
    defaultValues: { name: item?.name ?? '', shortName: item?.shortName ?? '' },
  });
  const { errors, isDirty } = form.formState;
  const submit = form.handleSubmit(async (values) => {
    setFormError(null);
    try {
      const saved = await save.mutateAsync({
        id: item?.id,
        body: { name: values.name.trim(), shortName: blankToNull(values.shortName), version: item?.version },
      });
      notify.success(item ? `Відділення «${saved.name}» збережено` : `Відділення «${saved.name}» додано`);
      onOpenChange(false);
    } catch (error) {
      setFormError(applyServerErrors(error, form.setError, ['name', 'shortName']));
    }
  });
  return (
    <FormDialog
      open={open}
      onOpenChange={onOpenChange}
      title={item ? item.name : 'Нове відділення'}
      formId={formId}
      submitLabel={item ? 'Зберегти' : 'Додати відділення'}
      pending={save.isPending}
      dirty={isDirty}
    >
      <form id={formId} noValidate onSubmit={(event) => void submit(event)} className="flex flex-col gap-4">
        <FormAlert message={formError} />
        <Field label="Назва" required error={errors.name?.message}>
          <Input {...form.register('name')} autoComplete="off" autoFocus={!item} placeholder="Компʼютерне відділення" />
        </Field>
        <Field label="Скорочення" error={errors.shortName?.message} hint="Необовʼязково">
          <Input {...form.register('shortName')} autoComplete="off" />
        </Field>
      </form>
    </FormDialog>
  );
}

// ───────────────────────── Спеціальності ─────────────────────────

const specialtySchema = z.object({
  letters: z
    .string()
    .trim()
    .min(1, 'Вкажіть літери з кодів груп, наприклад КІ')
    .max(3, 'Не більше 3 літер')
    .regex(/^[A-Za-zА-ЯІЇЄҐа-яіїєґ]+$/u, 'Лише літери, як у коді групи'),
  name: z.string().trim().min(1, 'Вкажіть назву спеціальності').max(200, 'Не довше за 200 символів'),
  code: z.string().trim().refine((value) => value === '' || /^\d{3}$/.test(value), 'Код спеціальності — три цифри, наприклад 123'),
  departmentId: z.string().nullable(),
});

const NONE = 'none';

export function SpecialtyFormDialog({ item, open, onOpenChange }: DialogProps<CatalogSpecialty>) {
  const formId = useId();
  const save = useSaveCatalogItem('specialties');
  const departments = useCatalogOptions('departments');
  const [formError, setFormError] = useState<string | null>(null);
  const form = useForm<z.infer<typeof specialtySchema>>({
    resolver: zodResolver(specialtySchema),
    defaultValues: {
      letters: item?.letters ?? '',
      name: item?.name ?? '',
      code: item?.code ?? '',
      departmentId: item?.departmentId ?? null,
    },
  });
  const { errors, isDirty } = form.formState;
  const submit = form.handleSubmit(async (values) => {
    setFormError(null);
    try {
      const saved = await save.mutateAsync({
        id: item?.id,
        body: {
          letters: values.letters.trim(),
          name: values.name.trim(),
          code: blankToNull(values.code),
          departmentId: values.departmentId,
          version: item?.version,
        },
      });
      notify.success(item ? `Спеціальність ${saved.letters} збережено` : `Спеціальність ${saved.letters} додано`);
      onOpenChange(false);
    } catch (error) {
      setFormError(applyServerErrors(error, form.setError, ['letters', 'name', 'code', 'departmentId']));
    }
  });
  const departmentOptions = [{ value: NONE, label: 'Не вказано' }, ...(departments.items ?? []).map((d) => ({ value: d.id, label: d.name }))];
  return (
    <FormDialog
      open={open}
      onOpenChange={onOpenChange}
      title={item ? `Спеціальність ${item.letters}` : 'Нова спеціальність'}
      description="Літери — ті, що в кодах груп: «КІ» у «4КІ1». Латиницю виправимо."
      formId={formId}
      submitLabel={item ? 'Зберегти' : 'Додати спеціальність'}
      pending={save.isPending}
      dirty={isDirty}
    >
      <form id={formId} noValidate onSubmit={(event) => void submit(event)} className="flex flex-col gap-4">
        <FormAlert message={formError} />
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Літери" required error={errors.letters?.message}>
            <Input {...form.register('letters')} autoComplete="off" spellCheck={false} autoFocus={!item} className="font-condensed font-bold" placeholder="КІ" />
          </Field>
          <Field label="Код класифікатора" error={errors.code?.message} hint="Три цифри; можна лишити порожнім">
            <Input {...form.register('code')} autoComplete="off" inputMode="numeric" maxLength={3} placeholder="123" />
          </Field>
        </div>
        <Field label="Назва" required error={errors.name?.message}>
          <Input {...form.register('name')} autoComplete="off" placeholder="Компʼютерна інженерія" />
        </Field>
        <Field label="Відділення" error={errors.departmentId?.message}>
          <Controller
            control={form.control}
            name="departmentId"
            render={({ field }) => (
              <Select value={field.value ?? NONE} onValueChange={(value) => field.onChange(value === NONE ? null : value)} options={departmentOptions} />
            )}
          />
        </Field>
      </form>
    </FormDialog>
  );
}

// ───────────────────────── Циклові комісії й корпуси ─────────────────────────

const nameSchema = z.object({ name: z.string().trim().min(1, 'Вкажіть назву').max(200, 'Не довше за 200 символів') });

interface NamedDialogProps extends DialogProps<CatalogCycleCommission | CatalogBuilding> {
  entity: 'cycle-commissions' | 'buildings';
}

const NAMED_TEXT = {
  'cycle-commissions': {
    create: 'Нова циклова комісія',
    placeholder: 'Компʼютерних дисциплін',
    added: (name: string) => `Циклову комісію «${name}» додано`,
    saved: (name: string) => `Циклову комісію «${name}» збережено`,
    submit: 'Додати комісію',
  },
  buildings: {
    create: 'Новий корпус',
    placeholder: 'Головний корпус',
    added: (name: string) => `Корпус «${name}» додано`,
    saved: (name: string) => `Корпус «${name}» збережено`,
    submit: 'Додати корпус',
  },
} as const;

export function NamedFormDialog({ entity, item, open, onOpenChange }: NamedDialogProps) {
  const formId = useId();
  const save = useSaveCatalogItem(entity);
  const text = NAMED_TEXT[entity];
  const [formError, setFormError] = useState<string | null>(null);
  const form = useForm<z.infer<typeof nameSchema>>({ resolver: zodResolver(nameSchema), defaultValues: { name: item?.name ?? '' } });
  const { errors, isDirty } = form.formState;
  const submit = form.handleSubmit(async (values) => {
    setFormError(null);
    try {
      const saved = await save.mutateAsync({ id: item?.id, body: { name: values.name.trim(), version: item?.version } });
      notify.success(item ? text.saved(saved.name) : text.added(saved.name));
      onOpenChange(false);
    } catch (error) {
      setFormError(applyServerErrors(error, form.setError, ['name']));
    }
  });
  return (
    <FormDialog
      open={open}
      onOpenChange={onOpenChange}
      title={item ? item.name : text.create}
      formId={formId}
      submitLabel={item ? 'Зберегти' : text.submit}
      pending={save.isPending}
      dirty={isDirty}
      size="sm"
    >
      <form id={formId} noValidate onSubmit={(event) => void submit(event)} className="flex flex-col gap-4">
        <FormAlert message={formError} />
        <Field label="Назва" required error={errors.name?.message}>
          <Input {...form.register('name')} autoComplete="off" autoFocus placeholder={text.placeholder} />
        </Field>
      </form>
    </FormDialog>
  );
}
