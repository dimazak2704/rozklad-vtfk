import { Check } from 'lucide-react';
import { useMemo, useState } from 'react';
import { useCatalogOptions, type CatalogEntity } from '@/api/catalog';
import { needsDecision, supportsAlias, useSaveMappings, type ImportJob } from '@/api/import';
import type { MappingType } from '@/api/types';
import { applyServerErrors } from '@/components/catalog/forms';
import { FormAlert } from '@/components/catalog/FormParts';
import { Badge, Button, Combobox, EmptyState, RadioGroup, notify, type ComboboxOption } from '@/components/ui';
import { cn } from '@/lib/cn';
import { countLabel } from '@/lib/labels';
import { creates, decisionKey, decisionRows, initialDecision, isComplete, toMapping, type Decision, type EntityReportRow, type MappingAction } from './decisions';
import { ENTITY_ACCUSATIVE, ROW_TYPE_LABEL, scorePercent } from './labels';

const CATALOG_OF: Record<MappingType, CatalogEntity> = {
  TEACHER: 'teachers',
  DISCIPLINE: 'disciplines',
  GROUP: 'groups',
  ROOM: 'rooms',
};

/**
 * Рішення щодо назв, яких немає в каталозі: зіставити з наявним записом, запамʼятати написання як
 * псевдонім (тоді наступний імпорт розпізнає його сам) або створити новий запис. Зберігаються разом.
 */
export function MappingPanel({ job }: { job: ImportJob }) {
  if (job.kind === 'ORDER_DOCUMENT') return <OrderMatching job={job} />;
  return <MappingForm job={job} />;
}

/**
 * Розпорядження зберігається як текст: каталог не змінюється, тож рішень немає — лише довідка, як назви з
 * розпорядження співвідносяться з каталогом (схожі записи допомагають помітити одруківку при звірці).
 */
function OrderMatching({ job }: { job: ImportJob }) {
  const rows = useMemo(() => decisionRows(job), [job]);
  if (rows.length === 0) {
    return (
      <EmptyState
        size="sm"
        icon={<Check aria-hidden="true" />}
        title="Усі назви є в каталозі"
        description="Групи, викладачі, дисципліни й аудиторії розпорядження знайдено в каталозі."
      />
    );
  }
  return (
    <div className="flex flex-col gap-4">
      <p className="max-w-prose text-ui text-secondary">
        Розпорядження зберігається як текст — каталог не змінюється, рішень тут не потрібно. Назви, яких немає в каталозі,
        варто ще раз звірити з фото на вкладці «Чернетка»: схожий запис часто підказує одруківку.
      </p>
      <ol className="flex flex-col divide-y divide-[var(--border-color-separator)] rounded-panel border border-default bg-surface">
        {rows.map((row) => (
          <li key={decisionKey(row)} className="flex flex-wrap items-baseline gap-x-3 gap-y-1 px-4 py-3">
            <Badge size="sm" tone="neutral">
              {ROW_TYPE_LABEL[row.type]}
            </Badge>
            <span className="font-condensed text-heading font-bold break-words text-primary">«{row.raw}»</span>
            {row.suggestions.length > 0 ? (
              <span className="text-caption text-secondary">
                схоже: {row.suggestions.map((s) => `«${s.label}»`).join(', ')}
              </span>
            ) : (
              <span className="text-caption text-muted">немає в каталозі</span>
            )}
            <span className="text-meta font-normal text-muted">{row.location}</span>
          </li>
        ))}
      </ol>
    </div>
  );
}

function MappingForm({ job }: { job: ImportJob }) {
  const rows = useMemo(() => decisionRows(job), [job]);
  const save = useSaveMappings(job.id);
  const editable = job.status === 'NEEDS_REVIEW' || job.status === 'READY';
  const [decisions, setDecisions] = useState<Map<string, Decision>>(
    () =>
      new Map(
        rows.map((row) => [
          decisionKey(row),
          initialDecision(row, job.mappings?.find((m) => decisionKey(m) === decisionKey(row)), creates(job.kind, row.type)),
        ]),
      ),
  );
  const [problem, setProblem] = useState<string | null>(null);
  const [missing, setMissing] = useState<ReadonlySet<string>>(new Set());
  const [dirty, setDirty] = useState(false);

  const update = (key: string, patch: Partial<Decision>) => {
    setDecisions((current) => {
      const next = new Map(current);
      next.set(key, { ...(current.get(key) ?? { action: null, targetId: null }), ...patch });
      return next;
    });
    setDirty(true);
    if (missing.has(key)) setMissing(new Set([...missing].filter((k) => k !== key)));
  };

  const undecided = rows.filter((row) => !isComplete(decisions.get(decisionKey(row))));

  const submit = async () => {
    setProblem(null);
    if (undecided.length > 0) {
      setMissing(new Set(undecided.map(decisionKey)));
      setProblem(`Вирішіть ${countLabel(undecided.length, ['рядок', 'рядки', 'рядків'])}: оберіть дію і запис каталогу.`);
      return;
    }
    const mappings = rows.map((row) => toMapping(row, decisions.get(decisionKey(row)) ?? { action: null, targetId: null })).filter((m) => m !== null);
    try {
      const updated = await save.mutateAsync(mappings);
      setDirty(false);
      notify.success(
        updated.summary.conflicts === 0
          ? 'Рішення збережено — імпорт готовий до застосування'
          : `Рішення збережено. Лишилось вирішити: ${updated.summary.conflicts}`,
      );
    } catch (error) {
      setProblem(applyServerErrors(error, () => undefined, []));
    }
  };

  if (rows.length === 0) {
    return (
      <EmptyState
        size="sm"
        icon={<Check aria-hidden="true" />}
        title="Рішень не потрібно"
        description="Усі назви з файлу знайдено в каталозі — нових записів чи можливих дублікатів немає."
      />
    );
  }

  return (
    <div className="flex flex-col gap-4">
      <p className="max-w-prose text-ui text-secondary">
        Назви з файлу, яких немає в каталозі. Для одруківок оберіть «Зіставити й запамʼятати» — написання стане псевдонімом, і
        наступного разу його розпізнають самі.
      </p>
      <ol className="flex flex-col divide-y divide-[var(--border-color-separator)] rounded-panel border border-default bg-surface">
        {rows.map((row) => (
          <MappingRow
            key={decisionKey(row)}
            row={row}
            decision={decisions.get(decisionKey(row)) ?? { action: null, targetId: null }}
            onChange={(patch) => update(decisionKey(row), patch)}
            missing={missing.has(decisionKey(row))}
            disabled={!editable || save.isPending}
            creatable={creates(job.kind, row.type)}
          />
        ))}
      </ol>
      <FormAlert message={problem} />
      {editable ? (
        <div className="flex flex-wrap items-center justify-end gap-3">
          <span className="text-caption text-muted" aria-live="polite">
            {undecided.length > 0
              ? `Без рішення: ${undecided.length}`
              : dirty
                ? 'Рішення не збережено'
                : 'Рішення збережено'}
          </span>
          <Button variant="primary" loading={save.isPending} disabled={!dirty && undecided.length === 0} onClick={() => void submit()}>
            Зберегти рішення
          </Button>
        </div>
      ) : (
        <p className="text-caption text-muted">Імпорт уже застосовано — рішення змінити не можна.</p>
      )}
    </div>
  );
}

interface MappingRowProps {
  row: EntityReportRow;
  decision: Decision;
  onChange: (patch: Partial<Decision>) => void;
  missing: boolean;
  disabled: boolean;
  /** Цей імпорт може створити такий запис (інакше — лише зіставити з наявним). */
  creatable: boolean;
}

function MappingRow({ row, decision, onChange, missing, disabled, creatable }: MappingRowProps) {
  const catalog = useCatalogOptions(CATALOG_OF[row.type], {}, { enabled: decision.action === 'existing' || decision.action === 'alias' });
  const top = row.suggestions[0];
  const aliasable = supportsAlias(row.type);

  const options = useMemo<ComboboxOption[]>(() => {
    const suggested = new Set(row.suggestions.map((s) => s.id));
    const fromSuggestions = row.suggestions.map((s) => ({ value: s.id, label: s.label, description: `схоже на ${scorePercent(s.score)}%` }));
    const rest = (catalog.items ?? [])
      .filter((item) => !suggested.has(item.id))
      .map((item) => ({ value: item.id, label: catalogLabel(item) }));
    return [...fromSuggestions, ...rest];
  }, [row.suggestions, catalog.items]);

  const actions = [
    { value: 'existing' as const, label: 'Зіставити з наявним' },
    ...(aliasable ? [{ value: 'alias' as const, label: 'Зіставити й запамʼятати написання' }] : []),
    ...(creatable ? [{ value: 'create' as const, label: `Створити ${ENTITY_ACCUSATIVE[row.type]}` }] : []),
  ];

  return (
    <li className={cn('grid gap-3 px-4 py-3.5 lg:grid-cols-[minmax(0,17rem)_minmax(0,1fr)]', missing && 'bg-danger-subtle')}>
      <div className="flex min-w-0 flex-col gap-1">
        <span className="flex flex-wrap items-center gap-2">
          <Badge size="sm" tone={needsDecision(row) || row.severity === 'ERROR' ? 'warning' : 'neutral'}>
            {ROW_TYPE_LABEL[row.type]}
          </Badge>
          <span className="font-condensed text-heading font-bold break-words text-primary">«{row.raw}»</span>
        </span>
        {row.message ? <span className="text-caption text-secondary">{row.message}</span> : null}
        <span className="text-meta font-normal text-muted">{row.location}</span>
      </div>
      <div className="flex min-w-0 flex-col gap-2.5">
        {/* '' — «ще не вирішено»: група лишається керованою, без перемикання в некеровану */}
        <RadioGroup<MappingAction | ''>
          aria-label={`Що зробити з «${row.raw}»`}
          orientation="horizontal"
          value={decision.action ?? ''}
          disabled={disabled}
          onValueChange={(action) => {
            if (action) onChange({ action, targetId: action === 'create' ? null : (decision.targetId ?? top?.id ?? null) });
          }}
          options={actions}
        />
        {decision.action === 'existing' || decision.action === 'alias' ? (
          <div className="flex flex-wrap items-center gap-2">
            <Combobox
              value={decision.targetId}
              onValueChange={(targetId) => onChange({ targetId })}
              options={options}
              placeholder={`Оберіть ${ENTITY_ACCUSATIVE[row.type]}`}
              searchPlaceholder="Пошук у каталозі"
              loading={catalog.isPending}
              disabled={disabled}
              aria-label={`Запис каталогу для «${row.raw}»`}
              aria-invalid={missing && !decision.targetId ? true : undefined}
              className="w-full sm:w-72"
            />
            {decision.action === 'alias' ? (
              <span className="text-caption text-muted">«{row.raw}» стане псевдонімом</span>
            ) : null}
          </div>
        ) : decision.action === null && top ? (
          <div>
            <Button
              size="sm"
              variant="secondary"
              disabled={disabled}
              onClick={() => onChange({ action: aliasable ? 'alias' : 'existing', targetId: top.id })}
            >
              Так, це «{top.label}»
            </Button>
          </div>
        ) : null}
        {missing ? <p className="text-caption font-medium text-danger">Оберіть дію{decision.action && decision.action !== 'create' ? ' і запис каталогу' : ''}.</p> : null}
      </div>
    </li>
  );
}

function catalogLabel(item: object): string {
  if ('shortName' in item && typeof item.shortName === 'string') return item.shortName;
  if ('code' in item && typeof item.code === 'string') return item.code;
  if ('name' in item && typeof item.name === 'string') return item.name;
  return '—';
}
