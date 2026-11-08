import { useMemo, useState } from 'react';
import type { TimetableDraft, TimetableDraftLesson } from '@/api/import';
import type { Weekday } from '@/api/types';
import { HoverDetails, ParityMark, Plate, Select } from '@/components/ui';
import { cn } from '@/lib/cn';
import { disciplineLine } from '@/lib/discipline-color';
import { capitalize, countLabel, PARITY_LABEL, weekdayName } from '@/lib/labels';

const WEEKDAYS: readonly Weekday[] = [1, 2, 3, 4, 5];
const SAMPLE_GROUP = '4КІ1';

/**
 * Чернетка таблиці розкладу: скільки всього розпізнано і тижневий шаблон однієї групи — так, як
 * він ляже в базовий розклад (чисельник і знаменник окремо, підгрупи позначено).
 */
export function TimetablePreview({ draft }: { draft: TimetableDraft }) {
  const codes = useMemo(() => draft.groups.map((g) => g.code), [draft.groups]);
  const [code, setCode] = useState(() => (codes.includes(SAMPLE_GROUP) ? SAMPLE_GROUP : (codes[0] ?? '')));
  const lessons = useMemo(() => draft.lessons.filter((l) => l.groupCode === code), [draft.lessons, code]);
  const names = useMemo(() => new Map(draft.disciplines.map((d) => [d.shortName, d.name])), [draft.disciplines]);
  const bells = draft.bellProfiles[0]?.slots ?? [];
  const pairs = lessons.length > 0 ? range(Math.min(...lessons.map((l) => l.pair)), Math.max(...lessons.map((l) => l.pair))) : [];
  const group = draft.groups.find((g) => g.code === code);

  const stats = [
    { label: 'груп', value: draft.groups.length },
    { label: 'викладачів', value: draft.teachers.length },
    { label: 'дисциплін', value: draft.disciplines.length },
    { label: 'аудиторій', value: draft.rooms.length },
    { label: 'занять у шаблоні', value: draft.lessons.length },
  ];

  return (
    <div className="flex flex-col gap-4">
      <dl className="grid grid-cols-2 gap-3 sm:grid-cols-5">
        {stats.map((stat) => (
          <div key={stat.label} className="flex flex-col-reverse rounded-panel border border-default bg-surface px-3 py-2">
            <dt className="text-caption text-muted">{stat.label}</dt>
            <dd className="font-condensed text-headline font-bold figures text-primary">{stat.value}</dd>
          </div>
        ))}
      </dl>
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="flex flex-col gap-0.5">
          <p className="text-ui font-semibold text-primary">
            {draft.title ?? 'Розклад'} · аркуш «{draft.sheet}»{draft.semesterLabel ? ` · ${draft.semesterLabel}` : ''}
          </p>
          {group ? (
            <p className="text-caption text-muted">
              {group.course} курс · {group.shift} зміна{group.homeRoom ? ` · аудиторія ${group.homeRoom}` : ''}
              {group.curator ? ` · куратор ${group.curator}` : ''}
              {group.raw ? ` · у файлі записано «${group.raw}»` : ''}
            </p>
          ) : null}
        </div>
        <label className="flex items-center gap-2 text-caption font-semibold text-secondary">
          Група
          <Select<string> size="sm" className="w-32" value={code} onValueChange={setCode} options={codes.map((c) => ({ value: c, label: c }))} />
        </label>
      </div>
      {lessons.length === 0 ? (
        <p className="rounded-panel border border-default bg-surface px-4 py-6 text-center text-ui text-secondary">У групи {code} занять у файлі немає.</p>
      ) : (
        <div className="overflow-x-auto rounded-panel border border-default bg-surface">
          <table className="w-full min-w-[46rem] table-fixed border-separate border-spacing-0">
            <caption className="sr-only">Тижневий шаблон групи {code} з чернетки імпорту</caption>
            <colgroup>
              <col className="w-20" />
              {WEEKDAYS.map((day) => (
                <col key={day} />
              ))}
            </colgroup>
            <thead>
              <tr>
                <th scope="col" className="border-e border-b border-default bg-sunken px-2 py-2 text-start">
                  <span className="sr-only">Пара</span>
                </th>
                {WEEKDAYS.map((day) => (
                  <th key={day} scope="col" className="border-b border-default bg-sunken px-3 py-2 text-start text-ui font-semibold text-primary">
                    {capitalize(weekdayName(day))}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {pairs.map((pair) => {
                const time = bells.find((slot) => slot.weekday === 1 && slot.pair === pair);
                return (
                  <tr key={pair}>
                    <th scope="row" className="border-e border-b border-separator px-2 py-2 text-start align-top">
                      <span className="block font-condensed text-headline leading-none font-bold figures text-primary">{pair}</span>
                      {time ? <span className="text-meta font-normal text-muted figures">{time.startsAt}</span> : null}
                    </th>
                    {WEEKDAYS.map((day) => (
                      <td key={day} className="border-b border-separator p-1 align-top">
                        <Cell lessons={lessons.filter((l) => l.weekday === day && l.pair === pair)} names={names} />
                      </td>
                    ))}
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      <p className="text-caption text-muted">
        {countLabel(lessons.length, ['заняття', 'заняття', 'занять'])} групи {code}. Наведіть на пару, щоб побачити, де вона у файлі.
      </p>
    </div>
  );
}

function range(from: number, to: number): number[] {
  return Array.from({ length: to - from + 1 }, (_, i) => from + i);
}

function Cell({ lessons, names }: { lessons: TimetableDraftLesson[]; names: ReadonlyMap<string, string> }) {
  if (lessons.length === 0) return <span className="block px-2 py-1.5 text-muted" aria-label="Пари немає">—</span>;
  const both = lessons.filter((l) => l.parity === 'BOTH');
  const numerator = lessons.filter((l) => l.parity === 'NUMERATOR');
  const denominator = lessons.filter((l) => l.parity === 'DENOMINATOR');
  return (
    <div className="flex flex-col gap-1">
      {both.map((lesson, i) => (
        <LessonChip key={`b-${i}`} lesson={lesson} names={names} />
      ))}
      {numerator.length > 0 || denominator.length > 0 ? (
        <div className="grid grid-rows-2 gap-1">
          {[numerator, denominator].map((list, index) =>
            list.length > 0 ? (
              list.map((lesson, i) => <LessonChip key={`${index}-${i}`} lesson={lesson} names={names} />)
            ) : (
              <span
                key={`empty-${index}`}
                aria-label={index === 0 ? 'Чисельник: пари немає' : 'Знаменник: пари немає'}
                className="hatch flex items-center gap-1 rounded-plate px-2 py-1 text-meta text-muted"
              >
                <ParityMark parity={index === 0 ? 'NUMERATOR' : 'DENOMINATOR'} className="h-3 w-2.5" />
                немає
              </span>
            ),
          )}
        </div>
      ) : null}
    </div>
  );
}

function LessonChip({ lesson, names }: { lesson: TimetableDraftLesson; names: ReadonlyMap<string, string> }) {
  const name = names.get(lesson.discipline) ?? lesson.discipline;
  const line = disciplineLine({ id: lesson.discipline, color: null, shortName: lesson.discipline });
  return (
    <HoverDetails
      label={`${lesson.discipline}, ${lesson.pair} пара`}
      content={
        <div className="flex flex-col gap-1 text-ui">
          <p className="font-semibold text-primary">{name}</p>
          <p className="text-secondary">
            {lesson.teacher ?? 'Викладача не вказано'}
            {lesson.room ? ` · ауд. ${lesson.room}` : ''}
          </p>
          <p className="text-caption text-secondary">
            {capitalize(weekdayName(lesson.weekday))}, {lesson.pair} пара · {PARITY_LABEL[lesson.parity]}
            {lesson.subgroup > 0 ? ` · ${lesson.subgroup} підгрупа` : ''}
          </p>
          {lesson.location ? <p className="text-meta text-muted">{lesson.location}</p> : null}
        </div>
      }
    >
      <button
        type="button"
        data-line={line}
        className={cn(
          'flex w-full min-w-0 flex-col items-start rounded-plate px-2 py-1 text-start',
          'hover:bg-[var(--line-tint)] focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-focus',
        )}
      >
        <span className="flex w-full min-w-0 items-center gap-1.5">
          <span aria-hidden="true" className="size-2 shrink-0 rounded-full bg-[var(--line-dot)]" />
          <span className="truncate font-condensed text-ui font-bold text-primary">{lesson.discipline}</span>
          {lesson.parity !== 'BOTH' ? (
            <span className="ms-auto flex shrink-0 items-center gap-0.5 text-meta text-secondary">
              <ParityMark parity={lesson.parity} className="h-3 w-2.5" />
              {lesson.parity === 'NUMERATOR' ? 'Ч' : 'З'}
            </span>
          ) : null}
        </span>
        <span className="w-full truncate text-caption text-secondary">
          {lesson.subgroup > 0 ? `${lesson.subgroup} п. · ` : ''}
          {lesson.teacher ?? '—'}
        </span>
        {lesson.room ? <Plate size="sm" className="mt-0.5">{lesson.room}</Plate> : null}
      </button>
    </HoverDetails>
  );
}
