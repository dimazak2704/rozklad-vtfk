import { CornerDownRight, MonitorSmartphone } from 'lucide-react';
import type { ComponentProps, ReactElement } from 'react';
import type { IsoDate, LessonView, WeekParity } from '@/api/types';
import { cn } from '@/lib/cn';
import { lineAttr } from '@/lib/discipline-color';
import { LESSON_VIEW_KIND_LABEL } from '@/lib/labels';
import { pairOrdinalGenitive } from '@/lib/ordinal';
import { LESSON_TONE, lessonCounterpart, lessonSummary, lessonTime, roomLabel, type Perspective } from '@/lib/schedule';
import { Drawer, DrawerContent, DrawerTrigger } from '../ui/Drawer';
import { HoverDetails } from '../ui/HoverCard';
import { LessonDetails } from './LessonDetails';
import { usePhoneLayout } from './usePhoneLayout';

export interface LessonCellProps {
  lesson: LessonView;
  /** Чий розклад: група бачить викладача, викладач — групу, аудиторія — обох. */
  perspective?: Perspective;
  /** `grid` — клітинка тижневої сітки, `list` — рядок денного списку. */
  variant?: 'grid' | 'list';
  date?: IsoDate;
  dayParity?: WeekParity | null;
  /** Пара йде зараз (для читача екрана; лінію «зараз» малює сітка чи список). */
  now?: boolean;
  /** Застаріле: прогрес малює лінія «зараз» сітки. */
  progress?: number;
  /** Пара вже минула (сьогодні): «осідає» — назва тихіша, крапка на 40 %. */
  past?: boolean;
  /** Показати час (коли він відрізняється від часу рядка сітки, напр. у пʼятницю). */
  showTime?: boolean;
  className?: string;
}

/**
 * Тло стану — лише в заміни й додаткової пари: тонування з відступом 4 px усередині клітинки, радіус 10.
 * «Пари не буде» без тла (значення несе закреслення), інша аудиторія змінює лише рядок аудиторії,
 * перенесена пара — пунктирний контур. Стан читається й у відтінках сірого: слово, закреслення, форма.
 */
const TONE_SURFACE = {
  regular: 'before:bg-transparent hover:before:bg-hover',
  change: 'before:bg-change-subtle',
  cancel: 'before:bg-transparent hover:before:bg-hover',
  add: 'before:bg-add-subtle',
  room: 'before:bg-transparent hover:before:bg-hover',
  block: 'hatch before:bg-transparent',
} as const;

/** Слово стану над назвою — звичайним текстом у кольорі стану, без «таблички». */
const STATE_WORD = {
  change: 'text-change',
  cancel: 'text-cancel',
  add: 'text-add',
} as const;

/** Закреслений оригінал: лінія кольору позначки заміни, текст тихий. */
const STRUCK = 'line-through decoration-[var(--text-decoration-color-change)] decoration-1';
const STRUCK_CANCEL = 'line-through decoration-[var(--text-decoration-color-cancel)] decoration-2';

/** Деталі: на компʼютері — картка при наведенні/Enter, на телефоні — шторка знизу. */
function DetailsTrigger({
  phone,
  label,
  content,
  children,
}: {
  phone: boolean;
  label: string;
  content: ComponentProps<typeof LessonDetails>;
  children: ReactElement;
}) {
  if (phone) {
    return (
      <Drawer>
        <DrawerTrigger asChild>{children}</DrawerTrigger>
        <DrawerContent title={label} titleClassName="sr-only">
          <LessonDetails {...content} />
        </DrawerContent>
      </Drawer>
    );
  }
  return (
    <HoverDetails label={label} content={<LessonDetails {...content} />}>
      {children}
    </HoverDetails>
  );
}

/**
 * Заняття в розкладі (DESIGN.md, «Клітинка заняття»): слово стану (за потреби), крапка дисципліни й назва
 * до двох рядків, викладач ліворуч і аудиторія праворуч, закреслений оригінал для заміни. Не більше трьох
 * рядків тексту й рядка стану; решта — у картці деталей (наведення чи Enter, на телефоні — шторка).
 */
export function LessonCell({
  lesson,
  perspective = 'group',
  variant = 'grid',
  date,
  dayParity,
  now = false,
  past = false,
  showTime = false,
  className,
}: LessonCellProps) {
  const phone = usePhoneLayout();
  const tone = LESSON_TONE[lesson.kind];
  const cancelled = tone === 'cancel';
  const moved = lesson.kind === 'MOVED';
  const counterpart = lessonCounterpart(lesson, perspective);
  const room = roomLabel(lesson.room?.name);
  const originalRoom = lesson.kind === 'ROOM_CHANGED' ? roomLabel(lesson.original?.room?.name) : null;
  const original = lesson.original;
  const changedDiscipline = lesson.kind === 'SUBSTITUTED' && original?.discipline && original.discipline.id !== lesson.discipline.id;
  const changedTeacher = lesson.kind === 'SUBSTITUTED' && original?.teacher && original.teacher.id !== lesson.teacher?.id;
  const replacedText =
    lesson.kind === 'SUBSTITUTED'
      ? [changedDiscipline ? original?.discipline?.shortName : null, changedTeacher ? original?.teacher?.displayName : null]
          .filter(Boolean)
          .join(' · ')
      : null;
  const movedFrom = moved && original?.pair ? original.pair : null;
  const isList = variant === 'list';
  const stateWord = tone === 'change' || tone === 'cancel' || tone === 'add' ? LESSON_VIEW_KIND_LABEL[lesson.kind] : null;
  const title = lesson.discipline.name || lesson.discipline.shortName;
  // минула пара сьогодні «осідає»: назва вторинним чорнилом (контраст лишається AA), крапка на 40 %
  const titleTone = cn('anim-settle', past || cancelled ? 'text-secondary' : 'text-primary', cancelled && STRUCK_CANCEL);
  const dot = (
    <span
      {...lineAttr(lesson.discipline)}
      aria-hidden="true"
      className={cn('anim-settle mt-[0.375rem] size-2 shrink-0 rounded-full bg-[var(--line-dot)]', past && 'opacity-40')}
    />
  );

  return (
    <DetailsTrigger
      phone={phone}
      label={`Деталі: ${lesson.discipline.shortName}, ${lesson.pair} пара`}
      content={{ lesson, date, dayParity }}
    >
      <button
        type="button"
        aria-label={`${lessonSummary(lesson, perspective)}${now ? ' Іде зараз.' : ''}${past ? ' Пара завершилась.' : ''}`}
        className={cn(
          'group/lesson relative isolate flex w-full min-w-0 flex-col items-stretch gap-1 text-start',
          // тло стану — псевдоелемент з відступом 4 px (у сітці) і радіусом 10
          'before:absolute before:-z-10 before:rounded-cell before:transition-[background-color] before:duration-150',
          'rounded-cell px-3.5 py-3',
          isList ? 'before:inset-0' : '@container/cell h-full before:inset-1',
          'focus-visible:z-10 focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus',
          TONE_SURFACE[tone],
          // перенесена пара — пунктирний контур замість п'ятого кольору
          moved && 'after:pointer-events-none after:absolute after:inset-1 after:rounded-cell after:border after:border-dashed after:border-control',
          className,
        )}
      >
        {stateWord ? (
          <span className={cn('text-meta font-semibold', STATE_WORD[tone as keyof typeof STATE_WORD])}>{stateWord}</span>
        ) : null}

        <span className={cn('flex min-w-0 items-start gap-2', tone === 'block' && 'w-fit max-w-full rounded-tag bg-surface pe-1')}>
          {dot}
          {isList ? (
            <span className={cn('min-w-0 text-cell font-[650] text-pretty', !stateWord && 'line-clamp-2', titleTone)}>{title}</span>
          ) : (
            <>
              {/* вузька колонка — коротка назва; від ~200 px — повна, до двох рядків (повна — у картці) */}
              <span className={cn('min-w-0 truncate text-cell font-[650] @[12.5rem]/cell:hidden', titleTone)}>
                {lesson.discipline.shortName}
              </span>
              <span aria-hidden="true" className={cn('hidden min-w-0 text-cell font-[650] text-pretty @[12.5rem]/cell:line-clamp-2', titleTone)}>
                {title}
              </span>
            </>
          )}
          {lesson.subgroup > 0 ? (
            <span className="ms-auto shrink-0 pt-px text-meta font-semibold whitespace-nowrap text-muted">{lesson.subgroup}&nbsp;п/г</span>
          ) : null}
        </span>

        {/* викладач ліворуч, аудиторія праворуч; не вміщаються — аудиторія переноситься, але не обрізається */}
        <span
          className={cn(
            'flex min-w-0 flex-wrap items-baseline justify-between gap-x-2 ps-4 text-caption',
            past ? 'text-muted' : 'text-secondary',
            cancelled && 'line-through decoration-[var(--text-decoration-color-cancel)]',
            tone === 'block' && 'w-fit max-w-full rounded-tag bg-surface pe-1',
          )}
        >
          {counterpart ? <span className="min-w-0 truncate">{counterpart}</span> : null}
          {originalRoom ? (
            <span className="inline-flex items-baseline gap-1 whitespace-nowrap">
              <s className={cn('text-muted', STRUCK)}>{originalRoom}</s>
              <span className="font-[650] text-room figures">{room?.replace(/^ауд\.\u00a0/, '')}</span>
            </span>
          ) : room ? (
            <span className="whitespace-nowrap">
              {room.startsWith('ауд.') ? (
                <>
                  ауд.&nbsp;<span className="figures">{room.replace(/^ауд\.\u00a0/, '')}</span>
                </>
              ) : (
                room
              )}
            </span>
          ) : null}
          {lesson.online ? (
            <span className="inline-flex items-center gap-1">
              <MonitorSmartphone aria-hidden="true" className="size-3.5 text-muted" />
              онлайн
            </span>
          ) : null}
        </span>

        {/*
          Що було замість — закресленим тихим рядком: «КМ · Колесник А.». Внутрішні коди розпорядження
          («Зв.зам.», «Відп.») у клітинку не виносимо — причину словами показує картка деталей.
        */}
        {replacedText ? (
          <span className="min-w-0 truncate ps-4 text-caption text-muted">
            <span className="sr-only">замість </span>
            <s className={STRUCK}>{replacedText}</s>
          </span>
        ) : null}
        {movedFrom ? (
          <span className="flex items-center gap-1 ps-4 text-caption text-secondary">
            <CornerDownRight aria-hidden="true" className="size-3.5 text-muted rtl:-scale-x-100" />з {pairOrdinalGenitive(movedFrom)} пари
          </span>
        ) : null}

        {showTime && lessonTime(lesson) ? <span className="ps-4 text-meta text-muted figures">{lessonTime(lesson)}</span> : null}
      </button>
    </DetailsTrigger>
  );
}
