import type { LoadItem } from '@/api/load';
import type { GroupTheoryDto, ShiftWindowDto } from '@/api/types';
import { GroupTheoryNote } from '@/components/accounting/GroupTheoryNote';
import { PLAN_RULE } from '@/components/accounting/theory';
import { useTeacherLabel } from '@/components/admin-daily/useTeacherLabel';
import { countLabel } from '@/lib/labels';
import { formatHours, pairsPerWeek } from './pattern';
import { teacherLoadSummary } from './summary';

function groupSummary(items: readonly LoadItem[]) {
  return {
    hours: items.reduce((sum, item) => sum + item.hoursPerWeek, 0),
    pairs: items.reduce((sum, item) => sum + pairsPerWeek(item.pattern), 0),
    planned: items.reduce((sum, item) => sum + item.plannedHours, 0),
    unassigned: items.filter((item) => item.teachers.length === 0).length,
  };
}

/** Скільки пар на тиждень вміщує сітка зміни: 1 зміна — 4 × 5, 2 зміна — 4 × 4 + 3. */
function weeklyCapacity(windows: ReadonlyArray<{ shift: number; firstPair: number; lastPair: number }>, shift: number): number | null {
  const own = windows.filter((w) => w.shift === shift);
  if (own.length === 0) return null;
  return own.reduce((sum, w) => sum + (w.lastPair - w.firstPair + 1), 0);
}

interface GroupWeekSummaryProps {
  group: { code: string; shift: number };
  items: readonly LoadItem[];
  shiftWindows: readonly ShiftWindowDto[] | undefined;
  groupTheory: GroupTheoryDto | undefined;
}

/** Тиждень обраної групи: години й пари проти місць у сітці зміни, план за семестр і дисципліни без викладача. */
export function GroupWeekSummary({ group, items, shiftWindows, groupTheory }: GroupWeekSummaryProps) {
  const summary = groupSummary(items);
  const capacity = weeklyCapacity(shiftWindows ?? [], group.shift);
  return (
    <section aria-label={`Тиждень групи ${group.code}`} className="flex max-w-3xl flex-col gap-2">
      <p className="text-body text-secondary">
        <span className="font-[650] text-primary">{group.code}</span>, {group.shift} зміна: {formatHours(summary.hours)} год на тиждень, у середньому{' '}
        {formatHours(summary.pairs)} {capacity ? `пар із ${capacity} місць у сітці` : 'пар'}; за семестр {summary.planned} год.
        {summary.unassigned > 0 ? (
          <span className="text-change"> {countLabel(summary.unassigned, ['дисципліна', 'дисципліни', 'дисциплін'])} без викладача.</span>
        ) : null}
      </p>
      {capacity ? (
        <span
          role="meter"
          aria-label="Заповнення тижня групи"
          aria-valuemin={0}
          aria-valuemax={capacity}
          aria-valuenow={summary.pairs}
          className="relative h-1.5 w-full max-w-sm overflow-hidden rounded-full bg-[var(--fill-track-future)]"
        >
          <span
            className={`absolute inset-y-0 start-0 rounded-full ${summary.pairs > capacity ? 'bg-[var(--fill-cancel)]' : 'bg-accent'}`}
            style={{ width: `${Math.min(100, (summary.pairs / capacity) * 100)}%` }}
          />
        </span>
      ) : null}
      {/* план «За семестр» — лише за тижні теорії групи: чому числа саме такі */}
      {groupTheory ? <GroupTheoryNote theory={groupTheory} className="mt-1" /> : null}
      <p className="text-caption text-muted">{PLAN_RULE}</p>
    </section>
  );
}

/** Пошук привів до рядків одного викладача — його тиждень і семестр, як у групи. */
export function TeacherLoadSummary({ rows }: { rows: readonly LoadItem[] }) {
  const name = useTeacherLabel();
  const teacherSummary = teacherLoadSummary(rows);
  if (!teacherSummary) return null;
  return (
    <p aria-label={`Навантаження: ${name(teacherSummary.teacher)}`} className="max-w-3xl text-body text-secondary">
      <span className="font-[650] text-primary">{name(teacherSummary.teacher)}</span>: {formatHours(teacherSummary.hours)} год на тиждень, за
      семестр {formatHours(teacherSummary.planned)} год — {countLabel(teacherSummary.rows, ['рядок', 'рядки', 'рядків'])} у{' '}
      {countLabel(teacherSummary.groups, ['групі', 'групах', 'групах'])}.
    </p>
  );
}
