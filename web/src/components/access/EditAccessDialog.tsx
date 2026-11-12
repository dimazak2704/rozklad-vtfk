import { zodResolver } from '@hookform/resolvers/zod';
import { useId, useState } from 'react';
import { Controller, useForm, useWatch } from 'react-hook-form';
import { z } from 'zod';
import { useUpdateAccess, type AccessDto, type AccessRole } from '@/api/access';
import { Checkbox, Combobox, Field, Input, notify } from '@/components/ui';
import { serverError, useTeacherChoices } from './access-form';
import { AccessDialog, AccessFormAlert } from './AccessFormParts';
import { ROLE_LABEL } from './labels';

const schema = z
  .object({
    admin: z.boolean(),
    teacher: z.boolean(),
    teacherId: z.string().nullable(),
    displayName: z.string().trim().min(1, 'Вкажіть ім’я').max(200, 'Не довше за 200 символів'),
  })
  .superRefine((values, ctx) => {
    if (!values.admin && !values.teacher) ctx.addIssue({ code: 'custom', path: ['teacher'], message: 'Виберіть хоча б одну роль' });
    if (values.teacher && !values.teacherId) ctx.addIssue({ code: 'custom', path: ['teacherId'], message: 'Виберіть викладача' });
  });

type Values = z.infer<typeof schema>;
const FIELDS = ['roles', 'teacherId', 'displayName'] as const;

interface EditAccessDialogProps {
  access: AccessDto;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

/** «Змінити доступ»: ролі, викладач з довідника (для ролі «Викладач») і ім'я, яке бачать у списку. */
export function EditAccessDialog({ access, open, onOpenChange }: EditAccessDialogProps) {
  const formId = useId();
  const update = useUpdateAccess();
  const teachers = useTeacherChoices(access.teacher);
  const [formError, setFormError] = useState<string | null>(null);
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: {
      admin: access.roles.includes('ADMIN'),
      teacher: access.roles.includes('TEACHER'),
      teacherId: access.teacher?.id ?? null,
      displayName: access.displayName,
    },
    mode: 'onTouched',
  });
  const { errors } = form.formState;
  const teacherRole = useWatch({ control: form.control, name: 'teacher' });

  const submit = form.handleSubmit(async (values) => {
    setFormError(null);
    const roles: AccessRole[] = [...(values.admin ? (['ADMIN'] as const) : []), ...(values.teacher ? (['TEACHER'] as const) : [])];
    try {
      await update.mutateAsync({
        id: access.id,
        body: {
          roles,
          displayName: values.displayName,
          teacherId: values.teacher && values.teacherId ? values.teacherId : undefined,
        },
      });
      notify.success(`Доступ змінено: ${values.displayName}`);
      onOpenChange(false);
    } catch (error) {
      setFormError(
        serverError(error, FIELDS, (field, message) =>
          form.setError(field === 'roles' ? 'teacher' : (field as keyof Values), { message }),
        ),
      );
    }
  });

  return (
    <AccessDialog open={open} onOpenChange={onOpenChange} title="Змінити доступ" formId={formId} submitLabel="Зберегти" pending={update.isPending}>
      <form id={formId} noValidate onSubmit={(event) => void submit(event)} className="flex flex-col gap-4">
        <fieldset className="flex flex-col gap-2">
          <legend className="mb-1 text-caption font-[650] text-primary">Ролі</legend>
          <Controller
            control={form.control}
            name="admin"
            render={({ field }) => <Checkbox label={ROLE_LABEL.ADMIN} checked={field.value} onCheckedChange={(checked) => field.onChange(checked === true)} />}
          />
          <Controller
            control={form.control}
            name="teacher"
            render={({ field }) => <Checkbox label={ROLE_LABEL.TEACHER} checked={field.value} onCheckedChange={(checked) => field.onChange(checked === true)} />}
          />
          {errors.teacher?.message ? (
            <p role="alert" className="text-caption text-danger">
              {errors.teacher.message}
            </p>
          ) : null}
        </fieldset>
        {teacherRole ? (
          <Field label="Викладач" required error={errors.teacherId?.message}>
            <Controller
              control={form.control}
              name="teacherId"
              render={({ field }) => (
                <Combobox
                  value={field.value}
                  onValueChange={field.onChange}
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
        <Field label="Ім’я" required error={errors.displayName?.message}>
          <Input {...form.register('displayName')} autoComplete="off" />
        </Field>
        <AccessFormAlert message={formError} />
      </form>
    </AccessDialog>
  );
}
