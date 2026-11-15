import { RotateCcw } from 'lucide-react';
import { useMemo, type ReactNode } from 'react';
import { DEFAULT_WEIGHTS } from '@/api/generation';
import type { LoadProblemDto, PublicGroupDto, TimetableVersionDto, Uuid } from '@/api/types';
import { Button, Chip, Combobox, Field, Panel, RadioGroup, Select } from '@/components/ui';
import { VERSION_STATUS_LABEL } from '@/components/timetable/model/labels';
import { groupDescription } from '@/lib/names';
import { validateForm, type GenerationFormState, type ScopeMode } from './model';
import { Disclosure } from './Disclosure';
import { LATE_PAIR_HINT, PRIORITIES, PRIORITY_HINT, PRIORITY_LABEL, PRIORITY_RULES, SECTION_LABEL, changedPriorities, type RuleSection } from './priorities';
import { PrioritySelect } from './PrioritySelect';

const SECTIONS: readonly RuleSection[] = ['groups', 'teachers', 'whole'];

const NO_BASE = 'none';

interface GenerationFormProps {
  form: GenerationFormState;
  onChange: (form: GenerationFormState) => void;
  groups: readonly PublicGroupDto[];
  versions: readonly TimetableVersionDto[];
  /** Задача вже йде — нову не запускаємо, форму блокуємо. */
  busy: boolean;
  /** Після спроби запуску — показати помилки полів. */
  submitted: boolean;
  /** Кнопка «Запустити генерацію» — наприкінці панелі, біля параметрів, до яких вона застосовується. */
  submit?: ReactNode;
  /** Заголовок панелі: «Параметри» або, поки задача йде, «Параметри запуску». */
  title?: string;
  /** 422 `GENERATION_LOAD_EXCEEDS_SLOTS`: що не вміщується в сітку — список біля кнопки запуску. */
  loadProblems?: readonly LoadProblemDto[];
}

/**
 * Параметри генерації: які групи, від якої версії, що важливіше. Ліміту часу немає: генерація зупиняється сама,
 * коли розклад перестає покращуватися. Пояснення ваг — в одній розгортці «Що означають пріоритети?»;
 * «Типові пріоритети» повертає типові значення core.
 */
export function GenerationForm({
  form,
  onChange,
  groups,
  versions,
  busy,
  submitted,
  submit,
  title = 'Параметри',
  loadProblems = [],
}: GenerationFormProps) {
  const errors = submitted ? validateForm(form) : {};
  const groupOptions = useMemo(
    () =>
      groups.map((g) => ({ value: g.id, label: g.code, description: groupDescription(g), keywords: g.department ? [g.department] : [] })),
    [groups],
  );
  const groupsById = useMemo(() => new Map(groups.map((g) => [g.id, g])), [groups]);
  // загальне «Типові пріоритети» — коли хоч щось змінено
  const weightsChanged = changedPriorities(form.weights).length > 0;
  const scopeHint =
    form.scope === 'all'
      ? `Усі ${groups.length} груп.`
      : form.scope === 'courses'
        ? `Груп у вибраних курсах: ${groups.filter((g) => form.courses.includes(g.course)).length}. Пари інших груп лишаються як у базовій версії.`
        : 'Пари інших груп лишаються як у базовій версії.';

  return (
    <Panel title={title}>
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
            orientation="vertical"
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
                      courses: form.courses.includes(course) ? form.courses.filter((c) => c !== course) : [...form.courses, course].sort((a, b) => a - b),
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
                Типові пріоритети
              </Button>
            ) : null}
          </div>
          {SECTIONS.map((section) => (
            <div key={section} role="group" aria-labelledby={`priority-${section}`} className="flex flex-col gap-1.5">
              <span id={`priority-${section}`} className="text-caption font-[650] text-secondary">
                {SECTION_LABEL[section]}
              </span>
              {PRIORITY_RULES.filter((rule) => rule.section === section).map((rule) => (
                <PrioritySelect
                  key={rule.id}
                  rule={rule}
                  weights={form.weights}
                  disabled={busy}
                  onChange={(weights) => onChange({ ...form, weights })}
                />
              ))}
            </div>
          ))}
          <Disclosure summary="Що означають пріоритети?" contentClassName="ps-5.5">
            <dl className="flex flex-col gap-2 text-caption">
              {PRIORITIES.map((priority) => (
                <div key={priority}>
                  <dt className="font-[650] text-primary">{PRIORITY_LABEL[priority]}</dt>
                  <dd className="text-secondary">{PRIORITY_HINT[priority]}</dd>
                </div>
              ))}
              {PRIORITY_RULES.map((rule) => (
                <div key={rule.id}>
                  <dt className="font-[650] text-primary">{rule.label}</dt>
                  <dd className="text-secondary">{rule.id === 'latePairs' ? LATE_PAIR_HINT : rule.hint}</dd>
                </div>
              ))}
            </dl>
          </Disclosure>
        </div>
      </fieldset>
      {loadProblems.length > 0 ? (
        <section role="alert" aria-labelledby="load-problems-title" className="mt-5 flex flex-col gap-2 rounded-control border border-cancel bg-danger-subtle px-3 py-2.5">
          <h3 id="load-problems-title" className="text-ui font-[650] text-danger">
            Навантаження не вміщується в сітку занять
          </h3>
          <ul className="flex list-disc flex-col gap-1 ps-4 text-caption text-primary">
            {loadProblems.map((problem) => (
              <li key={`${problem.kind}|${problem.id}`}>{problem.message}</li>
            ))}
          </ul>
          <p className="text-caption text-secondary">Виправте навантаження, недоступність викладачів або аудиторії й запустіть ще раз.</p>
        </section>
      ) : null}
      {/* підвал панелі липне до низу екрана, поки панель видно: запуск поруч із параметрами й завжди на виду */}
      {submit ? (
        <div className="sticky bottom-0 z-10 -mx-5 mt-6 -mb-5 flex rounded-b-panel border-t border-separator bg-surface px-5 py-3">{submit}</div>
      ) : null}
    </Panel>
  );
}
