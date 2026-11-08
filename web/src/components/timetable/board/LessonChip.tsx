import { useDraggable } from '@dnd-kit/core';
import { Layers, MonitorSmartphone, Pin, TriangleExclamationPoint } from 'lucide-react';
import { memo, useRef, useState, type CSSProperties, type KeyboardEvent } from 'react';
import type { TimetableConflict } from '@/api/timetable';
import type { TemplateLessonDto } from '@/api/types';
import { HoverCard, HoverCardContent, HoverCardTrigger } from '@/components/ui';
import { cn } from '@/lib/cn';
import { lineAttr } from '@/lib/discipline-color';
import { weekdayName } from '@/lib/labels';
import type { BoardView } from '../model/grid';
import { PARITY_SHORT } from '../model/labels';
import { chipText, lessonAriaLabel } from '../model/text';
import { useDragState, type DragStore } from './dragStore';
import { LessonDetailsCard } from './LessonDetailsCard';

export interface LessonChipProps {
  lesson: TemplateLessonDto;
  view: BoardView;
  /** Половинка пари (чисельник чи знаменник) — один рядок тексту. */
  compact: boolean;
  /** Вузька доріжка підгрупи — лише назва й аудиторія. */
  narrow: boolean;
  conflicts: readonly TimetableConflict[];
  selected: boolean;
  editable: boolean;
  store: DragStore;
  tabIndex: number;
  style?: CSSProperties;
  onSelect: (lesson: TemplateLessonDto) => void;
  onOpen: (lesson: TemplateLessonDto) => void;
  onFocusItem: (key: string) => void;
}

/**
 * Пара в сітці редактора: тягнеться мишею (після зсуву на 6 px) або з клавіатури (Пробіл/Enter, стрілки,
 * Пробіл/Enter). Клік — вибрати, подвійний клік — редагувати. Наведення показує повні відомості.
 */
export const LessonChip = memo(function LessonChip({
  lesson,
  view,
  compact,
  narrow,
  conflicts,
  selected,
  editable,
  store,
  tabIndex,
  style,
  onSelect,
  onOpen,
  onFocusItem,
}: LessonChipProps) {
  const { attributes, listeners, setNodeRef } = useDraggable({
    id: lesson.id,
    data: { lesson },
    disabled: !editable,
    attributes: { roleDescription: 'пара, яку можна перенести' },
  });
  const travelling = useDragState(store, (state) => state.activeIds.has(lesson.id));
  const dragging = useDragState(store, (state) => state.active !== null);
  const landed = useDragState(store, (state) => state.landedId === lesson.id);
  const [hoverOpen, setHoverOpen] = useState(false);
  // картку відкриває лише наведення миші: фокус (клавіатура, повернення після перетягування)
  // показує ті самі відомості в панелі «Вибрана пара», без спливного вікна
  const pointerInside = useRef(false);
  const hasHard = conflicts.some((c) => c.severity === 'HARD');
  const { title, detail } = chipText(lesson, view);
  const oneLine = compact || narrow;

  const onKeyDown = (event: KeyboardEvent<HTMLButtonElement>) => {
    // F2 — як у таблицях: редагувати; решту клавіш (Пробіл/Enter) обробляє сенсор перетягування
    if (event.key === 'F2') {
      event.preventDefault();
      onOpen(lesson);
      return;
    }
    listeners?.onKeyDown?.(event);
  };

  return (
    <HoverCard
      open={hoverOpen && !dragging}
      onOpenChange={(open) => setHoverOpen(open && pointerInside.current)}
      openDelay={350}
      closeDelay={120}
    >
      <HoverCardTrigger asChild>
        <button
          ref={setNodeRef}
          type="button"
          {...attributes}
          {...listeners}
          onPointerEnter={(event) => {
            if (event.pointerType === 'mouse') pointerInside.current = true;
          }}
          onPointerLeave={() => {
            pointerInside.current = false;
          }}
          onKeyDown={onKeyDown}
          tabIndex={tabIndex}
          data-roving=""
          data-lesson-id={lesson.id}
          aria-pressed={selected}
          aria-label={lessonAriaLabel(lesson, conflicts)}
          onFocus={() => {
            onFocusItem(`lesson:${lesson.id}`);
            onSelect(lesson);
          }}
          onClick={() => onSelect(lesson)}
          onDoubleClick={() => editable && onOpen(lesson)}
          style={style}
          {...lineAttr(lesson.discipline)}
          className={cn(
            'group/chip relative flex min-h-0 min-w-0 flex-col justify-center gap-0.5 overflow-hidden rounded-plate border px-1.5 text-start',
            'transition-[background-color,border-color,opacity] duration-100 ease-out',
            'focus-visible:z-10 focus-visible:outline-2 focus-visible:outline-offset-[-1px] focus-visible:outline-focus',
            oneLine ? 'py-0.5' : 'py-1',
            editable ? 'cursor-grab active:cursor-grabbing' : 'cursor-default',
            hasHard
              ? 'border-danger bg-danger-subtle'
              : 'border-separator bg-surface hover:border-default hover:bg-[var(--line-tint)]',
            selected && 'border-[var(--border-color-accent)] bg-selected ring-1 ring-[var(--border-color-accent)] ring-inset',
            travelling && 'border-dashed border-[var(--border-color-strong)] bg-sunken opacity-45',
            landed && 'motion-safe:animate-[live-flash_1.4s_var(--ease-out)]',
          )}
        >
          <span className="flex min-w-0 items-center gap-1">
            <span aria-hidden="true" className="size-1.5 shrink-0 rounded-full bg-[var(--line-dot)]" />
            {/* у половинці назва стискається останньою: спершу ховається викладач/аудиторія */}
            <span className="min-w-[1.75rem] shrink-[0.15] truncate font-condensed text-[0.875rem] leading-tight font-bold text-primary">
              {title}
            </span>
            {oneLine && !narrow && detail ? (
              <span className="min-w-0 shrink truncate text-meta font-normal text-secondary">{detail}</span>
            ) : null}
            <span className="ms-auto flex shrink-0 items-center gap-0.5 text-muted">
              {lesson.pinned ? <Pin aria-hidden="true" className="size-3" /> : null}
              {lesson.streamId ? <Layers aria-hidden="true" className="size-3" /> : null}
              {lesson.online ? <MonitorSmartphone aria-hidden="true" className="size-3" /> : null}
              {hasHard ? <TriangleExclamationPoint aria-hidden="true" className="size-3.5 text-danger" /> : null}
            </span>
          </span>
          {!oneLine && detail ? <span className="truncate text-meta font-normal text-secondary">{detail}</span> : null}
        </button>
      </HoverCardTrigger>
      <HoverCardContent side="right" align="start" className="p-3">
        <LessonDetailsCard lesson={lesson} conflicts={conflicts} />
      </HoverCardContent>
    </HoverCard>
  );
});

/** Плаваюча копія пари під курсором під час перетягування. */
export function LessonChipOverlay({ lesson, view }: { lesson: TemplateLessonDto; view: BoardView }) {
  const { title, detail } = chipText(lesson, view);
  return (
    <div
      {...lineAttr(lesson.discipline)}
      className="flex w-40 cursor-grabbing flex-col gap-0.5 rounded-plate border border-[var(--border-color-accent)] bg-raised px-2 py-1.5 shadow-lift"
    >
      <span className="flex min-w-0 items-center gap-1">
        <span aria-hidden="true" className="size-2 shrink-0 rounded-full bg-[var(--line-dot)]" />
        <span className="truncate font-condensed text-ui leading-tight font-bold text-primary">{title}</span>
      </span>
      {detail ? <span className="truncate text-meta text-secondary">{detail}</span> : null}
      <span className="text-meta text-muted">
        {weekdayName(lesson.weekday)}, {lesson.pair} пара · {PARITY_SHORT[lesson.parity]}
      </span>
    </div>
  );
}
