import { zodResolver } from '@hookform/resolvers/zod';
import { Controller, useForm } from 'react-hook-form';
import { z } from 'zod';
import { isApiError } from '@/api/errors';
import { useCreateVersion } from '@/api/timetable';
import type { TimetableVersionDto, Uuid } from '@/api/types';
import { Button, Dialog, DialogClose, DialogContent, Field, Input, Select, notify } from '@/components/ui';
import { VERSION_STATUS_LABEL } from '../model/labels';

const schema = z.object({
  name: z.string().trim().min(1, 'Вкажіть назву версії').max(120, 'Назва — до 120 символів'),
  copyFrom: z.string(),
});

type Values = z.infer<typeof schema>;

const EMPTY_SOURCE = 'empty';

interface CreateVersionDialogProps {
  /** null — закрито; інакше — з якої версії копіювати за замовчуванням (або порожня). */
  request: { copyFromId: Uuid | null } | null;
  onOpenChange: (open: boolean) => void;
  semesterId: Uuid;
  versions: readonly TimetableVersionDto[];
  onCreated: (version: TimetableVersionDto) => void;
}

/** Нова чернетка: порожня або копія наявної версії (зазвичай опублікованої) — правки не чіпають оригінал. */
export function CreateVersionDialog({ request, onOpenChange, semesterId, versions, onCreated }: CreateVersionDialogProps) {
  return (
    <Dialog open={request !== null} onOpenChange={onOpenChange}>
      {request ? (
        <CreateVersionForm
          key={request.copyFromId ?? EMPTY_SOURCE}
          copyFromId={request.copyFromId}
          semesterId={semesterId}
          versions={versions}
          onCreated={(version) => {
            onCreated(version);
            onOpenChange(false);
          }}
        />
      ) : null}
    </Dialog>
  );
}

function CreateVersionForm({
  copyFromId,
  semesterId,
  versions,
  onCreated,
}: { copyFromId: Uuid | null } & Pick<CreateVersionDialogProps, 'semesterId' | 'versions' | 'onCreated'>) {
  const create = useCreateVersion();
  const source = versions.find((v) => v.id === copyFromId);
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: { name: source ? `Копія: ${source.name}` : '', copyFrom: source?.id ?? EMPTY_SOURCE },
  });

  const submit = form.handleSubmit(async (values) => {
    try {
      const created = await create.mutateAsync({
        semesterId,
        name: values.name.trim(),
        copyFromVersionId: values.copyFrom === EMPTY_SOURCE ? null : values.copyFrom,
      });
      notify.success(`Чернетку «${created.name}» створено`, {
        description: created.lessonCount > 0 ? `Скопійовано пар: ${created.lessonCount}.` : 'Версія порожня — додайте пари в сітці.',
      });
      onCreated(created);
    } catch (error) {
      if (isApiError(error) && error.errors.length > 0) {
        const fields = error.fieldErrors();
        if (fields.name) form.setError('name', { message: fields.name });
        if (fields.copyFromVersionId) form.setError('copyFrom', { message: fields.copyFromVersionId });
      } else {
        notify.error(error, 'Версію не створено');
      }
    }
  });

  return (
    <DialogContent
      title="Нова версія розкладу"
      description="Чернетку можна правити, порівнювати й публікувати; опублікована версія лишається незмінною, доки не замінити її новою."
      footer={
        <>
          <DialogClose asChild>
            <Button variant="ghost">Скасувати</Button>
          </DialogClose>
          <Button type="submit" form="create-version-form" variant="primary" loading={form.formState.isSubmitting}>
            Створити чернетку
          </Button>
        </>
      }
    >
      <form id="create-version-form" noValidate onSubmit={(event) => void submit(event)} className="flex flex-col gap-4">
        <Field label="Назва" required error={form.formState.errors.name?.message} hint="Наприклад: «Чернетка після практики 2КІ3»">
          <Input {...form.register('name')} autoComplete="off" autoFocus />
        </Field>
        <Field label="Основа" error={form.formState.errors.copyFrom?.message}>
          <Controller
            control={form.control}
            name="copyFrom"
            render={({ field }) => (
              <Select
                value={field.value}
                onValueChange={field.onChange}
                options={[
                  { value: EMPTY_SOURCE, label: 'Порожня версія', description: 'Без жодної пари — для генератора чи ручного складання' },
                  ...versions.map((v) => ({
                    value: v.id,
                    label: `Копія: ${v.name}`,
                    description: `${VERSION_STATUS_LABEL[v.status]} · ${v.lessonCount} пар`,
                  })),
                ]}
              />
            )}
          />
        </Field>
      </form>
    </DialogContent>
  );
}
