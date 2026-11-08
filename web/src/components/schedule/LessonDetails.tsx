import { ArrowRight, Clock, DoorOpen, MonitorSmartphone, User, Users } from 'lucide-react';
import type { ReactNode } from 'react';
import type { IsoDate, LessonView, WeekParity } from '@/api/types';
import { cn } from '@/lib/cn';
import { formatWeekdayDate } from '@/lib/date';
import { lineAttr } from '@/lib/discipline-color';
import { LESSON_VIEW_KIND_LABEL, PARITY_LABEL, REASON_CODE_HINT, REASON_CODE_LABEL, ROOM_TYPE_LABEL, subgroupLabel } from '@/lib/labels';
import { pairOrdinalGenitive, pairOrdinalLabel } from '@/lib/ordinal';
import { LESSON_TONE, roomLabel } from '@/lib/schedule';
import { Badge } from '../ui/Badge';
import { Plate } from '../ui/Plate';
import { TONE_BADGE } from './tones';

function Row({ icon, label, children }: { icon: ReactNode; label: string; children: ReactNode }) {
  return (
    <div className="grid grid-cols-[1rem_minmax(0,1fr)] items-start gap-x-2.5">
      <span className="mt-0.5 text-muted [&_svg]:size-4" aria-hidden="true">
        {icon}
      </span>
      <p className="text-ui text-primary">
        <span className="sr-only">{label}: </span>
        {children}
      </p>
    </div>
  );
}

interface LessonDetailsProps {
  lesson: LessonView;
  date?: IsoDate;
  dayParity?: WeekParity;
}

/** Скорочення з розпорядження («Зв.зам.») — словами («взаємна заміна»): у публічній картці кодів не лишаємо. */
function reasonInWords(text: string): string {
  const code = (Object.keys(REASON_CODE_LABEL) as Array<keyof typeof REASON_CODE_LABEL>).find(
    (key) => REASON_CODE_LABEL[key].toLocaleLowerCase('uk') === text.trim().toLocaleLowerCase('uk'),
  );
  return code ? REASON_CODE_HINT[code].toLocaleLowerCase('uk') : text;
}

/**
 * Повні відомості про заняття для картки при наведенні чи шторки на телефоні (UI.md §3).
 * Лише тут предмет має свій колір лінії — у сітці його називає коротка назва.
 */
export function LessonDetails({ lesson, date, dayParity }: LessonDetailsProps) {
  const tone = LESSON_TONE[lesson.kind];
  const original = lesson.original;
  const subgroup = subgroupLabel(lesson.subgroup);
  const replacedDiscipline = original?.discipline && original.discipline.id !== lesson.discipline.id;
  const replacedTeacher = original?.teacher && original.teacher.id !== lesson.teacher?.id;
  const replacedRoom = original?.room && original.room.id !== lesson.room?.id;
  const movedFrom = original?.pair && original.pair !== lesson.pair ? original.pair : null;

  return (
    <div className="flex w-full flex-col gap-3 sm:w-[min(20rem,calc(100vw-3rem))]">
      <div className="flex items-start justify-between gap-3">
        <div className="flex min-w-0 flex-col gap-0.5">
          <p className="flex items-center gap-2 font-condensed text-heading leading-tight font-bold">
            <span {...lineAttr(lesson.discipline)} className="size-2.5 shrink-0 rounded-full bg-[var(--line-dot)]" aria-hidden="true" />
            <span className={cn(tone === 'cancel' && 'line-through decoration-[var(--text-decoration-color-cancel)] decoration-2')}>
              {lesson.discipline.shortName}
            </span>
          </p>
          {lesson.discipline.name && lesson.discipline.name !== lesson.discipline.shortName ? (
            <p className="text-caption text-secondary">{lesson.discipline.name}</p>
          ) : null}
        </div>
        {tone !== 'regular' ? (
          <Badge tone={TONE_BADGE[tone]} size="sm">
            {LESSON_VIEW_KIND_LABEL[lesson.kind]}
          </Badge>
        ) : null}
      </div>

      <div className="flex flex-col gap-1.5">
        <Row icon={<Clock />} label="Час">
          <span className="figures">
            {pairOrdinalLabel(lesson.pair)} · {lesson.startsAt}–{lesson.endsAt}
          </span>
          {date ? <span className="block text-caption text-secondary">{formatWeekdayDate(date)}</span> : null}
        </Row>
        <Row icon={<User />} label="Викладач">
          {lesson.teacher ? lesson.teacher.shortName : <span className="text-muted">не призначено</span>}
        </Row>
        <Row icon={<DoorOpen />} label="Аудиторія">
          {lesson.room ? (
            <>
              {roomLabel(lesson.room.name)}
              <span className="text-secondary"> · {ROOM_TYPE_LABEL[lesson.room.type]}</span>
            </>
          ) : (
            <span className="text-muted">не вказано</span>
          )}
        </Row>
        <Row icon={<Users />} label="Група">
          <Plate size="sm">{lesson.group.code}</Plate>
          {subgroup ? <span className="text-secondary"> · {subgroup}</span> : null}
        </Row>
        {lesson.online ? (
          <Row icon={<MonitorSmartphone />} label="Формат">
            Дистанційно
          </Row>
        ) : null}
      </div>

      {tone !== 'regular' && tone !== 'block' && (replacedDiscipline || replacedTeacher || replacedRoom || movedFrom || lesson.reasonText) ? (
        <div className="flex flex-col gap-1.5 border-t border-separator pt-2.5 text-ui">
          {replacedDiscipline || replacedTeacher ? (
            <p className="flex flex-wrap items-center gap-x-1.5 gap-y-0.5">
              <span className="text-muted line-through decoration-[var(--text-decoration-color-cancel)]">
                {[original?.discipline?.shortName ?? lesson.discipline.shortName, original?.teacher?.shortName]
                  .filter(Boolean)
                  .join(', ')}
              </span>
              <ArrowRight aria-label="замінено на" className="size-3.5 text-muted rtl:rotate-180" />
              <span className="font-semibold text-primary">
                {[lesson.discipline.shortName, lesson.teacher?.shortName].filter(Boolean).join(', ')}
              </span>
            </p>
          ) : null}
          {replacedRoom ? (
            <p className="flex items-center gap-1.5">
              <span className="text-muted line-through">{roomLabel(original?.room?.name)}</span>
              <ArrowRight aria-label="перенесено в" className="size-3.5 text-muted rtl:rotate-180" />
              <span className="font-semibold text-room">{roomLabel(lesson.room?.name)}</span>
            </p>
          ) : null}
          {movedFrom ? <p className="text-secondary">Перенесено з {pairOrdinalGenitive(movedFrom)} пари</p> : null}
          {lesson.reasonText ? (
            <p className="text-secondary">
              Причина: <span className="font-semibold text-primary">{reasonInWords(lesson.reasonText)}</span>
            </p>
          ) : null}
        </div>
      ) : null}

      {dayParity ? (
        <p className="border-t border-separator pt-2.5 text-caption text-secondary">
          <span>
            Цього тижня <span className="font-semibold text-primary">{PARITY_LABEL[dayParity]}</span>:{' '}
            {dayParity === 'NUMERATOR' ? 'діють пари з верхнього рядка' : 'діють пари з нижнього рядка'} друкованого розкладу
          </span>
        </p>
      ) : null}
    </div>
  );
}
