import { Layers, MonitorSmartphone, Pin, TriangleExclamationPoint } from 'lucide-react';
import type { TimetableConflict } from '@/api/timetable';
import type { TemplateLessonDto } from '@/api/types';
import { ParityMark } from '@/components/ui';
import { lineAttr } from '@/lib/discipline-color';
import { capitalize, LESSON_KIND_LABEL, weekdayName } from '@/lib/labels';
import { roomLabel } from '@/lib/schedule';
import { PARITY_SHORT } from '../model/labels';

interface LessonDetailsCardProps {
  lesson: TemplateLessonDto;
  conflicts: readonly TimetableConflict[];
}

/** Повні відомості про пару шаблону: назва, хто, де, коли, парність простими словами, конфлікти. */
export function LessonDetailsCard({ lesson, conflicts }: LessonDetailsCardProps) {
  const hard = conflicts.filter((c) => c.severity === 'HARD');
  const soft = conflicts.filter((c) => c.severity === 'SOFT');
  return (
    <div className="flex w-72 max-w-full flex-col gap-2.5" {...lineAttr(lesson.discipline)}>
      <div className="flex items-start gap-2">
        <span aria-hidden="true" className="mt-1.5 size-2.5 shrink-0 rounded-full bg-[var(--line-dot)]" />
        <div className="flex min-w-0 flex-col">
          <p className="font-condensed text-heading leading-tight font-bold text-primary">{lesson.discipline.shortName}</p>
          {lesson.discipline.name !== lesson.discipline.shortName ? (
            <p className="text-caption text-secondary">{lesson.discipline.name}</p>
          ) : null}
        </div>
      </div>

      <dl className="grid grid-cols-[5.5rem_minmax(0,1fr)] gap-x-3 gap-y-1 text-caption">
        <dt className="text-muted">Група</dt>
        <dd className="font-semibold text-primary">
          {lesson.group.code}
          {lesson.subgroup > 0 ? <span className="font-normal text-secondary"> · {lesson.subgroup} підгрупа</span> : null}
        </dd>
        <dt className="text-muted">Викладач</dt>
        <dd className="text-primary">{lesson.teacher?.shortName ?? <span className="text-muted">не призначено</span>}</dd>
        <dt className="text-muted">Аудиторія</dt>
        <dd className="text-primary figures">{roomLabel(lesson.room?.name) ?? <span className="text-muted">не призначено</span>}</dd>
        <dt className="text-muted">Коли</dt>
        <dd className="text-primary">
          {capitalize(weekdayName(lesson.weekday))}, {lesson.pair} пара
        </dd>
        <dt className="text-muted">Тижні</dt>
        <dd className="flex items-center gap-1.5 text-primary">
          {lesson.parity !== 'BOTH' ? <ParityMark parity={lesson.parity} className="text-accent" /> : null}
          {lesson.parity === 'BOTH' ? 'щотижня' : `лише ${PARITY_SHORT[lesson.parity]}`}
        </dd>
        <dt className="text-muted">Вид</dt>
        <dd className="text-primary">{LESSON_KIND_LABEL[lesson.lessonKind]}</dd>
      </dl>

      {lesson.pinned || lesson.online || lesson.streamId ? (
        <ul className="flex flex-wrap gap-x-3 gap-y-1 text-caption text-secondary">
          {lesson.pinned ? (
            <li className="inline-flex items-center gap-1">
              <Pin aria-hidden="true" className="size-3.5" />
              Закріплена: генератор її не рухає
            </li>
          ) : null}
          {lesson.online ? (
            <li className="inline-flex items-center gap-1">
              <MonitorSmartphone aria-hidden="true" className="size-3.5" />
              Онлайн
            </li>
          ) : null}
          {lesson.streamId ? (
            <li className="inline-flex items-center gap-1">
              <Layers aria-hidden="true" className="size-3.5" />
              Потік: кілька груп разом, переноситься разом
            </li>
          ) : null}
        </ul>
      ) : null}

      {hard.length > 0 || soft.length > 0 ? (
        <ul className="flex flex-col gap-1.5 border-t border-separator pt-2 text-caption">
          {hard.map((conflict) => (
            <li key={`h-${conflict.kind}-${conflict.message}`} className="flex gap-1.5 text-danger">
              <TriangleExclamationPoint aria-hidden="true" className="mt-0.5 size-3.5" />
              <span>{conflict.message}</span>
            </li>
          ))}
          {soft.slice(0, 3).map((conflict) => (
            <li key={`s-${conflict.kind}-${conflict.message}`} className="flex gap-1.5 text-change">
              <span aria-hidden="true" className="mt-1.5 size-1.5 shrink-0 rounded-full bg-[var(--fill-change)]" />
              <span>{conflict.message}</span>
            </li>
          ))}
          {soft.length > 3 ? <li className="text-muted">і ще {soft.length - 3} попередж.</li> : null}
        </ul>
      ) : null}
    </div>
  );
}
