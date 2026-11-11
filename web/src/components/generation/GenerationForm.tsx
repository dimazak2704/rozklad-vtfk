import { RotateCcw } from 'lucide-react';
import { useMemo } from 'react';
import { DEFAULT_WEIGHTS } from '@/api/generation';
import type { PublicGroupDto, TimetableVersionDto, Uuid } from '@/api/types';
import { Button, Chip, Combobox, Field, Panel, RadioGroup, SegmentedControl, Select } from '@/components/ui';
import { VERSION_STATUS_LABEL } from '@/components/timetable/model/labels';
import { groupDescription } from '@/lib/names';
import { formatSeconds, validateForm, WEIGHT_FIELDS, WEIGHT_MAX, type GenerationFormState, type ScopeMode } from './model';
import { Disclosure } from './Disclosure';
import { WeightSlider } from './WeightSlider';

const NO_BASE = 'none';
const TIME_PRESETS = [30, 60, 120, 300, 600] as const;

interface GenerationFormProps {
  form: GenerationFormState;
  onChange: (form: GenerationFormState) => void;
  groups: readonly PublicGroupDto[];
  versions: readonly TimetableVersionDto[];
  /** Задача вже йде — нову не запускаємо, форму блокуємо. */
  busy: boolean;
  /** Після спроби запуску — показати помилки полів (кнопка запуску в заголовку сторінки). */
  submitted: boolean;
}

/**
 * Параметри генерації: які групи, від якої версії, скільки часу, що важливіше. Пояснення ваг — в одній
 * розгортці «Що означають ваги?»; «Скинути ваги» повертає типові значення core.
 */
export function GenerationForm({ form, onChange, groups, versions, busy, submitted }: GenerationFormProps) {
  const errors = submitted ? validateForm(form) : {};
  const groupOptions = useMemo(
    () =>
      groups.map((g) => ({ value: g.id, label: g.code, description: groupDescription(g), keywords: g.department ? [g.department] : [] })),
    [groups],
  );
  const groupsById = useMemo(() => new Map(groups.map((g) => [g.id, g])), [groups]);
  const weightsChanged = WEIGHT_FIELDS.some(({ key }) => form.weights[key] !== DEFAULT_WEIGHTS[key]);
  const scopeHint =
    form.scope === 'all'
      ? `Усі ${groups.length} груп.`
      : form.scope === 'courses'
        ? `Груп у вибраних курсах: ${groups.filter((g) => form.courses.includes(g.course)).length}. Пари інших груп лишаються як у базовій версії.`
        : 'Пари інших груп лишаються як у базовій версії.';

  return (
    <Panel title="Параметри">
      <fieldset disabled={busy} className="flex flex-col gap-5">
        <div className="flex flex-col gap-2">
          <RadioGroup<ScopeMode>
            legend="Які групи"
            value={form.scope}
            onValueChange={(scope) => onChange({ ...form, scope })}
            options={[
              { value: 'all', label: 'Усі групи' },
              { value: 'courses', label: 'Окремі курси' },
              { value: 'groups', label: 'Окремі групи' },
            ]}
            orientation="horizontal"
          />
          {form.scope === 'courses' ? (
            <div className="flex flex-wrap gap-1.5" role="group" aria-label="Курси">
              {[1, 2, 3, 4].map((course) => (
                <Chip
                  key={course}
                  selected={form.courses.includes(course)}
                  onToggle={() =>
                    onChange({
                      ...form,
                      courses: form.courses.includes(course) ? form.courses.filter((c) => c !== course) : [...form.courses, course].sort(),
                    })
                  }
                >
                  {course} курс
                </Chip>
              ))}
            </div>
          ) : null}
          {form.scope === 'groups' ? (
            <div className="flex flex-col gap-2">
              <Combobox
                aria-label="Додати групу"
                value={null}
                onValueChange={(id) => id && !form.groupIds.includes(id) && onChange({ ...form, groupIds: [...form.groupIds, id] })}
                options={groupOptions.filter((option) => !form.groupIds.includes(option.value))}
                placeholder="Додати групу…"
                searchPlaceholder="Код групи: 4КІ1"
              />
              {form.groupIds.length > 0 ? (
                <div className="flex flex-wrap gap-1.5">
                  {form.groupIds.map((id: Uuid) => (
                    <Chip
                      key={id}
                      selected
                      onRemove={() =>
                        onChange({
                          ...form,
                          groupIds: form.groupIds.filter((g) => g !== id),
                        })
                      }
                      removeLabel={`Прибрати ${groupsById.get(id)?.code ?? 'групу'}`}
                    >
                      {groupsById.get(id)?.code ?? '—'}
                    </Chip>
                  ))}
                </div>
              ) : null}
            </div>
          ) : null}
          <p className={errors.scope ? 'text-caption font-medium text-danger' : 'text-caption text-muted'}>{errors.scope ?? scopeHint}</p>
        </div>

        <Field label="Базова версія" hint={form.baseVersionId ? undefined : 'Пари складаються з навантаження семестру.'}>
          <Select
            value={form.baseVersionId ?? NO_BASE}
            onValueChange={(value) =>
              onChange({
                ...form,
                baseVersionId: value === NO_BASE ? null : value,
              })
            }
            options={[
              ...versions.map((v) => ({
                value: v.id,
                label: v.name,
                description:
                  v.hardConflicts > 0 ? `${VERSION_STATUS_LABEL[v.status]}, конфліктів: ${v.hardConflicts}` : VERSION_STATUS_LABEL[v.status],
              })),
              { value: NO_BASE, label: 'Без базової — з навантаження' },
            ]}
          />
        </Field>

        {/* одне керування на одне значення: лише швидкий вибір, без окремого поля з плюсом і мінусом */}
        <div className="flex flex-col gap-1.5">
          <span aria-hidden="true" className="text-caption font-[650] text-primary">
            Ліміт часу
          </span>
          <SegmentedControl
            aria-label="Ліміт часу"
            stretch
            value={TIME_PRESETS.includes(form.timeLimitSeconds as (typeof TIME_PRESETS)[number]) ? String(form.timeLimitSeconds) : ''}
            onValueChange={(value) => onChange({ ...form, timeLimitSeconds: Number(value) })}
            options={TIME_PRESETS.map((seconds) => ({
              value: String(seconds),
              label: formatSeconds(seconds),
            }))}
          />
          {errors.timeLimitSeconds ? <p className="text-caption font-medium text-danger">{errors.timeLimitSeconds}</p> : null}
        </div>

        <div className="flex flex-col gap-4 border-t border-separator pt-4">
          <div className="flex items-center justify-between gap-2">
            <h3 className="text-heading font-bold text-primary">Що важливіше</h3>
            {weightsChanged ? (
              <Button
                size="sm"
                variant="ghost"
                onClick={() => onChange({ ...form, weights: { ...DEFAULT_WEIGHTS } })}
                iconStart={<RotateCcw aria-hidden="true" className="size-3.5" />}
              >
                Скинути ваги
              </Button>
            ) : null}
          </div>
          {WEIGHT_FIELDS.map(({ key, label, hint }) => (
            <WeightSlider
              key={key}
              label={label}
              hint={hint}
              value={form.weights[key]}
              max={WEIGHT_MAX}
              defaultValue={DEFAULT_WEIGHTS[key]}
              disabled={busy}
              onValueChange={(value) =>
                onChange({
                  ...form,
                  weights: { ...form.weights, [key]: value },
                })
              }
            />
          ))}
          <Disclosure summary="Що означають ваги?" contentClassName="ps-5.5">
            <dl className="flex flex-col gap-2 text-caption">
              {WEIGHT_FIELDS.map(({ key, label, hint }) => (
                <div key={key}>
                  <dt className="font-[650] text-primary">{label}</dt>
                  <dd className="text-secondary">{hint}</dd>
                </div>
              ))}
              <div>
                <dt className="font-[650] text-primary">0</dt>
                <dd className="text-secondary">Не враховувати це зовсім. Більше число — важливіше.</dd>
              </div>
            </dl>
          </Disclosure>
        </div>
      </fieldset>
    </Panel>
  );
}
