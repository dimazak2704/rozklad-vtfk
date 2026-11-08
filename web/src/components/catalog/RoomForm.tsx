import { zodResolver } from '@hookform/resolvers/zod';
import { useId, useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { Link } from 'react-router';
import { z } from 'zod';
import { useCatalogOptions, useSaveCatalogItem, type CatalogRoom } from '@/api/catalog';
import { Checkbox, Combobox, Field, Input, NumberInput, Select, notify } from '@/components/ui';
import { applyServerErrors } from './forms';
import { FormAlert, FormDialog } from './FormParts';
import { ROOM_TYPE_OPTIONS } from './labels';

const roomSchema = z.object({
  name: z.string().trim().min(1, 'Вкажіть назву або номер аудиторії').max(50, 'Не довше за 50 символів'),
  type: z.enum(['CLASSROOM', 'COMPUTER_LAB', 'LAB', 'GYM', 'SPORTS_GROUND', 'OTHER'], { error: 'Оберіть тип приміщення' }),
  buildingId: z.string().nullable(),
  capacity: z.number({ error: 'Місткість — ціле число' }).int('Місткість — ціле число').min(0, 'Не менше 0').max(1000, 'Не більше 1000').nullable(),
  maxParallel: z
    .number({ error: 'Вкажіть, скільки груп може бути одночасно' })
    .int('Ціле число')
    .min(1, 'Хоча б 1 група')
    .max(20, 'Не більше 20 груп'),
  active: z.boolean(),
});

type RoomValues = z.infer<typeof roomSchema>;

const FIELDS = ['name', 'type', 'buildingId', 'capacity', 'maxParallel', 'active'] as const;

interface RoomFormDialogProps {
  /** null — нова аудиторія. */
  room: CatalogRoom | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

/** Створення й редагування аудиторії: назва, тип, корпус, місткість, скільки груп одночасно. */
export function RoomFormDialog({ room, open, onOpenChange }: RoomFormDialogProps) {
  const formId = useId();
  const save = useSaveCatalogItem('rooms');
  const buildings = useCatalogOptions('buildings');
  const [formError, setFormError] = useState<string | null>(null);
  const form = useForm<RoomValues>({
    resolver: zodResolver(roomSchema),
    defaultValues: {
      name: room?.name ?? '',
      type: room?.type ?? 'CLASSROOM',
      buildingId: room?.building?.id ?? null,
      capacity: room?.capacity ?? null,
      maxParallel: room?.maxParallel ?? 1,
      active: room?.active ?? true,
    },
  });
  const { errors, isDirty } = form.formState;

  const submit = form.handleSubmit(async (values) => {
    setFormError(null);
    try {
      const saved = await save.mutateAsync({
        id: room?.id,
        body: {
          name: values.name.trim(),
          type: values.type,
          buildingId: values.buildingId,
          capacity: values.capacity,
          maxParallel: values.maxParallel,
          active: values.active,
          version: room?.version,
        },
      });
      notify.success(room ? `Аудиторію ${saved.name} збережено` : `Аудиторію ${saved.name} додано`);
      onOpenChange(false);
    } catch (error) {
      setFormError(applyServerErrors(error, form.setError, FIELDS));
    }
  });

  const buildingOptions = (buildings.items ?? []).map((b) => ({ value: b.id, label: b.name }));

  return (
    <FormDialog
      open={open}
      onOpenChange={onOpenChange}
      title={room ? `Аудиторія ${room.name}` : 'Нова аудиторія'}
      description={room ? 'Зміни одразу побачать редактор розкладу й генератор.' : 'Номер чи назва так, як їх пишуть у розкладі: «301», «с.з.».'}
      formId={formId}
      submitLabel={room ? 'Зберегти' : 'Додати аудиторію'}
      pending={save.isPending}
      dirty={isDirty}
    >
      <form id={formId} noValidate onSubmit={(event) => void submit(event)} className="flex flex-col gap-4">
        <FormAlert message={formError} />
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Назва" required error={errors.name?.message} hint="Як у розкладі: «301», «С.З.» = «с.з.»">
            <Input {...form.register('name')} autoComplete="off" autoFocus={!room} />
          </Field>
          <Field label="Тип" required error={errors.type?.message}>
            <Controller
              control={form.control}
              name="type"
              render={({ field }) => <Select value={field.value} onValueChange={field.onChange} options={ROOM_TYPE_OPTIONS} />}
            />
          </Field>
          <Field
            label="Місткість"
            error={errors.capacity?.message}
            hint="Порожньо — невідомо, тоді місткість не перевіряють"
          >
            <Controller
              control={form.control}
              name="capacity"
              render={({ field }) => (
                <NumberInput value={field.value} onValueChange={field.onChange} min={0} max={1000} unit="місць" />
              )}
            />
          </Field>
          <Field label="Груп одночасно" required error={errors.maxParallel?.message} hint="Спортзал «с.з.» приймає 3 групи">
            <Controller
              control={form.control}
              name="maxParallel"
              render={({ field }) => (
                <NumberInput value={field.value} onValueChange={(value) => field.onChange(value ?? 1)} min={1} max={20} />
              )}
            />
          </Field>
          <Field
            label="Корпус"
            error={errors.buildingId?.message}
            className="sm:col-span-2"
            hint={
              buildings.items && buildingOptions.length === 0 ? (
                <>
                  Корпусів ще немає — додайте їх у розділі{' '}
                  <Link to="/admin/catalog/structure?tab=buildings" className="font-semibold text-accent underline-offset-2 hover:underline">
                    «Структура коледжу»
                  </Link>
                  .
                </>
              ) : undefined
            }
          >
            <Controller
              control={form.control}
              name="buildingId"
              render={({ field }) => (
                <Combobox
                  value={field.value}
                  onValueChange={field.onChange}
                  options={buildingOptions}
                  placeholder={buildingOptions.length ? 'Оберіть корпус' : 'Корпус не вказано'}
                  searchPlaceholder="Назва корпусу"
                  loading={buildings.isPending}
                  disabled={buildingOptions.length === 0}
                  clearable
                />
              )}
            />
          </Field>
        </div>
        <Controller
          control={form.control}
          name="active"
          render={({ field }) => (
            <Checkbox
              checked={field.value}
              onCheckedChange={(checked) => field.onChange(checked === true)}
              label="Аудиторія діє"
              description="Неактивну не пропонують у розкладі й замінах, але старі заняття з нею лишаються."
            />
          )}
        />
      </form>
    </FormDialog>
  );
}
