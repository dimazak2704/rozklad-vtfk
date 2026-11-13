import { useDraggable } from '@dnd-kit/core';
import { Layers, MonitorSmartphone, Pin, TriangleExclamationPoint } from 'lucide-react';
import { memo, type CSSProperties, type KeyboardEvent } from 'react';
import type { TimetableConflict } from '@/api/timetable';
import type { TemplateLessonDto } from '@/api/types';
import { HoverCard, HoverCardContent, HoverCardTrigger } from '@/components/ui';
import { useHoverIntent } from '@/components/ui/hover-intent';
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
  /** Вузька доріжка підгрупи: назва, під нею викладач і аудиторія — кожне своїм рядком (у половинці пари — лише назва). */
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
    attributes: { roleDescription: editable ? 'пара, яку можна перенести' : 'пара' },
  });
  const travelling = useDragState(store, (state) => state.activeIds.has(lesson.id));
  const dragging = useDragState(store, (state) => state.active !== null);
  const landed = useDragState(store, (state) => state.landedId === lesson.id);
  // картку відкриває лише наведення миші: фокус (клавіатура, повернення після перетягування) показує
  // ті самі відомості в панелі «Вибрана пара», без спливного вікна. Поки картка відкрита, сусідня пара
  // показує свою одразу; сама картка мишу не ловить (лише читання), тож не накриває шлях до сусідньої клітинки
  const hover = useHoverIntent({ openDelay: CARD_OPEN_DELAY_MS, closeDelay: CARD_CLOSE_DELAY_MS });
  const hasHard = conflicts.some((c) => c.severity === 'HARD');
  const { title, detail, lines, room } = chipText(lesson, view);
  const oneLine = compact || narrow;
  // підгрупа на всю пару: під назвою — викладач і аудиторія окремими рядками, як у друкованому розкладі
  const stacked = narrow && !compact;

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
      open={hover.open && !dragging}
      // відкриває лише useHoverIntent; Radix тут лише закриває (Escape, курсор пішов)
      onOpenChange={(open) => {
        if (!open) hover.close();
      }}
      openDelay={CARD_OPEN_DELAY_MS}
      closeDelay={CARD_CLOSE_DELAY_MS}
    >
      <HoverCardTrigger asChild>
        <button
          ref={setNodeRef}
          type="button"
          {...attributes}
          // у версії лише для перегляду пару все одно можна вибрати й роздивитись — вона не «вимкнена»
          aria-disabled={undefined}
          {...listeners}
          onPointerEnter={(event) => {
            if (event.pointerType === 'mouse') hover.enter();
          }}
          onPointerLeave={(event) => {
            if (event.pointerType === 'mouse') hover.leave();
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
            'group/chip @container relative flex min-h-0 min-w-0 flex-col justify-center gap-0.5 overflow-hidden rounded-tag border px-1.5 text-start',
            'transition-[background-color,border-color,opacity] duration-150 ease-out',
            'focus-visible:z-10 focus-visible:outline-2 focus-visible:outline-offset-[-1px] focus-visible:outline-focus',
            oneLine ? 'py-0.5' : 'py-1',
            // у вузькій доріжці підгрупи кожен піксель — прізвищу
            narrow && 'px-1',
            editable ? 'cursor-grab active:cursor-grabbing' : 'cursor-default',
            // у спокої пара — текст на клітинці без власної рамки (межі вже дає сітка); половинки підгрупи тримають
            // волосяну лінію, щоб «Інф. | Інф.» не злились. Наведення — нейтральне тоновання, колір несе лише крапка
            hasHard
              ? 'border-danger bg-danger-subtle'
              : cn('bg-surface', !selected && 'hover-fine:border-default hover-fine:bg-hover', narrow ? 'border-separator' : 'border-transparent'),
            selected && 'border-[var(--border-color-accent)] bg-selected ring-1 ring-[var(--border-color-accent)] ring-inset',
            travelling && 'border-dashed border-[var(--border-color-strong)] bg-sunken opacity-45',
            landed && 'motion-safe:animate-[live-flash_1.4s_var(--ease-out)]',
          )}
        >
          <span className="flex min-w-0 items-center gap-1">
            {/* у вузькій половинці підгрупи крапку дисципліни ховаємо: місце — назві («Ін.мов.», а не «Ін.мо…») */}
            <span
              aria-hidden="true"
              className={cn(
                'size-1.5 shrink-0 rounded-full bg-[var(--line-dot)] transition-transform duration-150 ease-out motion-reduce:transition-none pointer-fine:group-hover/chip:scale-125',
                narrow && 'hidden @[5.5rem]:block',
              )}
            />
            {/* у половинці назва стискається останньою: спершу ховається викладач/аудиторія */}
            <span
              className={cn(
                'min-w-[1.75rem] shrink-[0.15] truncate leading-tight font-[650] text-primary',
                narrow ? 'text-[0.8125rem] tracking-[-0.01em]' : 'text-[0.875rem]',
              )}
            >
              {title}
            </span>
            {/* у половинці (чисельник/знаменник) викладач поруч — лише коли є місце; інакше назва
                лишається цілою, а викладач — у картці й панелі «Вибрана пара» */}
            {oneLine && !narrow && detail ? (
              <span className="hidden min-w-0 shrink truncate text-meta font-normal text-secondary @[11rem]:block">{detail}</span>
            ) : null}
            <span className="ms-auto flex shrink-0 items-center gap-0.5 text-muted">
              {lesson.pinned ? <Pin aria-hidden="true" className="size-3" /> : null}
              {lesson.streamId ? <Layers aria-hidden="true" className="size-3" /> : null}
              {lesson.online ? <MonitorSmartphone aria-hidden="true" className="size-3" /> : null}
              {hasHard ? <TriangleExclamationPoint aria-hidden="true" className="size-3.5 text-danger" /> : null}
            </span>
          </span>
          {!oneLine && detail ? <span className="truncate text-meta font-normal text-secondary">{detail}</span> : null}
          {stacked
            ? lines.map((line) => (
                // довге прізвище — з трикрапкою (повне — у картці й підписі для читача екрана): рядки клітинки
                // лишаються рівними, а перенос посеред прізвища без дефіса читається гірше
                <span key={line} className={cn('truncate text-meta leading-tight font-normal text-secondary', line === room && 'tabular-nums')}>
                  {line}
                </span>
              ))
            : null}
        </button>
      </HoverCardTrigger>
      <HoverCardContent side="right" align="start" className="p-3" peek instant={hover.instant}>
        <LessonDetailsCard lesson={lesson} conflicts={conflicts} />
      </HoverCardContent>
    </HoverCard>
  );
});

/** Перша картка — після паузи на парі; далі, поки картка відкрита, сусідні показуються одразу. */
const CARD_OPEN_DELAY_MS = 350;
/** Курсор пішов з пари: картка зникає майже одразу — на неї саму миша не переходить. */
const CARD_CLOSE_DELAY_MS = 80;

/** Плаваюча копія пари під курсором під час перетягування. */
export function LessonChipOverlay({ lesson, view }: { lesson: TemplateLessonDto; view: BoardView }) {
  const { title, detail } = chipText(lesson, view);
  return (
    <div
      {...lineAttr(lesson.discipline)}
      className="flex w-40 cursor-grabbing flex-col gap-0.5 rounded-tag border border-[var(--border-color-accent)] bg-raised px-2 py-1.5 shadow-popover"
    >
      <span className="flex min-w-0 items-center gap-1">
        <span aria-hidden="true" className="size-2 shrink-0 rounded-full bg-[var(--line-dot)]" />
        <span className="truncate text-ui leading-tight font-[650] text-primary">{title}</span>
      </span>
      {detail ? <span className="truncate text-meta text-secondary">{detail}</span> : null}
      <span className="text-meta text-muted">
        {weekdayName(lesson.weekday)}, {lesson.pair} пара · {PARITY_SHORT[lesson.parity]}
      </span>
    </div>
  );
}
