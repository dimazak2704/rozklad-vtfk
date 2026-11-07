import { ArrowRight, MonitorSmartphone } from 'lucide-react';
import type { IsoDate, LessonView, WeekParity } from '@/api/types';
import { cn } from '@/lib/cn';
import { lineAttr } from '@/lib/discipline-color';
import { LESSON_VIEW_KIND_LABEL } from '@/lib/labels';
import { LESSON_TONE, lessonCounterpart, lessonSummary, roomLabel, type Perspective } from '@/lib/schedule';
import { Badge } from '../ui/Badge';
import { HoverDetails } from '../ui/HoverCard';
import { LessonDetails } from './LessonDetails';
import { TONE_BADGE } from './tones';

export interface LessonCellProps {
  lesson: LessonView;
  /** Чий розклад: група бачить викладача, викладач — групу, аудиторія — обох. */
  perspective?: Perspective;
  /** `grid` — компактна клітинка тижневої сітки, `list` — рядок денного списку. */
  variant?: 'grid' | 'list';
  date?: IsoDate;
  dayParity?: WeekParity;
  /** Пара йде зараз: підсвітка «живим» кольором і смужка прогресу. */
  now?: boolean;
  /** 0..1 — скільки пари минуло (для смужки). */
  progress?: number;
  /** Пара вже минула (сьогодні): приглушити. */
  past?: boolean;
  /** Показати час (коли він відрізняється від часу рядка сітки, напр. у пʼятницю). */
  showTime?: boolean;
  className?: string;
}

/** Фон і рамка «обʼїзду» за станом: зміни лягають поверх розкладу, звичайна пара — на чистому тлі. */
const TONE_SURFACE = {
  regular: 'hover:bg-[var(--line-tint)]',
  change: 'bg-change-subtle ring-1 ring-inset ring-[var(--border-color-change)]',
  cancel: 'bg-cancel-subtle ring-1 ring-inset ring-[var(--border-color-cancel)]',
  add: 'bg-add-subtle ring-1 ring-inset ring-[var(--border-color-add)]',
  room: 'hover:bg-[var(--line-tint)]',
  block: 'hatch bg-block-subtle',
} as const;

/**
 * Заняття в розкладі. Дисципліна має свій колір лінії (крапка), стан — свій колір «обʼїзду»;
 * для змін оригінал лишається видимим закресленим. Наведення або Enter відкривають повні деталі.
 */
export function LessonCell({
  lesson,
  perspective = 'group',
  variant = 'grid',
  date,
  dayParity,
  now = false,
  progress = 0,
  past = false,
  showTime = false,
  className,
}: LessonCellProps) {
  const tone = LESSON_TONE[lesson.kind];
  const cancelled = tone === 'cancel';
  const counterpart = lessonCounterpart(lesson, perspective);
  const room = roomLabel(lesson.room?.name);
  const originalRoom = lesson.kind === 'ROOM_CHANGED' ? roomLabel(lesson.original?.room?.name) : null;
  const original = lesson.original;
  const replacedText =
    lesson.kind === 'SUBSTITUTED'
      ? [
          original?.discipline && original.discipline.id !== lesson.discipline.id ? original.discipline.shortName : null,
          original?.teacher && original.teacher.id !== lesson.teacher?.id ? original.teacher.displayName : null,
        ]
          .filter(Boolean)
          .join(' · ')
      : null;
  const movedFrom = lesson.kind === 'MOVED' && original?.pair ? original.pair : null;
  const isList = variant === 'list';

  return (
    <HoverDetails
      label={`Деталі: ${lesson.discipline.shortName}, ${lesson.pair} пара`}
      content={<LessonDetails lesson={lesson} date={date} dayParity={dayParity} />}
    >
      <button
        type="button"
        {...lineAttr(lesson.discipline)}
        aria-label={lessonSummary(lesson, perspective)}
        className={cn(
          'group/lesson relative flex w-full min-w-0 flex-col items-stretch text-start',
          'transition-[background-color] duration-100 ease-out',
          'focus-visible:z-10 focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus',
          isList ? 'gap-1 rounded-control px-3 py-2.5' : 'gap-0.5 rounded-plate px-2 py-1.5',
          now && tone === 'regular' ? 'bg-selected ring-1 ring-inset ring-[var(--border-color-accent-subtle)]' : TONE_SURFACE[tone],
          past && 'opacity-70',
          className,
        )}
      >
        {now ? (
          <span
            aria-hidden="true"
            className="absolute inset-x-0 top-0 h-0.5 origin-left rounded-full bg-accent transition-transform duration-1000 ease-linear rtl:origin-right"
            style={{ transform: `scaleX(${Math.max(0.02, Math.min(1, progress))})` }}
          />
        ) : null}

        <span className="flex min-w-0 items-center gap-1.5">
          <span aria-hidden="true" className="size-2 shrink-0 rounded-full bg-[var(--line-dot)]" />
          <span
            className={cn(
              'min-w-0 truncate font-condensed font-bold text-primary',
              isList ? 'text-body leading-tight' : 'text-[0.9375rem] leading-tight',
              cancelled && 'text-secondary line-through decoration-[var(--text-decoration-color-cancel)] decoration-2',
            )}
          >
            {lesson.discipline.shortName}
          </span>
          {lesson.subgroup > 0 ? (
            <span className="shrink-0 rounded-plate border border-default px-1 text-meta font-semibold text-secondary figures">
              {lesson.subgroup}&nbsp;п/г
            </span>
          ) : null}
          {tone !== 'regular' ? (
            <Badge tone={TONE_BADGE[tone]} size="sm" className="ms-auto shrink-0">
              {lesson.kind === 'ROOM_CHANGED' ? 'Аудиторія' : LESSON_VIEW_KIND_LABEL[lesson.kind]}
            </Badge>
          ) : null}
        </span>

        {isList && lesson.discipline.name && lesson.discipline.name !== lesson.discipline.shortName ? (
          <span className={cn('truncate text-caption text-muted', cancelled && 'line-through')}>{lesson.discipline.name}</span>
        ) : null}

        <span
          className={cn(
            'flex min-w-0 flex-wrap items-center gap-x-1.5 text-secondary',
            isList ? 'text-ui' : 'text-caption',
            cancelled && 'line-through decoration-[var(--text-decoration-color-cancel)]',
          )}
        >
          {counterpart ? <span className="truncate">{counterpart}</span> : null}
          {counterpart && (room || originalRoom) ? <span aria-hidden="true">·</span> : null}
          {originalRoom ? (
            <span className="inline-flex items-center gap-1 text-room">
              <s className="text-muted">{originalRoom}</s>
              <ArrowRight aria-hidden="true" className="size-3 rtl:rotate-180" />
              <span className="font-semibold">{room}</span>
            </span>
          ) : room ? (
            <span className="whitespace-nowrap figures">{room}</span>
          ) : null}
          {lesson.online ? (
            <span className="inline-flex items-center gap-1 text-accent">
              <MonitorSmartphone aria-hidden="true" className="size-3.5" />
              онлайн
            </span>
          ) : null}
        </span>

        {replacedText || movedFrom || (lesson.reasonText && tone !== 'regular') ? (
          <span className={cn('flex min-w-0 flex-wrap items-center gap-x-1.5 text-muted', isList ? 'text-caption' : 'text-meta')}>
            {replacedText ? <s className="truncate decoration-[var(--text-decoration-color-muted)]">{replacedText}</s> : null}
            {movedFrom ? <span className="text-change">з {movedFrom} пари</span> : null}
            {lesson.reasonText && tone !== 'regular' ? (
              <span className="font-semibold text-secondary">{lesson.reasonText}</span>
            ) : null}
          </span>
        ) : null}

        {showTime ? (
          <span className="text-meta font-semibold text-muted figures">
            {lesson.startsAt}–{lesson.endsAt}
          </span>
        ) : null}
      </button>
    </HoverDetails>
  );
}
