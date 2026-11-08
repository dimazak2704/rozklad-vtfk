import { zodResolver } from '@hookform/resolvers/zod';
import { useId, useState } from 'react';
import { Controller, useForm, useWatch } from 'react-hook-form';
import { z } from 'zod';
import { useSaveCatalogItem, type CatalogDiscipline } from '@/api/catalog';
import { Checkbox, Field, Input, notify } from '@/components/ui';
import { AliasEditor } from './AliasEditor';
import { DisciplineColorField } from './DisciplineColorField';
import { HEX_COLOR_RE } from './discipline-palette';
import { applyServerErrors } from './forms';
import { FormAlert, FormDialog } from './FormParts';

const disciplineSchema = z.object({
  shortName: z.string().trim().min(1, 'Вкажіть скорочення, як у розкладі: «СТП»').max(64, 'Не довше за 64 символи'),
  name: z.string().trim().min(1, 'Вкажіть повну назву дисципліни').max(300, 'Не довше за 300 символів'),
  color: z.string().regex(HEX_COLOR_RE, 'Колір має вигляд #RRGGBB').nullable(),
  needsReview: z.boolean(),
  active: z.boolean(),
});

type DisciplineValues = z.infer<typeof disciplineSchema>;

const FIELDS = ['shortName', 'name', 'color', 'needsReview', 'active'] as const;

interface DisciplineFormDialogProps {
  discipline: CatalogDiscipline | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

/** Дисципліна: скорочення з розкладу, повна назва, колір лінії з попереднім переглядом, псевдоніми. */
export function DisciplineFormDialog({ discipline, open, onOpenChange }: DisciplineFormDialogProps) {
  const formId = useId();
  const save = useSaveCatalogItem('disciplines');
  const [formError, setFormError] = useState<string | null>(null);
  const form = useForm<DisciplineValues>({
    resolver: zodResolver(disciplineSchema),
    defaultValues: {
      shortName: discipline?.shortName ?? '',
      name: discipline?.name ?? '',
      color: discipline?.color ?? null,
      needsReview: discipline?.needsReview ?? false,
      active: discipline?.active ?? true,
    },
  });
  const { errors, isDirty } = form.formState;
  const [shortName, name] = useWatch({ control: form.control, name: ['shortName', 'name'] });

  const submit = form.handleSubmit(async (values) => {
    setFormError(null);
    try {
      const saved = await save.mutateAsync({
        id: discipline?.id,
        body: { ...values, shortName: values.shortName.trim(), name: values.name.trim(), version: discipline?.version },
      });
      notify.success(discipline ? `Дисципліну ${saved.shortName} збережено` : `Дисципліну ${saved.shortName} додано`);
      onOpenChange(false);
    } catch (error) {
      setFormError(applyServerErrors(error, form.setError, FIELDS));
    }
  });

  return (
    <FormDialog
      open={open}
      onOpenChange={onOpenChange}
      title={discipline ? discipline.shortName : 'Нова дисципліна'}
      description={discipline ? discipline.name : 'Скорочення — як у розкладі й розпорядженнях, повна назва — як у навчальному плані.'}
      formId={formId}
      submitLabel={discipline ? 'Зберегти' : 'Додати дисципліну'}
      pending={save.isPending}
      dirty={isDirty}
      size="xl"
    >
      <form id={formId} noValidate onSubmit={(event) => void submit(event)} className="flex flex-col gap-4">
        <FormAlert message={formError} />
        <div className="grid gap-4 sm:grid-cols-[12rem_minmax(0,1fr)]">
          <Field label="Скорочення" required error={errors.shortName?.message} hint="Так друкують у розкладі">
            <Input {...form.register('shortName')} autoComplete="off" spellCheck={false} autoFocus={!discipline} />
          </Field>
          <Field label="Повна назва" required error={errors.name?.message}>
            <Input {...form.register('name')} autoComplete="off" />
          </Field>
        </div>
        <Controller
          control={form.control}
          name="color"
          render={({ field }) => (
            <DisciplineColorField
              value={field.value}
              onChange={(value) => field.onChange(value)}
              disciplineId={discipline?.id ?? null}
              shortName={shortName}
              name={name}
              error={errors.color?.message}
            />
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
                label="Назву треба перевірити"
                description="Імпорт ставить цю позначку, якщо розшифровка скорочення невідома."
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
                label="Дисципліна викладається"
                description="Неактивну не пропонують у навантаженні й замінах."
              />
            )}
          />
        </div>
      </form>
      {discipline ? (
        <div className="mt-4">
          <AliasEditor owner="disciplines" ownerId={discipline.id} ownerLabel={discipline.shortName} example="Укр.мова." />
        </div>
      ) : null}
    </FormDialog>
  );
}
