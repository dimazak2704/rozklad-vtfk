import { FileText, X } from 'lucide-react';
import type { ReactNode } from 'react';
import { Controller, type UseFormReturn } from 'react-hook-form';
import type { PeriodScope } from '@/api/types';
import { Button, Chip, Combobox, Field, IconButton, Plate, RadioGroup, Textarea, Tooltip } from '@/components/ui';
import { SCOPE_LABEL } from './labels';
import type { PeriodValues } from './period-form';

const COURSES = [1, 2, 3, 4] as const;

type PeriodForm = UseFormReturn<PeriodValues>;

interface Option {
  value: string;
  label: string;
  description?: string;
  keywords?: string[];
}

interface ChosenListProps {
  label: string;
  items: ReadonlyArray<{ id: string; name: string }>;
  removeLabel: (name: string) => string;
  onRemove: (id: string) => void;
  /** Що показати, коли нічого не вибрано (за замовчуванням — нічого). */
  empty?: ReactNode;
}

/** Вибрані значення плашками з кнопкою «прибрати» біля кожної. */
function ChosenList({ label, items, removeLabel, onRemove, empty = null }: ChosenListProps) {
  if (items.length === 0) return empty;
  return (
    <ul className="flex flex-wrap gap-1.5" aria-label={label}>
      {items.map((item) => (
        <li key={item.id} className="inline-flex items-center gap-0.5">
          <Plate size="md">{item.name}</Plate>
          <IconButton label={removeLabel(item.name)} icon={<X aria-hidden="true" className="size-3.5" />} size="sm" className="size-7" onClick={() => onRemove(item.id)} />
        </li>
      ))}
    </ul>
  );
}

interface ScopeFieldsProps {
  form: PeriodForm;
  scope: PeriodScope;
  groupOptions: readonly Option[];
  chosenGroups: ReadonlyArray<{ id: string; code: string }>;
  groupsLoading: boolean;
}

/** Кого стосується період: усіх, окремих курсів чи вибраних груп. */
export function ScopeFields({ form, scope, groupOptions, chosenGroups, groupsLoading }: ScopeFieldsProps) {
  const { errors } = form.formState;
  return (
    <>
      <Controller
        control={form.control}
        name="scope"
        render={({ field }) => (
          <RadioGroup<PeriodScope>
            legend="Кого стосується"
            orientation="horizontal"
            value={field.value}
            onValueChange={field.onChange}
            options={(['ALL', 'COURSES', 'GROUPS'] as const).map((value) => ({ value, label: SCOPE_LABEL[value] }))}
          />
        )}
      />
      {scope === 'COURSES' ? (
        <Controller
          control={form.control}
          name="courses"
          render={({ field }) => (
            <fieldset className="flex flex-col gap-2">
              <legend className="sr-only">Курси</legend>
              <div className="flex flex-wrap gap-2">
                {COURSES.map((course) => {
                  const selected = field.value.includes(course);
                  return (
                    <Chip
                      key={course}
                      selected={selected}
                      onToggle={() => field.onChange(selected ? field.value.filter((c) => c !== course) : [...field.value, course].sort((a, b) => a - b))}
                    >
                      {course} курс
                    </Chip>
                  );
                })}
              </div>
              {errors.courses ? <p className="text-caption font-medium text-danger">{errors.courses.message}</p> : null}
            </fieldset>
          )}
        />
      ) : null}
      {scope === 'GROUPS' ? (
        <Controller
          control={form.control}
          name="groupIds"
          render={({ field }) => (
            <Field label="Групи" required error={errors.groupIds?.message}>
              <div className="flex flex-col gap-2">
                <Combobox
                  value={null}
                  onValueChange={(id) => {
                    if (id) field.onChange([...field.value, id]);
                  }}
                  options={groupOptions}
                  placeholder="Додати групу…"
                  searchPlaceholder="Код групи: 2КІ3 або 2ki3"
                  loading={groupsLoading}
                />
                <ChosenList
                  label="Вибрані групи"
                  items={chosenGroups.map((group) => ({ id: group.id, name: group.code }))}
                  removeLabel={(code) => `Прибрати групу ${code}`}
                  onRemove={(id) => field.onChange(field.value.filter((groupId) => groupId !== id))}
                />
              </div>
            </Field>
          )}
        />
      ) : null}
    </>
  );
}

interface SuspendFieldsProps {
  form: PeriodForm;
  kindName: string;
  roomOptions: readonly Option[];
  roomsLoading: boolean;
  supervisorOptions: readonly Option[];
  chosenSupervisors: ReadonlyArray<{ id: string; name: string }>;
  teachersLoading: boolean;
}

/** «Група не на парах»: де вона (аудиторія лише показується) і хто керує — керівнику це видно в розкладі. */
export function SuspendFields({ form, kindName, roomOptions, roomsLoading, supervisorOptions, chosenSupervisors, teachersLoading }: SuspendFieldsProps) {
  const { errors } = form.formState;
  return (
    <>
      <Field label="Аудиторія" error={errors.roomId?.message} hint={`Група побачить «${kindName}: назва, ауд. …». Аудиторію поки лише показуємо, не займаємо.`}>
        <Controller
          control={form.control}
          name="roomId"
          render={({ field }) => (
            <Combobox
              value={field.value}
              onValueChange={field.onChange}
              options={roomOptions}
              placeholder="Не вказано"
              searchPlaceholder="Номер аудиторії"
              loading={roomsLoading}
              clearable
              className="sm:max-w-56"
            />
          )}
        />
      </Field>
      <Controller
        control={form.control}
        name="supervisorIds"
        render={({ field }) => (
          <Field label="Керівники" error={errors.supervisorIds?.message} hint="Керівник побачить у своєму розкладі «Керівництво» з назвою періоду й групою.">
            <div className="flex flex-col gap-2">
              <Combobox
                value={null}
                onValueChange={(id) => {
                  if (id) field.onChange([...field.value, id]);
                }}
                options={supervisorOptions}
                placeholder="Додати керівника…"
                searchPlaceholder="Прізвище викладача"
                loading={teachersLoading}
              />
              <ChosenList
                label="Вибрані керівники"
                items={chosenSupervisors}
                removeLabel={(name) => `Прибрати керівника ${name}`}
                onRemove={(id) => field.onChange(field.value.filter((teacherId) => teacherId !== id))}
                empty={<p className="text-caption text-muted">Керівника не призначено.</p>}
              />
            </div>
          </Field>
        )}
      />
    </>
  );
}

/** Примітка в розпорядження: поле, «Скласти з полів» за шаблоном виду і вигляд під таблицею розпорядження. */
export function OrderNotePanel({ form, draftNote, note }: { form: PeriodForm; draftNote: string; note: string | undefined }) {
  return (
    <div className="flex min-w-0 flex-col gap-3 lg:border-s lg:border-separator lg:ps-5">
      <Field label="Примітка в розпорядженні" error={form.formState.errors.orderNote?.message} hint="Друкується жирним унизу розпоряджень на дати періоду.">
        <Textarea {...form.register('orderNote')} rows={3} />
      </Field>
      {/* з назви не видно, що текст примітки буде замінено */}
      <Tooltip content="Замінити примітку текстом із полів періоду" align="start">
        <Button
          size="sm"
          variant="secondary"
          className="self-start"
          iconStart={<FileText aria-hidden="true" className="size-4" />}
          disabled={!draftNote}
          onClick={() => form.setValue('orderNote', draftNote, { shouldDirty: true, shouldValidate: true })}
        >
          Скласти з полів
        </Button>
      </Tooltip>
      <figure className="flex flex-col gap-1.5">
        <figcaption className="text-caption text-muted">Так виглядатиме під таблицею розпорядження</figcaption>
        <div data-theme="light" className="rounded-tag border border-default bg-surface px-4 py-3 text-primary">
          {/* нижній край таблиці розпорядження, під яким друкується примітка */}
          <div aria-hidden="true" className="mb-2.5 h-px bg-[var(--border-color-strong)]" />
          <p className="text-ui font-bold">{note?.trim() || <span className="font-normal text-muted">Примітки не буде.</span>}</p>
        </div>
      </figure>
    </div>
  );
}
