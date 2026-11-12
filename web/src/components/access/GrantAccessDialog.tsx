import { zodResolver } from '@hookform/resolvers/zod';
import { useId, useState } from 'react';
import { Controller, useForm, useWatch } from 'react-hook-form';
import { z } from 'zod';
import { useGrantAccess, type AccessDto, type AccessRole } from '@/api/access';
import { Combobox, Field, Input, RadioGroup, notify } from '@/components/ui';
import { serverError, useTeacherChoices } from './access-form';
import { AccessDialog, AccessFormAlert } from './AccessFormParts';
import { ROLE_LABEL } from './labels';

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

const schema = z
  .object({
    role: z.enum(['TEACHER', 'ADMIN']),
    teacherId: z.string().nullable(),
    email: z.string().trim().min(1, 'Вкажіть e-mail').max(254, 'Не довше за 254 символи').regex(EMAIL_RE, 'Перевірте адресу e-mail'),
    displayName: z.string().trim().max(200, 'Не довше за 200 символів'),
  })
  .superRefine((values, ctx) => {
    if (values.role === 'TEACHER' && !values.teacherId) {
      ctx.addIssue({ code: 'custom', path: ['teacherId'], message: 'Виберіть викладача' });
    }
    if (values.role === 'ADMIN' && !values.displayName) {
      ctx.addIssue({ code: 'custom', path: ['displayName'], message: 'Вкажіть ім’я' });
    }
  });

type Values = z.infer<typeof schema>;
const FIELDS = ['email', 'teacherId', 'displayName', 'roles'] as const;

const ROLE_OPTIONS: ReadonlyArray<{ value: AccessRole; label: string }> = [
  { value: 'TEACHER', label: ROLE_LABEL.TEACHER },
  { value: 'ADMIN', label: ROLE_LABEL.ADMIN },
];

interface GrantAccessDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onGranted?: (access: AccessDto) => void;
}

/**
 * «Надати доступ»: адреса e-mail і роль. Викладачеві потрібен викладач з довідника (так його заміни й розклад
 * стануть «моїми»). Пароля немає: людина входить через Google або за посиланням для пароля.
 */
export function GrantAccessDialog({ open, onOpenChange, onGranted }: GrantAccessDialogProps) {
  const formId = useId();
  const grant = useGrantAccess();
  const teachers = useTeacherChoices();
  const [formError, setFormError] = useState<string | null>(null);
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: { role: 'TEACHER', teacherId: null, email: '', displayName: '' },
    mode: 'onTouched',
  });
  const { errors } = form.formState;
  const role = useWatch({ control: form.control, name: 'role' });

  const submit = form.handleSubmit(async (values) => {
    setFormError(null);
    try {
      const granted = await grant.mutateAsync({
        email: values.email,
        roles: [values.role],
        displayName: values.displayName || undefined,
        teacherId: values.role === 'TEACHER' && values.teacherId ? values.teacherId : undefined,
      });
      notify.success(`Доступ надано: ${granted.displayName}`);
      onGranted?.(granted);
      onOpenChange(false);
    } catch (error) {
      setFormError(
        serverError(error, FIELDS, (field, message) =>
          form.setError(field === 'roles' ? 'role' : (field as keyof Values), { message }),
        ),
      );
    }
  });

  return (
    <AccessDialog open={open} onOpenChange={onOpenChange} title="Надати доступ" formId={formId} submitLabel="Надати доступ" pending={grant.isPending}>
      <form id={formId} noValidate onSubmit={(event) => void submit(event)} className="flex flex-col gap-4">
        <Controller
          control={form.control}
          name="role"
          render={({ field }) => (
            <RadioGroup<AccessRole> legend="Роль" orientation="horizontal" value={field.value} onValueChange={field.onChange} options={ROLE_OPTIONS} />
          )}
        />
        {role === 'TEACHER' ? (
          <Field label="Викладач" required error={errors.teacherId?.message}>
            <Controller
              control={form.control}
              name="teacherId"
              render={({ field }) => (
                <Combobox
                  value={field.value}
                  onValueChange={(value) => {
                    field.onChange(value);
                    // адреса з довідника, якщо вона там є, а поле ще порожнє
                    const chosen = teachers.options.find((option) => option.value === value);
                    if (chosen?.email && !form.getValues('email')) form.setValue('email', chosen.email, { shouldValidate: true });
                  }}
                  options={teachers.options}
                  loading={teachers.loading}
                  placeholder="Почніть вводити прізвище"
                  searchPlaceholder="Прізвище"
                  emptyText="Немає викладачів без доступу"
                />
              )}
            />
          </Field>
        ) : null}
        <Field label="E-mail" required error={errors.email?.message}>
          <Input {...form.register('email')} type="email" autoComplete="off" spellCheck={false} placeholder="name@vtc.vn.ua" />
        </Field>
        {role === 'ADMIN' ? (
          <Field label="Ім’я" required error={errors.displayName?.message}>
            <Input {...form.register('displayName')} autoComplete="off" />
          </Field>
        ) : null}
        <AccessFormAlert message={formError} />
      </form>
    </AccessDialog>
  );
}
