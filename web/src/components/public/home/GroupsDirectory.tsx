import { ChevronDown } from 'lucide-react';
import { useId, useState } from 'react';
import { Link } from 'react-router';
import { usePublicGroups } from '@/api/public';
import type { PublicGroupDto } from '@/api/types';
import { paths } from '@/app/routes';
import { ErrorState, Skeleton, useErrorCovered } from '@/components/ui';
import { useMediaQuery } from '@/hooks/useMediaQuery';
import { cn } from '@/lib/cn';
import { countLabel } from '@/lib/labels';
import { groupSpecialtyName } from '@/lib/names';
import { usePersistentFlag } from '../usePersistentFlag';
import { useStalled } from '../useStalled';
import { useRememberedGroup } from './useRememberedGroup';

const COURSE_WORDS = ['', 'один курс', 'два курси', 'три курси', 'чотири курси', 'пʼять курсів', 'шість курсів'] as const;

const TOGGLE = cn(
  'inline-flex min-h-8 w-fit items-center gap-1 rounded-tag text-ui font-[650] text-accent underline-offset-4 hover:underline pointer-coarse:min-h-11',
  'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus',
);

/** Коди груп курсу — прості текстові посилання (ціль 44 px), без рамок; своя група — тонованим тлом. */
function CourseLinks({ list, ownId }: { list: readonly PublicGroupDto[]; ownId: string | null }) {
  return (
    <ul className="-ms-2 flex flex-wrap">
      {list.map((group) => (
        <li key={group.id}>
          <Link
            to={paths.group(group.code)}
            title={groupSpecialtyName(group) ?? undefined}
            aria-current={group.id === ownId ? 'true' : undefined}
            className={cn(
              'inline-flex h-11 min-w-14 items-center justify-center rounded-control px-2 text-ui font-semibold text-primary figures',
              'transition-[background-color,color] duration-150 hover:bg-hover hover:text-accent',
              'focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus',
              group.id === ownId && 'bg-selected text-accent hover:bg-selected',
            )}
          >
            {group.code}
          </Link>
        </li>
      ))}
    </ul>
  );
}

/** Курс на телефоні — власне розкриття; свій курс відкритий одразу. */
function CourseDisclosure({ course, list, ownId, defaultOpen }: { course: number; list: readonly PublicGroupDto[]; ownId: string | null; defaultOpen: boolean }) {
  const [open, setOpen] = useState(defaultOpen);
  const panelId = useId();
  return (
    <li className="border-b border-separator last:border-b-0">
      <button
        type="button"
        aria-expanded={open}
        aria-controls={panelId}
        onClick={() => setOpen(!open)}
        className="flex min-h-11 w-full items-center justify-between gap-3 rounded-control text-start text-ui font-semibold text-primary focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus"
      >
        <span>
          {course} курс <span className="font-normal text-muted">· {countLabel(list.length, ['група', 'групи', 'груп'])}</span>
        </span>
        <ChevronDown aria-hidden="true" className={cn('size-4 text-muted motion-safe:transition-transform motion-safe:duration-200', open && 'rotate-180')} />
      </button>
      <div id={panelId} hidden={!open} className="pb-2">
        <CourseLinks list={list} ownId={ownId} />
      </div>
    </li>
  );
}

/**
 * «Усі групи» — розкриття, а не стіна з 56 табличок. Закрито: одне речення. Відкрито: курси рядками
 * простих посилань. Стан запамʼятовується; на телефоні курси згорнуті, крім курсу своєї групи.
 * `expanded` — перший візит (своєї групи ще немає): список одразу відкритий, без перемикача —
 * людині саме його й треба.
 */
export function GroupsDirectory({ expanded = false }: { expanded?: boolean }) {
  const groups = usePublicGroups();
  const stall = useStalled([groups]);
  const covered = useErrorCovered();
  const remembered = useRememberedGroup();
  const [rememberedOpen, setOpen] = usePersistentFlag('rozklad.home.groupsOpen', false);
  const open = expanded || rememberedOpen;
  const phone = useMediaQuery('(max-width: 767px)');
  const panelId = useId();

  if (groups.isError && !groups.data && covered) return null;
  if (groups.isPending && stall) return null;
  let body;
  if (groups.isPending) {
    body = <Skeleton className="h-4 w-48" />;
  } else if (groups.isError) {
    body = <ErrorState error={groups.error} size="sm" align="start" onRetry={() => void groups.refetch()} retrying={groups.isFetching} />;
  } else {
    const courses = [...new Set(groups.data.map((g) => g.course))].sort((a, b) => a - b);
    const own = remembered ? (groups.data.find((g) => g.id === remembered.id) ?? null) : null;
    const byCourse = (course: number) => groups.data.filter((g) => g.course === course);
    body = (
      <>
        {expanded ? null : (
          <>
            <p className="text-body text-secondary">
              {countLabel(groups.data.length, ['група', 'групи', 'груп'])}, {COURSE_WORDS[courses.length] ?? `${courses.length} курсів`}.
            </p>
            <button type="button" aria-expanded={open} aria-controls={panelId} onClick={() => setOpen(!open)} className={TOGGLE}>
              {open ? 'Згорнути список' : 'Відкрити список'}
              <ChevronDown aria-hidden="true" className={cn('size-4 motion-safe:transition-transform motion-safe:duration-200', open && 'rotate-180')} />
            </button>
          </>
        )}
        <div id={panelId} hidden={!open}>
          {phone ? (
            <ul className="flex flex-col">
              {courses.map((course) => (
                <CourseDisclosure
                  key={course}
                  course={course}
                  list={byCourse(course)}
                  ownId={own?.id ?? null}
                  defaultOpen={own?.course === course}
                />
              ))}
            </ul>
          ) : (
            <dl className="flex flex-col gap-3 pt-1">
              {courses.map((course) => (
                <div key={course} className="flex flex-col gap-0.5">
                  <dt className="text-caption font-semibold text-muted">{course} курс</dt>
                  <dd>
                    <CourseLinks list={byCourse(course)} ownId={own?.id ?? null} />
                  </dd>
                </div>
              ))}
            </dl>
          )}
        </div>
      </>
    );
  }

  return (
    <section aria-labelledby="groups-heading" className="flex flex-col gap-2">
      <h2 id="groups-heading" className="text-heading font-bold text-primary">
        Усі групи
      </h2>
      {body}
    </section>
  );
}
