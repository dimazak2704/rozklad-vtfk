import { Plus, TriangleExclamationPoint } from 'lucide-react';
import { memo, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import type { TimetableConflict } from '@/api/timetable';
import type { Parity, ShiftWindowDto, TemplateLessonDto, Uuid, Weekday } from '@/api/types';
import { Plate } from '@/components/ui';
import { cn } from '@/lib/cn';
import { capitalize, countLabel, PAIR_FORMS, weekdayName, weekdayShort } from '@/lib/labels';
import {
  acceptsLesson,
  inShiftWindow,
  isOrigin,
  layoutCell,
  pairRows,
  targetParities,
  WEEKDAYS,
  type BoardEntity,
  type DropTarget,
} from '../model/grid';
import { PARITY_SHORT } from '../model/labels';
import { DropZone } from './DropZone';
import { useDragState, type DragStore } from './dragStore';
import { moveRovingFocus } from './keyboard';
import { LessonChip } from './LessonChip';

export interface CellFocus {
  entityId: Uuid;
  weekday: number;
  pair: number;
  /** Щоб повторне натискання того самого конфлікту знову підсвітило клітинку. */
  nonce: number;
}

export interface EntityGridProps {
  entity: BoardEntity;
  windows: readonly ShiftWindowDto[];
  editable: boolean;
  conflictsByLesson: ReadonlyMap<Uuid, TimetableConflict[]>;
  selectedLessonId: Uuid | null;
  store: DragStore;
  focus: CellFocus | null;
  onSelect: (lesson: TemplateLessonDto) => void;
  onOpen: (lesson: TemplateLessonDto) => void;
  onAdd: (entity: BoardEntity, weekday: Weekday, pair: number, parity: Parity) => void;
}

const NO_CONFLICTS: TimetableConflict[] = [];

const ENTITY_KIND: Record<BoardEntity['view'], string> = { group: 'групи', teacher: 'викладача', room: 'аудиторії' };

/**
 * Тиждень однієї групи, викладача чи аудиторії: дні — стовпці, пари — рядки, кожна клітинка поділена
 * на чисельник (зверху) і знаменник (знизу), у групи ще й на доріжки підгруп. Стрілки ходять між парами.
 */
export const EntityGrid = memo(function EntityGrid({
  entity,
  windows,
  editable,
  conflictsByLesson,
  selectedLessonId,
  store,
  focus,
  onSelect,
  onOpen,
  onAdd,
}: EntityGridProps) {
  const gridRef = useRef<HTMLDivElement>(null);
  const [activeKey, setActiveKey] = useState<string | null>(null);
  const active = useDragState(store, (state) => state.active);
  const accepting = active !== null && acceptsLesson(entity.view, entity.id, active);
  const rows = useMemo(() => pairRows(entity, windows), [entity, windows]);
  const byCell = useMemo(() => {
    const map = new Map<string, TemplateLessonDto[]>();
    for (const lesson of entity.lessons) {
      const key = `${lesson.weekday}|${lesson.pair}`;
      map.set(key, [...(map.get(key) ?? []), lesson]);
    }
    return map;
  }, [entity.lessons]);
  const hardCount = useMemo(
    () => entity.lessons.filter((l) => (conflictsByLesson.get(l.id) ?? NO_CONFLICTS).some((c) => c.severity === 'HARD')).length,
    [entity.lessons, conflictsByLesson],
  );

  // перший елемент для Tab, якщо фокус у сітці ще не бував
  const firstKey = useMemo(() => {
    for (const pair of rows) {
      for (const weekday of WEEKDAYS) {
        const lessons = byCell.get(`${weekday}|${pair}`);
        if (lessons?.[0]) return `lesson:${layoutCell(lessons, entity.view, entity.lanes).items[0]?.lesson.id ?? lessons[0].id}`;
        if (editable && inShiftWindow(entity, windows, weekday, pair)) return `add:${weekday}:${pair}:BOTH`;
      }
    }
    return null;
  }, [rows, byCell, entity, editable, windows]);
  const tabKey = activeKey ?? firstKey;

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (active || !gridRef.current) return;
    const target = event.target as HTMLElement;
    if (!target.hasAttribute('data-roving')) return;
    if (moveRovingFocus(gridRef.current, target, event.key)) event.preventDefault();
  };

  const titleId = `grid-${entity.view}-${entity.id}`;
  const lessonCount = entity.lessons.length;

  return (
    <section aria-labelledby={titleId} className="min-w-0 rounded-panel border border-default bg-surface">
      <header className="flex flex-wrap items-center gap-x-3 gap-y-1 border-b border-separator px-3 py-2">
        <h3 id={titleId} className="flex min-w-0 items-center gap-2">
          <span className="sr-only">Розклад {ENTITY_KIND[entity.view]} </span>
          <Plate size="md">{entity.label}</Plate>
          {entity.sublabel ? <span className="truncate text-caption font-normal text-secondary">{entity.sublabel}</span> : null}
        </h3>
        <span className="ms-auto flex shrink-0 items-center gap-3 text-meta text-muted figures">
          <span>{countLabel(lessonCount, PAIR_FORMS)} у шаблоні</span>
          {hardCount > 0 ? (
            <span className="inline-flex items-center gap-1 font-semibold text-danger">
              <TriangleExclamationPoint aria-hidden="true" className="size-3.5" />
              {hardCount} з конфліктом
            </span>
          ) : null}
        </span>
      </header>

      <div
        ref={gridRef}
        role="grid"
        aria-labelledby={titleId}
        aria-rowcount={rows.length + 1}
        onKeyDown={onKeyDown}
        className="grid grid-cols-[2.75rem_repeat(5,minmax(0,1fr))]"
      >
        <div role="row" className="contents">
          <div role="columnheader" className="border-e border-b border-separator bg-sunken px-1 py-1.5 text-center text-meta font-semibold text-muted">
            Пара
          </div>
          {WEEKDAYS.map((weekday) => (
            <div
              key={weekday}
              role="columnheader"
              className="border-b border-separator bg-sunken px-2 py-1.5 text-caption font-semibold text-secondary not-last:border-e"
            >
              <span className="hidden 2xl:inline">{capitalize(weekdayName(weekday))}</span>
              <span className="2xl:hidden" aria-label={weekdayName(weekday)}>
                {weekdayShort(weekday)}
              </span>
            </div>
          ))}
        </div>

        {rows.map((pair, rowIndex) => (
          <div key={pair} role="row" aria-rowindex={rowIndex + 2} className="contents">
            <div
              role="rowheader"
              className={cn(
                'relative flex flex-col items-center justify-center border-e border-separator bg-sunken',
                rowIndex < rows.length - 1 && 'border-b',
              )}
            >
              <span className="font-condensed text-title leading-none font-bold text-primary figures">{pair}</span>
              <span className="sr-only"> пара</span>
              {/* верхня половина клітинки — чисельник, нижня — знаменник, як у друкованому розкладі */}
              <span aria-hidden="true" className="absolute end-1 top-1/4 -translate-y-1/2 text-[0.625rem] leading-none font-semibold text-muted">
                ч
              </span>
              <span aria-hidden="true" className="absolute end-1 top-3/4 -translate-y-1/2 text-[0.625rem] leading-none font-semibold text-muted">
                з
              </span>
            </div>
            {WEEKDAYS.map((weekday) => (
              <SlotCell
                key={weekday}
                entity={entity}
                weekday={weekday}
                pair={pair}
                lastRow={rowIndex === rows.length - 1}
                lastColumn={weekday === 5}
                lessons={byCell.get(`${weekday}|${pair}`) ?? []}
                inWindow={inShiftWindow(entity, windows, weekday, pair)}
                editable={editable}
                accepting={accepting}
                active={active}
                conflictsByLesson={conflictsByLesson}
                selectedLessonId={selectedLessonId}
                store={store}
                tabKey={tabKey}
                flash={focus !== null && focus.entityId === entity.id && focus.weekday === weekday && focus.pair === pair ? focus.nonce : null}
                onSelect={onSelect}
                onOpen={onOpen}
                onAdd={onAdd}
                onFocusItem={setActiveKey}
              />
            ))}
          </div>
        ))}
      </div>
    </section>
  );
});

interface SlotCellProps {
  entity: BoardEntity;
  weekday: Weekday;
  pair: number;
  lastRow: boolean;
  lastColumn: boolean;
  lessons: TemplateLessonDto[];
  inWindow: boolean;
  editable: boolean;
  accepting: boolean;
  active: TemplateLessonDto | null;
  conflictsByLesson: ReadonlyMap<Uuid, TimetableConflict[]>;
  selectedLessonId: Uuid | null;
  store: DragStore;
  tabKey: string | null;
  flash: number | null;
  onSelect: (lesson: TemplateLessonDto) => void;
  onOpen: (lesson: TemplateLessonDto) => void;
  onAdd: (entity: BoardEntity, weekday: Weekday, pair: number, parity: Parity) => void;
  onFocusItem: (key: string) => void;
}

/** Порожні частини клітинки, куди можна додати пару: уся клітинка або вільна половина. */
function emptyParts(lessons: readonly TemplateLessonDto[]): Parity[] {
  if (lessons.length === 0) return ['BOTH'];
  if (lessons.some((l) => l.parity === 'BOTH')) return [];
  const parts: Parity[] = [];
  if (!lessons.some((l) => l.parity === 'NUMERATOR')) parts.push('NUMERATOR');
  if (!lessons.some((l) => l.parity === 'DENOMINATOR')) parts.push('DENOMINATOR');
  return parts;
}

const SlotCell = memo(function SlotCell({
  entity,
  weekday,
  pair,
  lastRow,
  lastColumn,
  lessons,
  inWindow,
  editable,
  accepting,
  active,
  conflictsByLesson,
  selectedLessonId,
  store,
  tabKey,
  flash,
  onSelect,
  onOpen,
  onAdd,
  onFocusItem,
}: SlotCellProps) {
  const layout = useMemo(() => layoutCell(lessons, entity.view, entity.view === 'group' ? entity.lanes : 1), [lessons, entity.view, entity.lanes]);
  const canAdd = editable && inWindow;
  const addParts = canAdd ? emptyParts(lessons) : [];
  const slotText = `${weekdayName(weekday)}, ${pair} пара`;

  return (
    <div
      role="gridcell"
      aria-label={inWindow ? undefined : `${slotText}: поза вікном зміни`}
      data-cell={`${entity.id}|${weekday}|${pair}`}
      className={cn(
        'relative grid min-h-16 min-w-0 grid-rows-2 gap-0.5 p-0.5',
        !lastRow && 'border-b border-separator',
        !lastColumn && 'border-e border-separator',
        inWindow ? 'bg-surface' : 'hatch bg-block-subtle',
        // ледь помітна межа між чисельником і знаменником
        'before:pointer-events-none before:absolute before:inset-x-1.5 before:top-1/2 before:border-t before:border-dashed before:border-separator',
      )}
      style={{ gridTemplateColumns: `repeat(${layout.lanes}, minmax(0, 1fr))` }}
    >
      {flash !== null ? (
        // новий key щоразу перезапускає підсвітку, навіть для тієї самої клітинки
        <span
          key={flash}
          aria-hidden="true"
          className="pointer-events-none absolute inset-0 z-10 rounded-plate bg-selected-hover/70 outline-2 outline-offset-[-2px] outline-[var(--border-color-accent)] motion-safe:animate-[fade-out_2.2s_var(--ease-in)_forwards]"
        />
      ) : null}
      {layout.items.map((item) => (
        <LessonChip
          key={item.lesson.id}
          lesson={item.lesson}
          view={entity.view}
          compact={item.rowSpan === 1}
          narrow={layout.lanes > 1 && item.laneSpan < layout.lanes}
          conflicts={conflictsByLesson.get(item.lesson.id) ?? NO_CONFLICTS}
          selected={item.lesson.id === selectedLessonId}
          editable={editable}
          store={store}
          tabIndex={tabKey === `lesson:${item.lesson.id}` ? 0 : -1}
          style={{
            gridRow: `${item.rowStart} / span ${item.rowSpan}`,
            gridColumn: `${item.laneStart} / span ${item.laneSpan}`,
          }}
          onSelect={onSelect}
          onOpen={onOpen}
          onFocusItem={onFocusItem}
        />
      ))}

      {addParts.map((parity) => {
        const key = `add:${weekday}:${pair}:${parity}`;
        return (
          <button
            key={parity}
            type="button"
            data-roving=""
            tabIndex={tabKey === key ? 0 : -1}
            aria-label={`Додати пару: ${slotText}${parity === 'BOTH' ? '' : `, ${PARITY_SHORT[parity]}`}`}
            onFocus={() => onFocusItem(key)}
            onClick={() => onAdd(entity, weekday, pair, parity)}
            style={{ gridRow: parity === 'BOTH' ? '1 / span 2' : parity === 'NUMERATOR' ? '1' : '2', gridColumn: '1 / -1' }}
            className={cn(
              'group/add relative z-[1] flex min-h-0 items-center justify-center rounded-plate text-transparent',
              'transition-[background-color,color] duration-100 ease-out hover:bg-hover hover:text-muted',
              'focus-visible:text-accent focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus',
            )}
          >
            <Plus aria-hidden="true" className="size-4" />
          </button>
        );
      })}

      {accepting && active
        ? targetParities(active.parity).map((parity) => {
            const target: DropTarget = { view: entity.view, entityId: entity.id, weekday, pair, parity };
            return (
              <DropZone
                key={parity}
                target={target}
                isOrigin={isOrigin(active, target)}
                occupied={lessons.some((l) => l.id !== active.id && (parity === 'BOTH' || l.parity === 'BOTH' || l.parity === parity))}
                store={store}
              />
            );
          })
        : null}
    </div>
  );
});
