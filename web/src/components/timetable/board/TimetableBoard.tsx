import {
  closestCenter,
  DndContext,
  DragOverlay,
  KeyboardSensor,
  MeasuringStrategy,
  PointerSensor,
  pointerWithin,
  useSensor,
  useSensors,
  type Announcements,
  type CollisionDetection,
  type DragEndEvent,
  type DragOverEvent,
  type DragStartEvent,
} from '@dnd-kit/core';
import { defaultRangeExtractor, useWindowVirtualizer, type Range } from '@tanstack/react-virtual';
import { useCallback, useEffect, useEffectEvent, useMemo, useRef, useState } from 'react';
import { errorMessage } from '@/api/errors';
import { previewMove, type TimetableConflict } from '@/api/timetable';
import type { MoveLessonRequest, Parity, PublicRoomDto, ShiftWindowDto, TemplateLessonDto, Uuid, Weekday } from '@/api/types';
import { roomLabel } from '@/lib/schedule';
import {
  acceptsLesson,
  dropTargetId,
  entityIdOf,
  isOrigin,
  moveRequestFor,
  pairRows,
  parseDropTargetId,
  targetParities,
  WEEKDAYS,
  type BoardEntity,
  type BoardView,
  type DropTarget,
} from '../model/grid';
import { SlotIndex, withStreamMates } from '../model/occupancy';
import { slotLong } from '../model/text';
import { createDragStore, setCheck, useDragState, type TargetCheck } from './dragStore';
import { DropPreviewCard } from './DropPreviewCard';
import { EntityGrid, type CellFocus } from './EntityGrid';
import { gridKeyboardCoordinates } from './keyboard';
import { LessonChipOverlay } from './LessonChip';
import { cardPositionFor, type CardPosition } from './position';

/** Запит «показати»: клітинка сутності або лише пара (сутність і клітинку знайдемо самі). */
export interface BoardFocus extends Omit<CellFocus, 'entityId'> {
  entityId: Uuid | null;
  lessonId: Uuid | null;
}

function resolveFocus(focus: BoardFocus | null, entities: readonly BoardEntity[]): (BoardFocus & CellFocus) | null {
  if (!focus) return null;
  if (focus.entityId) return { ...focus, entityId: focus.entityId };
  for (const entity of entities) {
    const lesson = entity.lessons.find((l) => l.id === focus.lessonId);
    if (lesson) return { ...focus, entityId: entity.id, weekday: lesson.weekday, pair: lesson.pair };
  }
  return null;
}

export interface TimetableBoardProps {
  versionId: Uuid;
  view: BoardView;
  entities: readonly BoardEntity[];
  /** Усі заняття версії — для попередньої оцінки цілей. */
  lessons: readonly TemplateLessonDto[];
  windows: readonly ShiftWindowDto[];
  rooms: ReadonlyMap<Uuid, PublicRoomDto>;
  groupShift: ReadonlyMap<Uuid, 1 | 2>;
  editable: boolean;
  conflictsByLesson: ReadonlyMap<Uuid, TimetableConflict[]>;
  selectedLessonId: Uuid | null;
  focus: BoardFocus | null;
  onSelect: (lesson: TemplateLessonDto) => void;
  onOpen: (lesson: TemplateLessonDto) => void;
  onAdd: (entity: BoardEntity, weekday: Weekday, pair: number, parity: Parity) => void;
  /** Переміщення після кидка: сторінка робить оптимістичну правку й історію. */
  onMove: (lesson: TemplateLessonDto, target: MoveLessonRequest, room: PublicRoomDto | null) => void;
  /** Кидок на ціль, яку сервер уже назвав конфліктною: нічого не надсилаємо, лише пояснюємо. */
  onRejected: (lesson: TemplateLessonDto, target: DropTarget, conflicts: readonly TimetableConflict[]) => void;
}

const MIN_GRID_WIDTH = 600;
const ROW_GAP = 16;
const PREVIEW_DEBOUNCE_MS = 120;

/** Мишею — ціль під курсором; з клавіатури курсора немає — найближча до «несеної» пари. */
const collisionDetection: CollisionDetection = (args) => (args.pointerCoordinates ? pointerWithin(args) : closestCenter(args));

const lessonText = (lesson: TemplateLessonDto) => `${lesson.discipline.shortName}, ${lesson.group.code}, ${slotLong(lesson)}`;

function chunk<T>(items: readonly T[], size: number): T[][] {
  const rows: T[][] = [];
  for (let i = 0; i < items.length; i += size) rows.push(items.slice(i, i + size));
  return rows;
}

/**
 * Дошка редактора: сітки груп / викладачів / аудиторій одна під одною (на широкому екрані — по дві),
 * віртуалізовані (56 груп — це тисячі елементів). Перетягування dnd-kit мишею і з клавіатури: на старті
 * всі цілі отримують попередню оцінку в браузері, ціль під курсором — точну відповідь сервера (dryRun).
 */
export function TimetableBoard({
  versionId,
  view,
  entities,
  lessons,
  windows,
  rooms,
  groupShift,
  editable,
  conflictsByLesson,
  selectedLessonId,
  focus,
  onSelect,
  onOpen,
  onAdd,
  onMove,
  onRejected,
}: TimetableBoardProps) {
  const [store] = useState(createDragStore);
  const active = useDragState(store, (state) => state.active);
  const overId = useDragState(store, (state) => state.overId);
  const overCheck = useDragState(store, (state) => (state.overId ? state.checks.get(state.overId) : undefined));
  const [cardPosition, setCardPosition] = useState<CardPosition | null>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const [columns, setColumns] = useState(1);
  const [scrollMargin, setScrollMargin] = useState(0);
  const previewTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const previewAbort = useRef<AbortController | null>(null);
  const landedTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const index = useMemo(() => new SlotIndex(lessons), [lessons]);

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 6 } }),
    useSensor(KeyboardSensor, { coordinateGetter: gridKeyboardCoordinates }),
  );

  // ширина дошки → скільки сіток у ряду; відступ дошки від верху документа → для віртуалізатора вікна
  // (стежимо й за body: панелі над дошкою змінюють висоту, коли дані довантажуються)
  useEffect(() => {
    const element = listRef.current;
    if (!element) return;
    const measure = () => {
      const width = element.clientWidth;
      setColumns(Math.max(1, Math.min(3, Math.floor((width + ROW_GAP) / (MIN_GRID_WIDTH + ROW_GAP)))));
      setScrollMargin(Math.round(element.getBoundingClientRect().top + window.scrollY));
    };
    const observer = new ResizeObserver(measure);
    observer.observe(element);
    observer.observe(document.body);
    return () => observer.disconnect();
  }, []);

  useEffect(() => () => clearTimeout(landedTimer.current), []);

  const rows = useMemo(() => chunk(entities, columns), [entities, columns]);
  const activeRow = useMemo(() => {
    if (!active) return null;
    const ownerId = entityIdOf(view, active);
    const position = rows.findIndex((row) => row.some((entity) => entity.id === ownerId));
    return position >= 0 ? position : null;
  }, [active, rows, view]);

  // рядок із «несеною» парою не прибираємо з DOM, навіть якщо він виїхав за екран
  const rangeExtractor = useCallback(
    (range: Range) => {
      const visible = defaultRangeExtractor(range);
      return activeRow !== null && !visible.includes(activeRow) ? [...visible, activeRow].sort((a, b) => a - b) : visible;
    },
    [activeRow],
  );

  const virtualizer = useWindowVirtualizer({
    count: rows.length,
    estimateSize: (rowIndex) => {
      const tallest = Math.max(...(rows[rowIndex] ?? []).map((entity) => pairRows(entity, windows).length), 4);
      return 82 + tallest * 66 + ROW_GAP;
    },
    overscan: 2,
    scrollMargin,
    rangeExtractor,
  });

  const resolvedFocus = useMemo(() => resolveFocus(focus, entities), [focus, entities]);

  // запит на фокус (конфлікт, порівняння): прокрутити до сутності, потім сфокусувати пару.
  // Перезапускається лише новим запитом, а не кожною зміною рядків.
  const scrollToFocus = useEffectEvent((raw: BoardFocus) => {
    const request = resolveFocus(raw, entities);
    if (!request) return undefined;
    const rowIndex = rows.findIndex((row) => row.some((entity) => entity.id === request.entityId));
    if (rowIndex < 0) return undefined;
    virtualizer.scrollToIndex(rowIndex, { align: 'center' });
    let attempts = 0;
    let frame = 0;
    const tryFocus = () => {
      const selector = request.lessonId
        ? `[data-lesson-id="${request.lessonId}"]`
        : `[data-cell="${request.entityId}|${request.weekday}|${request.pair}"] [data-roving]`;
      const element = listRef.current?.querySelector<HTMLElement>(selector);
      if (element) {
        element.focus({ preventScroll: true });
        return;
      }
      attempts += 1;
      if (attempts < 20) frame = requestAnimationFrame(tryFocus);
    };
    frame = requestAnimationFrame(tryFocus);
    return () => cancelAnimationFrame(frame);
  });

  useEffect(() => (focus ? scrollToFocus(focus) : undefined), [focus]);

  /** Попередня оцінка всіх цілей у сітках, що приймають пару (у браузері, без сервера). */
  const estimateTargets = useCallback(
    (lesson: TemplateLessonDto) => {
      const checks = new Map<string, TargetCheck>();
      const ignoreIds = withStreamMates(lesson, lessons);
      for (const entity of entities) {
        if (!acceptsLesson(view, entity.id, lesson)) continue;
        const roomId = view === 'room' ? entity.id : (lesson.room?.id ?? null);
        for (const pair of pairRows(entity, windows)) {
          for (const weekday of WEEKDAYS) {
            for (const parity of targetParities(lesson.parity)) {
              const target: DropTarget = { view, entityId: entity.id, weekday, pair, parity };
              const issues = index.check(
                {
                  weekday,
                  pair,
                  parity,
                  groupId: lesson.group.id,
                  subgroup: lesson.subgroup,
                  shift: groupShift.get(lesson.group.id) ?? null,
                  teacherId: lesson.teacher?.id ?? null,
                  roomId,
                  roomType: roomId ? (rooms.get(roomId)?.type ?? null) : null,
                  ignoreIds,
                  streamId: lesson.streamId ?? null,
                },
                windows,
              );
              checks.set(dropTargetId(target), {
                source: 'estimate',
                status: issues.length > 0 ? 'busy' : 'free',
                messages: issues.map((issue) => issue.message),
              });
            }
          }
        }
      }
      return checks;
    },
    [entities, groupShift, index, lessons, rooms, view, windows],
  );

  const cancelPreview = () => {
    if (previewTimer.current) clearTimeout(previewTimer.current);
    previewTimer.current = null;
    previewAbort.current?.abort();
    previewAbort.current = null;
  };

  const requestPreview = (lesson: TemplateLessonDto, target: DropTarget) => {
    const id = dropTargetId(target);
    const known = store.getState().checks.get(id);
    if (known?.source === 'server' && known.status !== 'error') return;
    cancelPreview();
    previewTimer.current = setTimeout(() => {
      const controller = new AbortController();
      previewAbort.current = controller;
      setCheck(store, id, { source: 'server', status: 'loading' });
      previewMove(versionId, lesson.id, moveRequestFor(lesson, target), controller.signal)
        .then((preview) => setCheck(store, id, { source: 'server', ...preview }))
        .catch((error: unknown) => {
          if (controller.signal.aborted) {
            // повертаємо попередню оцінку, щоб ціль не лишилась «у перевірці»
            if (known) setCheck(store, id, known);
            return;
          }
          setCheck(store, id, { source: 'server', status: 'error', message: errorMessage(error) });
        });
    }, PREVIEW_DEBOUNCE_MS);
  };

  const finishDrag = () => {
    cancelPreview();
    setCardPosition(null);
    store.setState({ active: null, activeIds: new Set(), overId: null, checks: new Map() });
  };

  const onDragStart = ({ active: dragged }: DragStartEvent) => {
    const lesson = (dragged.data.current as { lesson?: TemplateLessonDto } | undefined)?.lesson;
    if (!lesson) return;
    store.setState({
      active: lesson,
      activeIds: withStreamMates(lesson, lessons),
      overId: null,
      checks: estimateTargets(lesson),
      landedId: null,
    });
  };

  const onDragOver = ({ over }: DragOverEvent) => {
    const lesson = store.getState().active;
    const target = over ? parseDropTargetId(String(over.id)) : null;
    store.setState({ overId: target ? String(over?.id) : null });
    if (!lesson || !target) {
      cancelPreview();
      setCardPosition(null);
      return;
    }
    const origin = isOrigin(lesson, target);
    setCardPosition(origin ? null : cardPositionFor(target));
    if (!origin) requestPreview(lesson, target);
  };

  const onDragEnd = ({ over }: DragEndEvent) => {
    const lesson = store.getState().active;
    const target = over ? parseDropTargetId(String(over.id)) : null;
    const check = target ? store.getState().checks.get(dropTargetId(target)) : undefined;
    finishDrag();
    if (!lesson || !target || isOrigin(lesson, target)) return;
    if (check?.source === 'server' && check.status === 'blocked') {
      onRejected(lesson, target, check.conflicts);
      return;
    }
    const body = moveRequestFor(lesson, target);
    onMove(lesson, body, body.roomId ? (rooms.get(body.roomId) ?? null) : null);
    store.setState({ landedId: lesson.id });
    clearTimeout(landedTimer.current);
    landedTimer.current = setTimeout(() => store.setState({ landedId: null }), 1600);
    // фокус — на пару в новій клітинці (вона перемальовується в іншому місці)
    requestAnimationFrame(() => listRef.current?.querySelector<HTMLElement>(`[data-lesson-id="${lesson.id}"]`)?.focus({ preventScroll: true }));
  };

  const announcements: Announcements = {
    onDragStart: () => {
      const lesson = store.getState().active;
      return lesson ? `Підхоплено пару: ${lessonText(lesson)}. Стрілками оберіть слот, Пробіл або Enter — поставити, Escape — скасувати.` : undefined;
    },
    onDragOver: ({ over }) => {
      const target = over ? parseDropTargetId(String(over.id)) : null;
      if (!target) return 'Поза сіткою: тут пару поставити не можна.';
      const check = store.getState().checks.get(String(over?.id));
      const hint = check?.source === 'estimate' && check.status === 'busy' ? ` Схоже, зайнято: ${check.messages.join('; ')}.` : '';
      return `Слот: ${slotLong(target)}.${hint}`;
    },
    onDragEnd: ({ over }) => {
      const target = over ? parseDropTargetId(String(over.id)) : null;
      return target ? `Пару відпущено над слотом: ${slotLong(target)}.` : 'Пару відпущено поза сіткою, вона лишилась на місці.';
    },
    onDragCancel: () => 'Перенесення скасовано, пара лишилась на місці.',
  };

  const overTarget = overId ? parseDropTargetId(overId) : null;
  const roomChange =
    active && overTarget && overTarget.view === 'room' && overTarget.entityId !== active.room?.id
      ? (roomLabel(rooms.get(overTarget.entityId)?.name) ?? null)
      : null;

  return (
    <DndContext
      sensors={sensors}
      collisionDetection={collisionDetection}
      measuring={{ droppable: { strategy: MeasuringStrategy.Always } }}
      // сторінка довга, а ціль зазвичай поруч: прокручуємо лише біля самого краю вікна
      autoScroll={{ threshold: { x: 0, y: 0.08 }, acceleration: 6 }}
      accessibility={{
        announcements,
        screenReaderInstructions: {
          draggable:
            'Щоб перенести пару, натисніть Пробіл або Enter, стрілками оберіть новий слот і натисніть Пробіл або Enter ще раз. Escape — скасувати. F2 — редагувати.',
        },
      }}
      onDragStart={onDragStart}
      onDragOver={onDragOver}
      onDragEnd={onDragEnd}
      onDragCancel={finishDrag}
    >
      <div ref={listRef} className="relative w-full" style={{ height: virtualizer.getTotalSize() }}>
        {virtualizer.getVirtualItems().map((item) => {
          const row = rows[item.index] ?? [];
          return (
            <div
              key={item.key}
              data-index={item.index}
              ref={virtualizer.measureElement}
              className="absolute inset-x-0 top-0"
              style={{ transform: `translateY(${item.start - scrollMargin}px)`, paddingBottom: ROW_GAP }}
            >
              <div className="grid gap-4" style={{ gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))` }}>
                {row.map((entity) => (
                  <EntityGrid
                    key={entity.id}
                    entity={entity}
                    windows={windows}
                    editable={editable}
                    conflictsByLesson={conflictsByLesson}
                    selectedLessonId={selectedLessonId}
                    store={store}
                    focus={resolvedFocus}
                    onSelect={onSelect}
                    onOpen={onOpen}
                    onAdd={onAdd}
                  />
                ))}
              </div>
            </div>
          );
        })}
      </div>

      <DragOverlay dropAnimation={null}>{active ? <LessonChipOverlay lesson={active} view={view} /> : null}</DragOverlay>
      {active && overTarget && cardPosition ? (
        <DropPreviewCard target={overTarget} check={overCheck} position={cardPosition} roomChange={roomChange} />
      ) : null}
    </DndContext>
  );
}
