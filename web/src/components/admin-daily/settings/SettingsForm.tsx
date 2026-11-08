import { zodResolver } from '@hookform/resolvers/zod';
import { CircleExclamationPoint, Save } from 'lucide-react';
import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { Controller, useForm, useWatch } from 'react-hook-form';
import { useBlocker } from 'react-router';
import { isApiError } from '@/api/errors';
import { useBellProfiles } from '@/api/public';
import { useUpdateSettings } from '@/api/settings';
import type { BellProfileDto, SettingsDto } from '@/api/types';
import { Button, ConfirmDialog, Field, Input, notify, NumberInput, RadioGroup, Select, Switch } from '@/components/ui';
import { LIMITS, settingsSchema, type SettingsFormValues } from './schema';

const SERIF = '"Times New Roman", "Liberation Serif", Tinos, "Noto Serif", Times, serif';

/** «Пн–Чт: 8 пар з 08:30, пт: 6» — щоб профіль дзвінків було видно без розкриття. */
function profileSummary(profile: BellProfileDto): string {
  const monday = profile.slots.filter((s) => s.weekday === 1);
  const friday = profile.slots.filter((s) => s.weekday === 5);
  const first = monday[0]?.startsAt ?? friday[0]?.startsAt;
  if (!first) return 'Без дзвінків';
  return friday.length !== monday.length
    ? `Пн–Чт: ${monday.length} пар з ${first}, пт: ${friday.length}`
    : `Пн–Пт: ${monday.length} пар з ${first}`;
}

function SettingsSection({ title, description, children, id }: { title: string; description: ReactNode; children: ReactNode; id?: string }) {
  return (
    <section id={id} aria-labelledby={id ? `${id}-title` : undefined} className="grid gap-x-10 gap-y-4 border-t border-separator py-6 first:border-t-0 first:pt-0 md:grid-cols-[17rem_minmax(0,1fr)]">
      <div className="flex flex-col gap-1">
        <h2 id={id ? `${id}-title` : undefined} className="text-heading font-semibold text-primary">
          {title}
        </h2>
        <p className="text-ui text-secondary">{description}</p>
      </div>
      <div className="flex max-w-2xl min-w-0 flex-col gap-5">{children}</div>
    </section>
  );
}

/** Питає перед переходом зі сторінки, якщо є незбережені зміни (і перед закриттям вкладки). */
function UnsavedChangesGuard({ when }: { when: boolean }) {
  const blocker = useBlocker(({ currentLocation, nextLocation }) => when && currentLocation.pathname !== nextLocation.pathname);
  useEffect(() => {
    if (!when) return;
    const onBeforeUnload = (event: BeforeUnloadEvent) => event.preventDefault();
    window.addEventListener('beforeunload', onBeforeUnload);
    return () => window.removeEventListener('beforeunload', onBeforeUnload);
  }, [when]);
  return (
    <ConfirmDialog
      open={blocker.state === 'blocked'}
      onOpenChange={(open) => {
        if (!open && blocker.state === 'blocked') blocker.reset();
      }}
      title="Піти без збереження?"
      description="Зміни в налаштуваннях ще не збережено. Якщо піти зараз, вони пропадуть."
      confirmLabel="Піти без збереження"
      cancelLabel="Залишитися"
      tone="danger"
      onConfirm={() => blocker.proceed?.()}
    />
  );
}

interface SettingsFormProps {
  settings: SettingsDto;
  /** Питати перед переходом із незбереженими змінами (потребує data router; у тестах вимикається). */
  guardNavigation?: boolean;
  /** Довідка про нумерацію (лише читання) — у розділі «Розпорядження». */
  numbering: ReactNode;
  /** Моделі й витрати ШІ (лише читання) — у розділі «ШІ». */
  ai: ReactNode;
}

/**
 * Налаштування системи (CONTRACTS §2): погодження замін, підписант і нумерація розпоряджень, профіль
 * дзвінків і години на пару, видимість на сайті. Зберігаються однією дією; панель збереження видно,
 * лише коли є зміни.
 */
export function SettingsForm({ settings, guardNavigation = true, numbering, ai }: SettingsFormProps) {
  const profiles = useBellProfiles();
  const update = useUpdateSettings();
  const [editedVersion, setEditedVersion] = useState(settings.version);
  const form = useForm<SettingsFormValues>({ resolver: zodResolver(settingsSchema), defaultValues: settings, mode: 'onBlur' });
  const { control, handleSubmit, reset, setError, formState } = form;
  const [position, name, mode] = useWatch({ control, name: ['signatoryPosition', 'signatoryName', 'approvalMode'] });

  const profileOptions = useMemo(
    () => (profiles.data?.profiles ?? []).map((p) => ({ value: p.id, label: p.name, description: profileSummary(p) })),
    [profiles.data],
  );

  const submit = handleSubmit(async (values) => {
    try {
      // версія — та, з якої почали редагувати (а не свіжіша з фонового оновлення): якщо хтось зберіг
      // налаштування раніше, сервер відповість 409 і чужі зміни не перезапишуться мовчки
      const saved = await update.mutateAsync({
        ...values,
        activeBellProfileId: values.activeBellProfileId ?? null,
        version: editedVersion,
      });
      setEditedVersion(saved.version);
      reset(saved);
      notify.success('Налаштування збережено');
    } catch (error) {
      if (isApiError(error) && error.errors.length > 0) {
        for (const [field, message] of Object.entries(error.fieldErrors())) {
          if (field in values) setError(field as keyof SettingsFormValues, { message });
        }
      }
      notify.error(error, 'Не вдалося зберегти налаштування');
    }
  });

  return (
    <form noValidate onSubmit={(event) => void submit(event)} className="flex flex-col">
      {guardNavigation ? <UnsavedChangesGuard when={formState.isDirty && !formState.isSubmitting} /> : null}

      <SettingsSection
        title="Заміни"
        description="Як підтверджуються заміни і скільки чекати відповіді викладача, перш ніж нагадати диспетчеру."
      >
        <Controller
          control={control}
          name="approvalMode"
          render={({ field }) => (
            <RadioGroup<'AUTO' | 'CONFIRM'>
              legend="Режим погодження"
              value={field.value}
              onValueChange={field.onChange}
              options={[
                {
                  value: 'CONFIRM',
                  label: 'Із підтвердженням у Telegram',
                  description: 'Викладач отримує запит із кнопками «Підтверджую» / «Не можу». До розпорядження йдуть лише підтверджені.',
                },
                {
                  value: 'AUTO',
                  label: 'Автоматично',
                  description: 'Заміна підтверджується одразу після вибору диспетчера; викладач лише отримує повідомлення.',
                },
              ]}
            />
          )}
        />
        <Field
          label="Скільки чекати відповіді"
          hint={
            mode === 'AUTO'
              ? 'У режимі «Автоматично» не використовується.'
              : `Після цього строку диспетчер бачить нагадування на панелі й у чаті диспетчерів. ${LIMITS.confirmTimeoutMinutes.min}–${LIMITS.confirmTimeoutMinutes.max} хв.`
          }
          error={formState.errors.confirmTimeoutMinutes?.message}
          required
        >
          <Controller
            control={control}
            name="confirmTimeoutMinutes"
            render={({ field }) => (
              <NumberInput
                value={Number.isNaN(field.value) ? null : field.value}
                onValueChange={(value) => field.onChange(value ?? Number.NaN)}
                onBlur={field.onBlur}
                min={LIMITS.confirmTimeoutMinutes.min}
                max={LIMITS.confirmTimeoutMinutes.max}
                step={5}
                unit="хв"
                className="w-40"
                disabled={mode === 'AUTO'}
              />
            )}
          />
        </Field>
      </SettingsSection>

      <SettingsSection
        title="Розпорядження"
        description="Підпис унизу кожного розпорядження. Номер присвоюється під час публікації, наскрізно в межах семестру."
      >
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Посада підписанта" required error={formState.errors.signatoryPosition?.message}>
            <Controller control={control} name="signatoryPosition" render={({ field }) => <Input {...field} maxLength={LIMITS.signatoryPosition} />} />
          </Field>
          <Field label="Імʼя підписанта" hint="Як у документі: імʼя та ПРІЗВИЩЕ" required error={formState.errors.signatoryName?.message}>
            <Controller control={control} name="signatoryName" render={({ field }) => <Input {...field} maxLength={LIMITS.signatoryName} />} />
          </Field>
        </div>
        <figure className="flex flex-col gap-1.5">
          <figcaption className="text-caption text-muted">Так виглядатиме підпис</figcaption>
          <div data-theme="light" className="rounded-control border border-default bg-surface px-5 py-4 text-primary" style={{ fontFamily: SERIF }}>
            <p className="flex flex-wrap items-baseline justify-between gap-x-8 gap-y-1 text-body font-bold">
              <span>{position || 'Посада'}</span>
              <span>{name || 'Імʼя ПРІЗВИЩЕ'}</span>
            </p>
          </div>
        </figure>
        {numbering}
      </SettingsSection>

      <SettingsSection title="Розклад" description="Профіль дзвінків, що діє зараз, і скільки академічних годин зараховується за одну пару в обліку.">
        <Field
          label="Профіль дзвінків"
          hint="Зміна профілю оновить час пар на сайті, у боті й у розпорядженнях."
          error={formState.errors.activeBellProfileId?.message}
        >
          <Controller
            control={control}
            name="activeBellProfileId"
            render={({ field }) => (
              <Select
                value={field.value}
                onValueChange={field.onChange}
                options={profileOptions}
                placeholder={profiles.isPending ? 'Завантажуємо профілі…' : profiles.isError ? 'Профілі не завантажилися' : 'Оберіть профіль'}
                disabled={profiles.isPending || profiles.isError}
                className="max-w-sm"
              />
            )}
          />
        </Field>
        <Field
          label="Годин за пару"
          hint="Для обліку «план / факт»: пара 60 хвилин зараховується як 2 академічні години."
          error={formState.errors.hoursPerPair?.message}
          required
        >
          <Controller
            control={control}
            name="hoursPerPair"
            render={({ field }) => (
              <NumberInput
                value={Number.isNaN(field.value) ? null : field.value}
                onValueChange={(value) => field.onChange(value ?? Number.NaN)}
                onBlur={field.onBlur}
                min={LIMITS.hoursPerPair.min}
                max={LIMITS.hoursPerPair.max}
                unit="год"
                className="w-36"
              />
            )}
          />
        </Field>
      </SettingsSection>

      <SettingsSection title="Публічний сайт" description="Що бачать студенти й батьки без входу.">
        <Field label="Назва коледжу" hint="У шапці сайту, в боті й на сторінці «Про систему»." required error={formState.errors.collegeName?.message}>
          <Controller control={control} name="collegeName" render={({ field }) => <Input {...field} maxLength={LIMITS.collegeName.max} />} />
        </Field>
        <Controller
          control={control}
          name="showTeacherFullNames"
          render={({ field }) => (
            <Switch
              checked={field.value}
              onCheckedChange={field.onChange}
              label="Показувати повні імена викладачів"
              description="Вимкнено — на сайті лише прізвище та ініціали («Кренцін М.Д.»), як у друкованому розкладі."
            />
          )}
        />
      </SettingsSection>

      <SettingsSection id="ai" title="ШІ" description="Розбір відсутностей з повідомлень у боті й розпізнавання документів під час імпорту.">
        {ai}
      </SettingsSection>

      {formState.isDirty ? (
        <div className="sticky bottom-0 z-20 -mx-3 mt-2 flex flex-wrap items-center justify-end gap-2 border-t border-default bg-surface px-3 py-3 sm:-mx-5 sm:px-5 lg:-mx-8 lg:px-8">
          <p className="me-auto flex items-center gap-2 text-ui text-secondary">
            <CircleExclamationPoint aria-hidden="true" className="size-4 text-change" />
            Є незбережені зміни
          </p>
          <Button variant="secondary" onClick={() => reset()} disabled={formState.isSubmitting}>
            Скасувати зміни
          </Button>
          <Button type="submit" variant="primary" loading={formState.isSubmitting} iconStart={<Save aria-hidden="true" className="size-4" />}>
            Зберегти
          </Button>
        </div>
      ) : null}
    </form>
  );
}
