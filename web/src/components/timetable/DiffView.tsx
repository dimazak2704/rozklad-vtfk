import { ArrowRight, Crosshair, Minus, Plus, Search } from 'lucide-react';
import { useMemo, useState, type ReactNode } from 'react';
import { useVersionDiff } from '@/api/timetable';
import type { TemplateLessonDto, TimetableVersionDto, Uuid } from '@/api/types';
import {
  Button,
  EmptyState,
  ErrorState,
  IconButton,
  Input,
  LoadingRegion,
  Plate,
  SegmentedControl,
  Select,
  Skeleton,
} from '@/components/ui';
import { cn } from '@/lib/cn';
import { roomLabel } from '@/lib/schedule';
import { matchScore } from '@/lib/search';
import { ukCollator } from '@/lib/sort';
import { VERSION_STATUS_LABEL } from './model/labels';
import { slotShort } from './model/text';

type DiffKind = 'moved' | 'added' | 'removed';
type DiffFilter = 'all' | DiffKind;

interface DiffRow {
  kind: DiffKind;
  key: string;
  groupCode: string;
  course: number;
  /** Заняття в цільовій версії (для «показати в сітці»); у прибраних — немає. */
  target: TemplateLessonDto | null;
  before: TemplateLessonDto | null;
  after: TemplateLessonDto | null;
}

interface DiffViewProps {
  versions: readonly TimetableVersionDto[];
  target: TimetableVersionDto;
  baseId: Uuid | null;
  onBaseChange: (baseId: Uuid) => void;
  onShowInGrid: (lesson: TemplateLessonDto) => void;
}

const PAGE = 120;

const KIND_META: Record<DiffKind, { label: string; tone: string; icon: ReactNode }> = {
  moved: { label: 'Переміщено', tone: 'text-change', icon: <ArrowRight aria-hidden="true" className="size-3.5" /> },
  added: { label: 'Додано', tone: 'text-add', icon: <Plus aria-hidden="true" className="size-3.5" /> },
  removed: { label: 'Прибрано', tone: 'text-cancel', icon: <Minus aria-hidden="true" className="size-3.5" /> },
};

const who = (lesson: TemplateLessonDto) =>
  [lesson.discipline.shortName, lesson.subgroup > 0 ? `${lesson.subgroup} п/г` : null, lesson.teacher?.shortName].filter(Boolean).join(' · ');
const where = (lesson: TemplateLessonDto) => [slotShort(lesson), roomLabel(lesson.room?.name)].filter(Boolean).join(', ');

/**
 * Порівняння версій: що в «цільовій» версії додано, прибрано й переміщено відносно базової (зазвичай
 * опублікованої). Рядки згруповано за групами; «показати в сітці» відкриває пару в редакторі.
 */
export function DiffView({ versions, target, baseId, onBaseChange, onShowInGrid }: DiffViewProps) {
  const others = versions.filter((v) => v.id !== target.id);
  const base = others.find((v) => v.id === baseId) ?? others.find((v) => v.status === 'PUBLISHED') ?? others[0] ?? null;
  const diff = useVersionDiff(base?.id, target.id);
  const [filter, setFilter] = useState<DiffFilter>('all');
  const [query, setQuery] = useState('');
  const [limit, setLimit] = useState(PAGE);

  const rows = useMemo<DiffRow[]>(() => {
    if (!diff.data) return [];
    const list: DiffRow[] = [
      ...diff.data.moved.map(({ from, to }) => ({
        kind: 'moved' as const,
        key: `m-${to.id}`,
        groupCode: to.group.code,
        course: to.group.course,
        target: to,
        before: from,
        after: to,
      })),
      ...diff.data.added.map((lesson) => ({
        kind: 'added' as const,
        key: `a-${lesson.id}`,
        groupCode: lesson.group.code,
        course: lesson.group.course,
        target: lesson,
        before: null,
        after: lesson,
      })),
      ...diff.data.removed.map((lesson) => ({
        kind: 'removed' as const,
        key: `r-${lesson.id}`,
        groupCode: lesson.group.code,
        course: lesson.group.course,
        target: null,
        before: lesson,
        after: null,
      })),
    ];
    return list.sort((a, b) => a.course - b.course || ukCollator.compare(a.groupCode, b.groupCode));
  }, [diff.data]);

  const visible = rows.filter((row) => {
    if (filter !== 'all' && row.kind !== filter) return false;
    if (!query.trim()) return true;
    const lesson = (row.after ?? row.before) as TemplateLessonDto;
    return matchScore(query, row.groupCode, lesson.discipline.shortName, lesson.discipline.name, lesson.teacher?.shortName) >= 40;
  });
  const counts = { moved: diff.data?.moved.length ?? 0, added: diff.data?.added.length ?? 0, removed: diff.data?.removed.length ?? 0 };
  const groups = groupRows(visible.slice(0, limit));

  if (!base) {
    return (
      <EmptyState
        title="Немає з чим порівнювати"
        description="У семестрі лише одна версія. Створіть копію, змініть її — і тут зʼявиться різниця."
        className="rounded-panel border border-default bg-surface"
      />
    );
  }

  return (
    <section aria-labelledby="diff-title" className="flex flex-col gap-4">
      <div className="flex flex-wrap items-end gap-x-6 gap-y-3 rounded-panel border border-default bg-surface px-4 py-3">
        <div className="flex min-w-0 flex-col gap-1">
          <h2 id="diff-title" className="text-heading font-semibold text-primary">
            Що змінилось у «{target.name}»
          </h2>
          <div className="flex flex-wrap items-center gap-2 text-caption text-secondary">
            <span>відносно</span>
            <Select
              aria-label="Базова версія для порівняння"
              size="sm"
              className="w-72"
              value={base.id}
              onValueChange={onBaseChange}
              options={others.map((v) => ({ value: v.id, label: v.name, description: VERSION_STATUS_LABEL[v.status] }))}
            />
          </div>
        </div>
        <dl className="ms-auto grid grid-cols-3 gap-px overflow-hidden rounded-control border border-default bg-[var(--border-color-separator)] text-center">
          {(['added', 'removed', 'moved'] as const).map((kind) => (
            <div key={kind} className="flex min-w-24 flex-col bg-surface px-4 py-2">
              <dt className="order-2 text-caption text-secondary">{KIND_META[kind].label}</dt>
              <dd className={cn('order-1 font-condensed text-headline leading-none font-bold figures', KIND_META[kind].tone)}>
                {diff.isPending ? <Skeleton className="mx-auto h-7 w-8" /> : `${counts[kind] > 0 && kind === 'added' ? '+' : counts[kind] > 0 && kind === 'removed' ? '−' : ''}${counts[kind]}`}
              </dd>
            </div>
          ))}
        </dl>
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <SegmentedControl<DiffFilter>
          aria-label="Які зміни показати"
          value={filter}
          onValueChange={(next) => {
            setFilter(next);
            setLimit(PAGE);
          }}
          options={[
            { value: 'all', label: `Усі ${rows.length}` },
            { value: 'moved', label: `Переміщено ${counts.moved}` },
            { value: 'added', label: `Додано ${counts.added}` },
            { value: 'removed', label: `Прибрано ${counts.removed}` },
          ]}
        />
        <Input
          type="search"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder="Група, дисципліна, викладач"
          aria-label="Пошук у змінах"
          iconStart={<Search aria-hidden="true" className="size-4" />}
          wrapperClassName="w-full sm:w-64"
        />
      </div>

      {diff.isPending ? (
        <LoadingRegion label="Порівнюємо версії…" className="flex flex-col gap-2">
          {Array.from({ length: 6 }, (_, i) => (
            <Skeleton key={i} className="h-12 w-full rounded-panel" />
          ))}
        </LoadingRegion>
      ) : diff.error ? (
        <ErrorState error={diff.error} onRetry={() => void diff.refetch()} title="Порівняння не завантажилось" className="rounded-panel border border-default bg-surface" />
      ) : rows.length === 0 ? (
        <EmptyState
          title="Версії однакові"
          description={`У «${target.name}» ті самі пари в тих самих слотах, що й у «${base.name}». Змініть щось у сітці — і тут зʼявиться різниця.`}
          className="rounded-panel border border-default bg-surface"
        />
      ) : visible.length === 0 ? (
        <EmptyState
          size="sm"
          title="Нічого не знайдено"
          description="Спробуйте інший запит або покажіть усі зміни."
          action={
            <Button
              size="sm"
              onClick={() => {
                setQuery('');
                setFilter('all');
              }}
            >
              Показати всі зміни
            </Button>
          }
          className="rounded-panel border border-default bg-surface"
        />
      ) : (
        <div className="flex flex-col gap-3">
          {groups.map(([groupCode, groupRows_]) => (
            <section key={groupCode} aria-label={`Група ${groupCode}`} className="rounded-panel border border-default bg-surface">
              <header className="flex items-center gap-2 border-b border-separator px-3 py-2">
                <Plate>{groupCode}</Plate>
                <span className="text-caption text-muted">змін: {groupRows_.length}</span>
              </header>
              <ul className="divide-y divide-[var(--border-color-separator)]">
                {groupRows_.map((row) => (
                  <DiffRowItem key={row.key} row={row} onShowInGrid={onShowInGrid} />
                ))}
              </ul>
            </section>
          ))}
          {visible.length > limit ? (
            <Button variant="ghost" onClick={() => setLimit((value) => value + PAGE)}>
              Показати ще {Math.min(PAGE, visible.length - limit)} з {visible.length - limit}
            </Button>
          ) : null}
        </div>
      )}
    </section>
  );
}

function groupRows(rows: readonly DiffRow[]): Array<[string, DiffRow[]]> {
  const map = new Map<string, DiffRow[]>();
  for (const row of rows) map.set(row.groupCode, [...(map.get(row.groupCode) ?? []), row]);
  return [...map];
}

function DiffRowItem({ row, onShowInGrid }: { row: DiffRow; onShowInGrid: (lesson: TemplateLessonDto) => void }) {
  const meta = KIND_META[row.kind];
  const lesson = (row.after ?? row.before) as TemplateLessonDto;
  return (
    <li className="flex flex-wrap items-center gap-x-4 gap-y-1 px-3 py-2">
      <span className={cn('inline-flex w-28 shrink-0 items-center gap-1.5 text-caption font-semibold', meta.tone)}>
        {meta.icon}
        {meta.label}
      </span>
      <span className={cn('min-w-0 flex-1 text-ui text-primary', row.kind === 'removed' && 'text-secondary line-through decoration-[var(--text-decoration-color-cancel)]')}>
        {who(lesson)}
      </span>
      <span className="text-ui text-secondary figures">
        {row.kind === 'moved' && row.before && row.after ? (
          <>
            <s className="text-muted decoration-[var(--text-decoration-color-muted)]">{where(row.before)}</s>
            <ArrowRight aria-label="на" className="mx-1.5 inline size-3.5 text-change" />
            <span className="font-semibold text-primary">{where(row.after)}</span>
          </>
        ) : (
          where(lesson)
        )}
      </span>
      {row.target ? (
        <IconButton
          label="Показати в сітці"
          size="sm"
          icon={<Crosshair aria-hidden="true" className="size-4" />}
          onClick={() => row.target && onShowInGrid(row.target)}
        />
      ) : (
        <span className="size-8" aria-hidden="true" />
      )}
    </li>
  );
}
