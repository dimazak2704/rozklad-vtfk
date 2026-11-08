import { zodResolver } from '@hookform/resolvers/zod';
import { useId, useState } from 'react';
import { Controller, useForm, useWatch } from 'react-hook-form';
import { Link } from 'react-router';
import { z } from 'zod';
import { useCatalogOptions, useSaveCatalogItem, type CatalogGroup } from '@/api/catalog';
import { Checkbox, Combobox, Field, Input, NumberInput, Plate, RadioGroup, notify } from '@/components/ui';
import { canonicalGroupCode } from '@/lib/search';
import { applyServerErrors } from './forms';
import { FormAlert, FormDialog } from './FormParts';
import { SubgroupEditor } from './SubgroupEditor';

const groupSchema = z.object({
  code: z
    .string()
    .trim()
    .min(1, 'Вкажіть код групи, наприклад 4КІ1')
    .refine((value) => canonicalGroupCode(value) !== null, 'Код групи має вигляд «4КІ1»: курс, літери спеціальності, номер.'),
  shift: z.enum(['1', '2']),
  size: z.number().int('Ціле число').min(0, 'Не менше 0').max(200, 'Не більше 200').nullable(),
  curatorTeacherId: z.string().nullable(),
  homeRoomId: z.string().nullable(),
  active: z.boolean(),
});

type GroupValues = z.infer<typeof groupSchema>;

const FIELDS = ['code', 'shift', 'size', 'curatorTeacherId', 'homeRoomId', 'active'] as const;

const SHIFT_OPTIONS = [
  { value: '1' as const, label: '1 зміна', description: 'Пари 1–4' },
  { value: '2' as const, label: '2 зміна', description: 'Пари 5–8 у пн–чт, 4–6 у пʼятницю' },
];

/** У реальному розкладі 2 курс навчається в другу зміну, інші — у першу (DOMAIN §2). */
const defaultShift = (code: string | null): '1' | '2' => (code?.charAt(0) === '2' ? '2' : '1');

interface GroupFormDialogProps {
  group: CatalogGroup | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

/**
 * Група: код «4КІ1» сам каже курс, спеціальність і номер (латиницю «4ki1» виправляємо), зміна,
 * куратор, домашня аудиторія, підгрупи.
 */
export function GroupFormDialog({ group, open, onOpenChange }: GroupFormDialogProps) {
  const formId = useId();
  const save = useSaveCatalogItem('groups');
  const specialties = useCatalogOptions('specialties');
  const teachers = useCatalogOptions('teachers', { active: true });
  const rooms = useCatalogOptions('rooms', { active: true });
  const [formError, setFormError] = useState<string | null>(null);
  const form = useForm<GroupValues>({
    resolver: zodResolver(groupSchema),
    defaultValues: {
      code: group?.code ?? '',
      shift: group ? (String(group.shift) as '1' | '2') : '1',
      size: group?.size ?? null,
      curatorTeacherId: group?.curator?.id ?? null,
      homeRoomId: group?.homeRoom?.id ?? null,
      active: group?.active ?? true,
    },
  });
  const { errors, isDirty } = form.formState;
  const code = useWatch({ control: form.control, name: 'code' });
  const canonical = canonicalGroupCode(code);
  const letters = canonical ? /^\d([А-ЯІЇЄҐ]{1,3})/u.exec(canonical)?.[1] : undefined;
  const specialty = letters ? specialties.items?.find((s) => s.letters === letters) : undefined;

  const submit = form.handleSubmit(async (values) => {
    setFormError(null);
    try {
      const saved = await save.mutateAsync({
        id: group?.id,
        body: {
          code: canonicalGroupCode(values.code) ?? values.code.trim(),
          shift: Number(values.shift) as 1 | 2,
          size: values.size,
          curatorTeacherId: values.curatorTeacherId,
          homeRoomId: values.homeRoomId,
          active: values.active,
          version: group?.version,
        },
      });
      notify.success(group ? `Групу ${saved.code} збережено` : `Групу ${saved.code} додано`);
      onOpenChange(false);
    } catch (error) {
      setFormError(applyServerErrors(error, form.setError, FIELDS, { course: 'code', number: 'code', specialtyId: 'code' }));
    }
  });

  const teacherOptions = (teachers.items ?? []).map((t) => ({ value: t.id, label: t.shortName, keywords: [t.displayName, t.lastName] }));
  const roomOptions = (rooms.items ?? []).map((r) => ({ value: r.id, label: r.name }));

  return (
    <FormDialog
      open={open}
      onOpenChange={onOpenChange}
      title={group ? `Група ${group.code}` : 'Нова група'}
      description={group ? 'Код змінює курс і спеціальність групи — перевірте розклад після зміни.' : 'Курс, спеціальність і номер беремо з коду.'}
      formId={formId}
      submitLabel={group ? 'Зберегти' : 'Додати групу'}
      pending={save.isPending}
      dirty={isDirty}
      size="lg"
    >
      <form id={formId} noValidate onSubmit={(event) => void submit(event)} className="flex flex-col gap-4">
        <FormAlert message={formError} />
        <div className="grid gap-4 sm:grid-cols-[12rem_minmax(0,1fr)]">
          <Field label="Код групи" required error={errors.code?.message}>
            <Input
              {...form.register('code', {
                onChange: (event: { target: { value: string } }) => {
                  if (!form.getFieldState('shift').isDirty) {
                    form.setValue('shift', defaultShift(canonicalGroupCode(event.target.value)));
                  }
                },
              })}
              autoComplete="off"
              spellCheck={false}
              placeholder="4КІ1"
              autoFocus={!group}
              className="font-condensed text-heading font-bold"
            />
          </Field>
          <div className="flex min-h-full flex-col justify-end gap-1 pb-1" aria-live="polite">
            {canonical ? (
              <>
                <p className="flex flex-wrap items-center gap-2 text-ui text-secondary">
                  <Plate size="md" tone="accent">
                    {canonical}
                  </Plate>
                  <span>
                    {canonical.charAt(0)} курс · {letters}
                    {specialty ? ` — ${specialty.name}` : ''}
                    {/\d$/.test(canonical) ? ` · група ${canonical.slice(-1)}` : ''}
                  </span>
                </p>
                {specialties.items && !specialty ? (
                  <p className="text-caption font-medium text-change">
                    Спеціальності «{letters}» немає в довіднику —{' '}
                    <Link to="/admin/catalog/structure?tab=specialties" className="underline underline-offset-2">
                      додайте її
                    </Link>
                    , інакше групу не зберегти.
                  </p>
                ) : null}
                {canonical !== code.trim() ? (
                  <p className="text-caption text-muted">Збережемо як «{canonical}» (латиницю й пробіли виправлено).</p>
                ) : null}
              </>
            ) : (
              <p className="text-caption text-muted">Цифра курсу, 1–3 літери спеціальності, номер групи.</p>
            )}
          </div>
        </div>
        <Controller
          control={form.control}
          name="shift"
          render={({ field }) => (
            <RadioGroup legend="Зміна" orientation="horizontal" value={field.value} onValueChange={field.onChange} options={SHIFT_OPTIONS} />
          )}
        />
        <div className="grid gap-4 sm:grid-cols-3">
          <Field label="Куратор" error={errors.curatorTeacherId?.message}>
            <Controller
              control={form.control}
              name="curatorTeacherId"
              render={({ field }) => (
                <Combobox
                  value={field.value}
                  onValueChange={field.onChange}
                  options={teacherOptions}
                  placeholder="Оберіть викладача"
                  searchPlaceholder="Прізвище"
                  loading={teachers.isPending}
                  clearable
                />
              )}
            />
          </Field>
          <Field label="Домашня аудиторія" error={errors.homeRoomId?.message} hint="Генератор намагається ставити пари сюди">
            <Controller
              control={form.control}
              name="homeRoomId"
              render={({ field }) => (
                <Combobox
                  value={field.value}
                  onValueChange={field.onChange}
                  options={roomOptions}
                  placeholder="Оберіть аудиторію"
                  searchPlaceholder="Номер"
                  loading={rooms.isPending}
                  clearable
                />
              )}
            />
          </Field>
          <Field label="Студентів" error={errors.size?.message} hint="Для перевірки місткості аудиторій">
            <Controller
              control={form.control}
              name="size"
              render={({ field }) => <NumberInput value={field.value} onValueChange={field.onChange} min={0} max={200} />}
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
              label="Група навчається"
              description="Неактивну групу ховають з пошуку й не беруть у генерацію розкладу."
            />
          )}
        />
      </form>
      {group ? (
        <div className="mt-4">
          <SubgroupEditor groupId={group.id} groupCode={group.code} />
        </div>
      ) : null}
    </FormDialog>
  );
}
