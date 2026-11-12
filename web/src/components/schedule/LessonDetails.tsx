import { MonitorSmartphone } from 'lucide-react';
import type { ReactNode } from 'react';
import { Link } from 'react-router';
import type { IsoDate, LessonView, WeekParity } from '@/api/types';
import { paths } from '@/app/routes';
import { cn } from '@/lib/cn';
import { formatWeekdayDate } from '@/lib/date';
import { lineAttr } from '@/lib/discipline-color';
import { capitalize, LESSON_VIEW_KIND_LABEL, PARITY_LABEL, REASON_CODE_HINT, REASON_CODE_LABEL, ROOM_TYPE_LABEL, subgroupLabel } from '@/lib/labels';
import { pairOrdinalGenitive, pairOrdinalLabel } from '@/lib/ordinal';
import { LESSON_TONE, lessonTime, roomLabel } from '@/lib/schedule';

const STATE_WORD = {
  change: 'text-change',
  cancel: 'text-cancel',
  add: 'text-add',
  room: 'text-room',
  regular: 'text-secondary',
  block: 'text-secondary',
} as const;

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="grid grid-cols-[4.75rem_minmax(0,1fr)] items-baseline gap-x-3">
      <dt className="text-caption text-muted">{label}</dt>
      <dd className="text-ui text-primary">{children}</dd>
    </div>
  );
}

interface LessonDetailsProps {
  lesson: LessonView;
  date?: IsoDate;
  dayParity?: WeekParity | null;
}

/** Скорочення з розпорядження («Зв.зам.») — словами («взаємна заміна»): у публічній картці кодів не лишаємо. */
function reasonInWords(text: string): string {
  const code = (Object.keys(REASON_CODE_LABEL) as Array<keyof typeof REASON_CODE_LABEL>).find(
    (key) => REASON_CODE_LABEL[key].toLocaleLowerCase('uk') === text.trim().toLocaleLowerCase('uk'),
  );
  return code ? REASON_CODE_HINT[code].toLocaleLowerCase('uk') : text;
}

const LINK =
  'inline-flex min-h-8 items-center rounded-control text-ui font-[650] text-accent pointer-coarse:min-h-11 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus';

/**
 * Повні відомості про заняття для картки при наведенні чи шторки на телефоні: повна назва з крапкою
 * дисципліни, «Веде / Де / Коли / Група», для зміни — «Було» й причина словами, і два переходи:
 * розклад викладача й аудиторії.
 */
export function LessonDetails({ lesson, date, dayParity }: LessonDetailsProps) {
  const tone = LESSON_TONE[lesson.kind];
  const original = lesson.original;
  const subgroup = subgroupLabel(lesson.subgroup);
  const replacedDiscipline = original?.discipline && original.discipline.id !== lesson.discipline.id;
  const replacedTeacher = original?.teacher && original.teacher.id !== lesson.teacher?.id;
  const replacedRoom = original?.room && original.room.id !== lesson.room?.id;
  const movedFrom = original?.pair && original.pair !== lesson.pair ? original.pair : null;
  const when = [
    date ? formatWeekdayDate(date) : null,
    `${pairOrdinalLabel(lesson.pair)}${lessonTime(lesson) ? ` · ${lessonTime(lesson)}` : ''}`,
  ]
    .filter(Boolean)
    .join(', ');

  return (
    <div className="flex w-full flex-col gap-3 sm:w-[min(21rem,calc(100vw-3rem))]">
      <div className="flex flex-col gap-1">
        {tone !== 'regular' && tone !== 'block' ? (
          <span className={cn('text-meta font-semibold', STATE_WORD[tone])}>{LESSON_VIEW_KIND_LABEL[lesson.kind]}</span>
        ) : null}
        <p className="flex items-start gap-2 text-heading font-bold text-primary">
          <span {...lineAttr(lesson.discipline)} className="mt-2 size-2 shrink-0 rounded-full bg-[var(--line-dot)]" aria-hidden="true" />
          <span className={cn(tone === 'cancel' && 'line-through decoration-[var(--text-decoration-color-cancel)] decoration-2')}>
            {lesson.discipline.name || lesson.discipline.shortName}
          </span>
        </p>
      </div>

      <dl className="flex flex-col gap-1.5">
        <Row label="Веде">{lesson.teacher ? lesson.teacher.shortName : <span className="text-muted">не призначено</span>}</Row>
        <Row label="Де">
          {lesson.room ? (
            <>
              {roomLabel(lesson.room.name)}
              {/* «ауд. 16 · аудиторія» — повтор; тип називаємо, лише коли він щось каже (клас, лабораторія, зал) */}
              {lesson.room.type !== 'CLASSROOM' && lesson.room.type !== 'OTHER' ? (
                <span className="text-secondary"> · {ROOM_TYPE_LABEL[lesson.room.type]}</span>
              ) : null}
            </>
          ) : (
            <span className="text-muted">не вказано</span>
          )}
          {lesson.online ? (
            <span className="ms-1 inline-flex items-center gap-1 text-secondary">
              <MonitorSmartphone aria-hidden="true" className="size-3.5" />
              дистанційно
            </span>
          ) : null}
        </Row>
        <Row label="Коли">
          {capitalize(when)}
          {dayParity ? <span className="text-secondary">, {PARITY_LABEL[dayParity]}</span> : null}
        </Row>
        <Row label="Група">
          {lesson.group.code}
          {subgroup ? <span className="text-secondary"> · {subgroup}</span> : null}
        </Row>
      </dl>

      {tone !== 'regular' && tone !== 'block' && (replacedDiscipline || replacedTeacher || replacedRoom || movedFrom || lesson.reasonText) ? (
        <dl className="flex flex-col gap-1.5 border-t border-separator pt-3">
          {replacedDiscipline || replacedTeacher ? (
            <Row label="Було">
              <s className="text-secondary decoration-[var(--text-decoration-color-change)]">
                {[original?.discipline?.shortName ?? lesson.discipline.shortName, original?.teacher?.shortName].filter(Boolean).join(' · ')}
              </s>
            </Row>
          ) : null}
          {replacedRoom ? (
            <Row label="Аудиторія">
              <s className="text-secondary">{roomLabel(original?.room?.name)}</s> → <span className="font-[650] text-room">{roomLabel(lesson.room?.name)}</span>
            </Row>
          ) : null}
          {movedFrom ? <Row label="Перенесено">з {pairOrdinalGenitive(movedFrom)} пари</Row> : null}
          {lesson.reasonText ? (
            <Row label="Причина">
              <span className="font-[650]">{reasonInWords(lesson.reasonText)}</span>
            </Row>
          ) : null}
        </dl>
      ) : null}

      {lesson.teacher || lesson.room ? (
        <div className="flex flex-wrap gap-x-4 border-t border-separator pt-2">
          {lesson.teacher ? (
            <Link to={paths.teacher(lesson.teacher.id)} className={LINK}>
              <span className="u-line">Розклад викладача</span>
            </Link>
          ) : null}
          {lesson.room ? (
            <Link to={paths.room(lesson.room.id)} className={LINK}>
              <span className="u-line">{/^\d/.test(lesson.room.name) ? `Аудиторія ${lesson.room.name}` : capitalize(lesson.room.name)}</span>
            </Link>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
