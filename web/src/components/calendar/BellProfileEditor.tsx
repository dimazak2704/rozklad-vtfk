import { Minus, Plus, Trash } from 'lucide-react';
import { useId, useState } from 'react';
import { useDeleteBellProfile, useSaveBellProfile, type CalendarBellProfile } from '@/api/calendar';
import type { Weekday } from '@/api/types';
import { applyServerErrors } from '@/components/catalog/forms';
import { FormAlert } from '@/components/catalog/FormParts';
import { DeleteDialog } from '@/components/catalog/DeleteDialog';
import { Badge, Button, Checkbox, Field, IconButton, Input, Switch, notify } from '@/components/ui';
import { cn } from '@/lib/cn';
import { capitalize, countLabel, PAIR_FORMS, weekdayName, weekdayShort } from '@/lib/labels';
import { breakAfter, duration, gridFromSlots, hasErrors, nextSlot, slotsFromGrid, validateDay, type BellGrid, type SlotDraft } from './bells';

interface BellProfileEditorProps {
  /** null — новий профіль (копія `template`). */
  profile: CalendarBellProfile | null;
  template: CalendarBellProfile | null;
  onSaved: (profile: CalendarBellProfile) => void;
  onDeleted: () => void;
}

interface Column {
  key: string;
  title: string;
  days: Weekday[];
}

/**
 * Редактор профілю дзвінків: колонки днів (пн–чт можна редагувати разом), пари з часом, тривалість і
 * перерви між ними. Помилки показуємо біля пари одразу, а не після «Зберегти».
 */
export function BellProfileEditor({ profile, template, onSaved, onDeleted }: BellProfileEditorProps) {
  const nameId = useId();
  const save = useSaveBellProfile();
  const remove = useDeleteBellProfile();
  const source = profile ?? template;
  const [name, setName] = useState(profile?.name ?? (template ? `${template.name} (копія)` : 'Новий профіль'));
  const [active, setActive] = useState(profile?.active ?? true);
  const [grid, setGrid] = useState<BellGrid>(() => gridFromSlots(source?.slots ?? []));
  const [dirty, setDirty] = useState(profile === null);
  const [formError, setFormError] = useState<string | null>(null);
  const [nameError, setNameError] = useState<string | null>(null);
  const [deleting, setDeleting] = useState(false);

  const columns: Column[] = grid.sameMonThu
    ? [
        { key: 'mon-thu', title: 'Понеділок – четвер', days: [1, 2, 3, 4] },
        { key: 'fri', title: 'Пʼятниця', days: [5] },
      ]
    : ([1, 2, 3, 4, 5] as const).map((day) => ({ key: String(day), title: capitalize(weekdayName(day)), days: [day] }));

  const change = (recipe: (draft: BellGrid) => BellGrid) => {
    setGrid((current) => recipe(current));
    setDirty(true);
    setFormError(null);
  };

  const updateDay = (day: Weekday, slots: SlotDraft[]) => change((current) => ({ ...current, days: { ...current.days, [day]: slots } }));

  const submit = async () => {
    if (!name.trim()) {
      setNameError('Назвіть профіль: «2 зміни, 60 хв»');
      return;
    }
    if (hasErrors(grid)) {
      setFormError('Виправте час пар, позначений червоним.');
      return;
    }
    setNameError(null);
    setFormError(null);
    try {
      const saved = await save.mutateAsync({
        id: profile?.id,
        body: { name: name.trim(), active, slots: slotsFromGrid(grid), version: profile?.version },
      });
      notify.success(profile ? `Дзвінки «${saved.name}» збережено` : `Профіль «${saved.name}» створено`);
      setDirty(false);
      onSaved(saved);
    } catch (error) {
      const message = applyServerErrors<{ name: string }>(
        error,
        (field, value) => {
          if (field === 'name') setNameError(value.message ?? null);
        },
        ['name'],
      );
      setFormError(message);
    }
  };

  return (
    <div className="flex min-w-0 flex-col gap-4">
      <FormAlert message={formError} />
      <div className="flex flex-wrap items-end gap-x-6 gap-y-3">
        <Field label="Назва профілю" required error={nameError} id={nameId} className="w-full sm:w-72">
          <Input
            value={name}
            onChange={(event) => {
              setName(event.target.value);
              setDirty(true);
              setNameError(null);
            }}
            autoComplete="off"
          />
        </Field>
        <Switch
          checked={grid.sameMonThu}
          onCheckedChange={(checked) =>
            change((current) =>
              checked
                ? { ...current, sameMonThu: true }
                : {
                    sameMonThu: false,
                    days: { ...current.days, 2: [...current.days[1]], 3: [...current.days[1]], 4: [...current.days[1]] },
                  },
            )
          }
          label="Пн–чт однакові"
          description="Редагувати чотири дні однією колонкою"
          className="pb-1"
        />
        <Checkbox
          checked={active}
          onCheckedChange={(checked) => {
            setActive(checked === true);
            setDirty(true);
          }}
          label="Профіль доступний"
          description={profile?.defaultProfile ? 'Типовий профіль вимкнути не можна' : 'Його можна вибрати для семестру'}
          disabled={profile?.defaultProfile}
          className="pb-1"
        />
      </div>

      <div className={cn('grid gap-3', grid.sameMonThu ? 'md:grid-cols-2' : 'md:grid-cols-3 xl:grid-cols-5')}>
        {columns.map((column) => {
          const day = column.days[0] as Weekday;
          const slots = grid.days[day];
          const errors = validateDay(slots);
          return (
            <section key={column.key} aria-label={`Дзвінки: ${column.title}`} className="flex min-w-0 flex-col rounded-panel border border-default bg-surface">
              <header className="flex items-center justify-between gap-2 border-b border-separator bg-sunken px-3 py-2">
                <h3 className="text-ui font-semibold text-primary">{column.title}</h3>
                <span className="text-meta text-muted figures">{countLabel(slots.length, PAIR_FORMS)}</span>
              </header>
              <ol className="flex flex-col px-3 py-2">
                {slots.map((slot, index) => {
                  const error = errors.get(index);
                  const length = duration(slot);
                  const pause = breakAfter(slots, index);
                  const setSlot = (patch: Partial<SlotDraft>) =>
                    updateDay(
                      day,
                      slots.map((s, i) => (i === index ? { ...s, ...patch } : s)),
                    );
                  return (
                    <li key={index} className="flex flex-col">
                      <div className="flex items-center gap-2 py-1">
                        <span className="w-6 font-condensed text-heading font-bold figures text-primary">{index + 1}</span>
                        <Input
                          type="time"
                          size="sm"
                          value={slot.startsAt}
                          aria-label={`${index + 1} пара, ${weekdayShort(day)}: початок`}
                          aria-invalid={error ? true : undefined}
                          onChange={(event) => setSlot({ startsAt: event.target.value })}
                          className="w-[6.5rem] figures [&::-webkit-calendar-picker-indicator]:hidden"
                        />
                        <span aria-hidden="true" className="text-muted">
                          –
                        </span>
                        <Input
                          type="time"
                          size="sm"
                          value={slot.endsAt}
                          aria-label={`${index + 1} пара, ${weekdayShort(day)}: кінець`}
                          aria-invalid={error ? true : undefined}
                          onChange={(event) => setSlot({ endsAt: event.target.value })}
                          className="w-[6.5rem] figures [&::-webkit-calendar-picker-indicator]:hidden"
                        />
                        <span className="ms-auto text-meta text-muted figures">{length && length > 0 ? `${length} хв` : ''}</span>
                      </div>
                      {error ? <p className="ps-8 text-caption font-medium text-danger">{error}</p> : null}
                      {pause !== null && index < slots.length - 1 ? (
                        <p
                          className={cn(
                            'flex items-center gap-2 ps-8 text-meta figures',
                            pause < 5 ? 'font-semibold text-change' : 'text-muted',
                          )}
                        >
                          <span aria-hidden="true" className="h-px w-4 bg-[var(--border-color-default)]" />
                          {pause < 0 ? 'пари накладаються' : pause < 5 ? `перерва лише ${pause} хв` : `перерва ${pause} хв`}
                        </p>
                      ) : null}
                    </li>
                  );
                })}
              </ol>
              <footer className="mt-auto flex items-center gap-1 border-t border-separator px-2 py-1.5">
                <Button
                  size="sm"
                  variant="ghost"
                  iconStart={<Plus aria-hidden="true" className="size-4" />}
                  disabled={slots.length >= 8}
                  onClick={() => updateDay(day, [...slots, nextSlot(slots)])}
                >
                  Пара
                </Button>
                <IconButton
                  label={`Прибрати останню пару (${column.title})`}
                  icon={<Minus aria-hidden="true" className="size-4" />}
                  size="sm"
                  disabled={slots.length <= 1}
                  onClick={() => updateDay(day, slots.slice(0, -1))}
                />
              </footer>
            </section>
          );
        })}
      </div>

      <div className="flex flex-wrap items-center gap-2 border-t border-separator pt-4">
        {profile && !profile.defaultProfile ? (
          <Button variant="danger-ghost" size="sm" iconStart={<Trash aria-hidden="true" className="size-4" />} onClick={() => setDeleting(true)}>
            Видалити профіль
          </Button>
        ) : null}
        {profile?.defaultProfile ? (
          <Badge tone="accent" size="sm">
            Типовий профіль
          </Badge>
        ) : null}
        <span className="ms-auto text-caption text-muted">{dirty ? 'Є незбережені зміни' : 'Збережено'}</span>
        <Button variant="primary" loading={save.isPending} disabled={!dirty} onClick={() => void submit()}>
          {profile ? 'Зберегти дзвінки' : 'Створити профіль'}
        </Button>
      </div>

      <DeleteDialog
        target={deleting && profile ? { id: profile.id, name: `Профіль «${profile.name}»` } : null}
        onClose={() => setDeleting(false)}
        title="Видалити профіль дзвінків?"
        description="Профіль, який використовує семестр, видалити не можна — спершу змініть дзвінки семестру."
        onDelete={async (id) => {
          await remove.mutateAsync(id);
          onDeleted();
        }}
        successMessage={(label) => `${label}: видалено`}
      />
    </div>
  );
}
