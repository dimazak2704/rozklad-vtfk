import { ArrowRight, MonitorSmartphone } from 'lucide-react';
import type { ComponentProps, ReactElement } from 'react';
import type { IsoDate, LessonView, WeekParity } from '@/api/types';
import { cn } from '@/lib/cn';
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
  /** `grid` — клітинка тижневої сітки (на всю клітинку таблиці), `list` — рядок денного списку. */
  variant?: 'grid' | 'list';
  date?: IsoDate;
  dayParity?: WeekParity | null;
  /** Пара йде зараз: доріжка прогресу 3 px уздовж верхнього краю. */
  now?: boolean;
  /** 0..1 — скільки пари минуло (для доріжки). */
  progress?: number;
  /** Пара вже минула (сьогодні): тихіший текст, без прозорості (контраст лишається AA). */
  past?: boolean;
  /** Показати час (коли він відрізняється від часу рядка сітки, напр. у пʼятницю). */
  showTime?: boolean;
  className?: string;
}

/**
 * Тло стану — як маркер на надрукованому аркуші: тонування від краю до краю, без рамок.
 * Справжній «маркер» один — бурштин заміни; «пари не буде» тонована ледь-ледь, бо значення несе закреслення.
 * Додаткова пара без заливки: лише риска 3 px на початку клітинки (або заливка, або межа — ніколи обидві).
 * Зміна аудиторії тла не має: її видно в самому рядку «ауд. 17 → ауд. 309».
 */
const TONE_SURFACE = {
  regular: 'hover:bg-hover',
  change: 'bg-change-subtle',
  cancel: 'bg-cancel-subtle',
  add: 'hover:bg-hover before:absolute before:inset-y-0 before:start-0 before:w-[3px] before:bg-[var(--fill-add)]',
  room: 'hover:bg-hover',
  block: 'hatch bg-block-subtle',
} as const;

/** Одне слово стану над назвою — звичайним текстом, без «таблички». */
const STATE_WORD = {
  change: 'text-change',
  cancel: 'text-cancel',
  add: 'text-add',
} as const;

/**
 * Змінене значення — напівжирним: клітинка вже тонована бурштином, а оригінал закреслений рядком нижче,
 * тож підкреслення було б уже пʼятим сигналом.
 */
const MARKED = 'font-semibold text-primary';

/** Закреслення оригіналу нейтральне; червоне — лише коли пари справді не буде. */
const STRUCK = 'decoration-[var(--text-decoration-color-muted)] decoration-1';
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
 * Заняття в розкладі. Предмет названо повністю, коли є місце (список; клітинка сітки від ~200 px), інакше —
 * кодом; кольорів дисциплін у сітці немає. Стан — тонування і одне слово; для змін оригінал лишається
 * видимим закресленим («замість …»), а нове значення виділене вагою.
 * Наведення або Enter відкривають повні деталі.
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
  const phone = usePhoneLayout();
  const tone = LESSON_TONE[lesson.kind];
  const cancelled = tone === 'cancel';
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
  const movedFrom = lesson.kind === 'MOVED' && original?.pair ? original.pair : null;
  const isList = variant === 'list';
  const stateWord = tone === 'change' || tone === 'cancel' || tone === 'add' ? LESSON_VIEW_KIND_LABEL[lesson.kind] : null;
  // у списку (телефон) предмет названо повністю: скорочення зрозумілі не всім, а місця вистачає
  const title = lesson.discipline.name || lesson.discipline.shortName;
  // один маркер на клітинку: змінився предмет — підкреслено предмет, інакше — нового викладача
  const markCounterpart = Boolean(changedTeacher && !changedDiscipline && perspective !== 'teacher');
  const emphasised = now || tone !== 'regular';
  const titleTone = cn(
    past ? 'text-secondary' : 'text-primary',
    cancelled && cn('text-secondary', STRUCK_CANCEL),
    changedDiscipline && !cancelled && MARKED,
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
          'group/lesson relative flex w-full min-w-0 flex-col items-stretch overflow-hidden text-start',
          'transition-[background-color] duration-100 ease-out',
          'focus-visible:z-10 focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus',
          // у списку лівий край прямий — урівень із колонкою лінії маршруту, заокруглено лише праворуч
          isList ? 'gap-0.5 rounded-e-control px-3 py-2.5' : '@container/cell h-full gap-0.5 px-3 py-2',
          TONE_SURFACE[tone],
          className,
        )}
      >
        {now ? (
          <span aria-hidden="true" className="absolute inset-x-0 top-0 h-[3px] bg-accent-subtle">
            <span
              className="block h-full origin-left bg-accent transition-transform duration-1000 ease-linear rtl:origin-right"
              style={{ transform: `scaleX(${Math.max(0.02, Math.min(1, progress))})` }}
            />
          </span>
        ) : null}

        {/* слово стану — окремим рядком над назвою (як рубрика), щоб назва мала всю ширину й не обрізалась */}
        {stateWord ? (
          <span className={cn('text-meta font-semibold', STATE_WORD[tone as keyof typeof STATE_WORD])}>{stateWord}</span>
        ) : null}

        <span
          className={cn(
            'flex min-w-0 items-baseline gap-1.5',
            // текст блоку пар лежить на непрозорій «плашці», щоб штрихування не різало літери
            tone === 'block' && '-mx-1 w-fit max-w-full rounded-plate bg-surface px-1',
          )}
        >
          {isList ? (
            <span
              className={cn(
                'min-w-0 text-body font-semibold text-pretty',
                // змінену й поточну пару не обрізаємо ніколи: саме її людина відкрила сайт подивитись
                !emphasised && 'line-clamp-3',
                titleTone,
              )}
            >
              {title}
            </span>
          ) : (
            <>
              {/* вузька колонка — код вузьким жирним; від ~200 px — повна назва, до двох рядків */}
              <span className={cn('min-w-0 truncate font-condensed text-cell font-bold @[12.5rem]/cell:hidden', titleTone)}>
                {lesson.discipline.shortName}
              </span>
              <span
                aria-hidden="true"
                className={cn(
                  'hidden min-w-0 text-cell font-semibold text-pretty',
                  // змінена чи поточна пара — до трьох рядків, звичайна — до двох
                  emphasised ? '@[12.5rem]/cell:line-clamp-3' : '@[12.5rem]/cell:line-clamp-2',
                  titleTone,
                )}
              >
                {lesson.discipline.name || lesson.discipline.shortName}
              </span>
            </>
          )}
          {lesson.subgroup > 0 ? (
            <span className="shrink-0 text-meta font-medium whitespace-nowrap text-muted figures">{lesson.subgroup}&nbsp;п/г</span>
          ) : null}
        </span>

        <span
          className={cn(
            'flex min-w-0 flex-wrap items-center gap-x-1.5',
            isList ? 'text-cell' : 'text-caption',
            past ? 'text-muted' : 'text-secondary',
            cancelled && 'line-through decoration-[var(--text-decoration-color-cancel)]',
            tone === 'block' && '-mx-1 w-fit max-w-full rounded-plate bg-surface px-1',
          )}
        >
          {counterpart ? <span className={cn('truncate', markCounterpart && !cancelled && MARKED)}>{counterpart}</span> : null}
          {counterpart && (room || originalRoom) ? <span aria-hidden="true">·</span> : null}
          {originalRoom ? (
            <span className="inline-flex items-center gap-1 whitespace-nowrap text-room figures">
              <s className={cn('text-muted', STRUCK)}>{originalRoom}</s>
              <ArrowRight aria-hidden="true" className="size-3 text-muted rtl:rotate-180" />
              <span className="font-semibold">{room}</span>
            </span>
          ) : room ? (
            <span className="whitespace-nowrap figures">{room}</span>
          ) : null}
          {lesson.online ? (
            <span className="inline-flex items-center gap-1">
              <MonitorSmartphone aria-hidden="true" className="size-3.5 text-muted" />
              онлайн
            </span>
          ) : null}
        </span>

        {/*
          Що було замість — простими словами: «замість СТП · Кренцін». Внутрішні коди розпорядження
          («Зв.зам.», «Відп.») у клітинку не виносимо — причину словами показує картка деталей.
        */}
        {replacedText || movedFrom ? (
          <span className={cn('flex min-w-0 flex-wrap items-baseline gap-x-1 text-muted', isList ? 'text-caption' : 'text-meta')}>
            {replacedText ? (
              <span className="min-w-0 truncate">
                замість <s className={STRUCK}>{replacedText}</s>
              </span>
            ) : null}
            {movedFrom ? <span className="text-secondary">з {pairOrdinalGenitive(movedFrom)} пари</span> : null}
          </span>
        ) : null}

        {showTime && lessonTime(lesson) ? <span className="text-meta text-muted figures">{lessonTime(lesson)}</span> : null}
      </button>
    </DetailsTrigger>
  );
}
