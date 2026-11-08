import { Play, RotateCcw } from 'lucide-react';
import { useMemo, useState } from 'react';
import { DEFAULT_WEIGHTS, TIME_LIMIT_MAX, TIME_LIMIT_MIN } from '@/api/generation';
import type { PublicGroupDto, TimetableVersionDto, Uuid } from '@/api/types';
import { Button, Chip, Combobox, Field, NumberInput, Panel, RadioGroup, SegmentedControl, Select } from '@/components/ui';
import { VERSION_STATUS_LABEL } from '@/components/timetable/model/labels';
import { groupDescription } from '@/lib/names';
import { formatSeconds, validateForm, WEIGHT_FIELDS, WEIGHT_MAX, type GenerationFormState, type ScopeMode } from './model';
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
  starting: boolean;
  onStart: () => void;
}

/**
 * Параметри генерації: які групи, від якої версії, скільки часу, що важливіше. Кожна вага пояснена
 * словами; «Скинути ваги» повертає типові значення core.
 */
export function GenerationForm({ form, onChange, groups, versions, busy, starting, onStart }: GenerationFormProps) {
  const [submitted, setSubmitted] = useState(false);
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

  const start = () => {
    setSubmitted(true);
    if (Object.keys(validateForm(form)).length === 0) onStart();
  };

  return (
    <Panel
      title="Параметри"
      description="Розвʼязувач Timefold переставляє незакріплені пари, доки не вичерпає ліміт часу."
      footer={
        <Button
          variant="primary"
          size="lg"
          className="w-full"
          onClick={start}
          disabled={busy}
          loading={starting}
          iconStart={<Play aria-hidden="true" className="size-4" />}
        >
          Запустити генерацію
        </Button>
      }
    >
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
                      onRemove={() => onChange({ ...form, groupIds: form.groupIds.filter((g) => g !== id) })}
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

        <Field
          label="Базова версія"
          hint={
            form.baseVersionId
              ? 'Закріплені пари й «Було» беруться з неї; результат — нова чернетка, база не змінюється.'
              : 'Пари складаються з навантаження семестру; «Було» — опублікований розклад.'
          }
        >
          <Select
            value={form.baseVersionId ?? NO_BASE}
            onValueChange={(value) => onChange({ ...form, baseVersionId: value === NO_BASE ? null : value })}
            options={[
              ...versions.map((v) => ({ value: v.id, label: v.name, description: `${VERSION_STATUS_LABEL[v.status]} · конфліктів: ${v.hardConflicts}` })),
              { value: NO_BASE, label: 'Без базової — з навантаження' },
            ]}
          />
        </Field>

        <div className="flex flex-col gap-2">
          <Field label="Ліміт часу" error={errors.timeLimitSeconds} hint={`Від ${TIME_LIMIT_MIN} с до ${TIME_LIMIT_MAX / 60} хв. Довше — краща оцінка, але чекати довше.`}>
            <NumberInput
              value={form.timeLimitSeconds}
              onValueChange={(value) => onChange({ ...form, timeLimitSeconds: value ?? TIME_LIMIT_MIN })}
              min={TIME_LIMIT_MIN}
              max={TIME_LIMIT_MAX}
              step={10}
              unit="с"
            />
          </Field>
          <SegmentedControl
            aria-label="Швидкий вибір ліміту"
            size="sm"
            stretch
            value={String(form.timeLimitSeconds)}
            onValueChange={(value) => onChange({ ...form, timeLimitSeconds: Number(value) })}
            options={TIME_PRESETS.map((seconds) => ({ value: String(seconds), label: formatSeconds(seconds) }))}
          />
        </div>

        <div className="flex flex-col gap-4 border-t border-separator pt-4">
          <div className="flex items-center justify-between gap-2">
            <h3 className="text-heading font-semibold text-primary">Що важливіше</h3>
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
              onValueChange={(value) => onChange({ ...form, weights: { ...form.weights, [key]: value } })}
            />
          ))}
        </div>
      </fieldset>
    </Panel>
  );
}
