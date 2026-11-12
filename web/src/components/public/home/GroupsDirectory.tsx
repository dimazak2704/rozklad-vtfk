import { ChevronDown } from 'lucide-react';
import { useId, useState } from 'react';
import { Link } from 'react-router';
import { usePublicGroups } from '@/api/public';
import type { PublicGroupDto } from '@/api/types';
import { paths } from '@/app/routes';
import { ErrorState, Skeleton, useErrorCovered } from '@/components/ui';
import { cn } from '@/lib/cn';
import { countLabel } from '@/lib/labels';
import { groupSpecialtyName } from '@/lib/names';
import { useStalled } from '../useStalled';
import { useRememberedGroup } from './useRememberedGroup';

/** Які курси людина тримає відкритими: `[4]`, `[1, 3]`. Немає запису — відкритий лише курс своєї групи. */
export const OPEN_COURSES_KEY = 'rozklad.home.openCourses';

function readOpenCourses(): number[] | null {
  try {
    const raw = localStorage.getItem(OPEN_COURSES_KEY);
    if (raw === null) return null;
    const parsed: unknown = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.filter((value): value is number => Number.isInteger(value)) : null;
  } catch {
    return null;
  }
}

function writeOpenCourses(courses: readonly number[]) {
  try {
    localStorage.setItem(OPEN_COURSES_KEY, JSON.stringify(courses));
  } catch {
    // сховище недоступне — вибір діє до перезавантаження
  }
}

/**
 * Коди груп курсу — сіткою з однаковими колонками (ціль 44 px, без рамок), тож рядки рівні й ніде не
 * лишається «сирота» на окремому рядку. Своя група — тонованим тлом.
 */
function CourseGrid({ list, ownId }: { list: readonly PublicGroupDto[]; ownId: string | null }) {
  return (
    <ul className="-mx-2 grid grid-cols-[repeat(auto-fill,minmax(4.5rem,1fr))]">
      {list.map((group) => (
        <li key={group.id}>
          <Link
            to={paths.group(group.code)}
            title={groupSpecialtyName(group) ?? undefined}
            aria-current={group.id === ownId ? 'true' : undefined}
            className={cn(
              'flex h-11 items-center rounded-control px-2 text-ui font-semibold text-primary figures',
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

function CourseDisclosure({
  course,
  list,
  ownId,
  open,
  onToggle,
}: {
  course: number;
  list: readonly PublicGroupDto[];
  ownId: string | null;
  open: boolean;
  onToggle: () => void;
}) {
  const panelId = useId();
  return (
    <li className="border-b border-separator last:border-b-0">
      <button
        type="button"
        aria-expanded={open}
        aria-controls={panelId}
        onClick={onToggle}
        className="flex min-h-11 w-full items-center justify-between gap-3 rounded-control text-start text-ui font-semibold text-primary hover:text-accent focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus"
      >
        <span>
          {course} курс <span className="font-normal text-muted">· {countLabel(list.length, ['група', 'групи', 'груп'])}</span>
        </span>
        <ChevronDown aria-hidden="true" className={cn('size-4 text-muted motion-safe:transition-transform motion-safe:duration-200', open && 'rotate-180')} />
      </button>
      <div id={panelId} hidden={!open} className="pb-3">
        <CourseGrid list={list} ownId={ownId} />
      </div>
    </li>
  );
}

/**
 * «Усі групи» — чотири рядки курсів, а не стіна з 56 кодів. Курс розкривається на місці; відкриті курси
 * запамʼятовуються. Поки людина нічого не відкривала, відкритий лише курс її групи (на першому візиті —
 * жоден: спершу пошук, список — під рукою).
 */
export function GroupsDirectory({ className }: { className?: string }) {
  const groups = usePublicGroups();
  const stall = useStalled([groups]);
  const covered = useErrorCovered();
  const remembered = useRememberedGroup();
  const [stored, setStored] = useState(readOpenCourses);

  if (groups.isError && !groups.data && covered) return null;
  if (groups.isPending && stall) return null;
  let body;
  if (groups.isPending) {
    body = (
      <div className="flex flex-col gap-3" aria-hidden="true">
        {[0, 1, 2, 3].map((index) => (
          <Skeleton key={index} className="h-5 w-40" />
        ))}
      </div>
    );
  } else if (groups.isError) {
    body = <ErrorState error={groups.error} size="sm" align="start" onRetry={() => void groups.refetch()} retrying={groups.isFetching} />;
  } else {
    const courses = [...new Set(groups.data.map((g) => g.course))].sort((a, b) => a - b);
    const own = remembered ? (groups.data.find((g) => g.id === remembered.id) ?? null) : null;
    const open = stored ?? (own ? [own.course] : []);
    const toggle = (course: number) => {
      const next = open.includes(course) ? open.filter((c) => c !== course) : [...open, course].sort((a, b) => a - b);
      setStored(next);
      writeOpenCourses(next);
    };
    body = (
      <ul className="flex flex-col">
        {courses.map((course) => (
          <CourseDisclosure
            key={course}
            course={course}
            list={groups.data.filter((g) => g.course === course)}
            ownId={own?.id ?? null}
            open={open.includes(course)}
            onToggle={() => toggle(course)}
          />
        ))}
      </ul>
    );
  }

  return (
    <section aria-labelledby="groups-heading" className={cn('flex flex-col gap-1', className)}>
      <h2 id="groups-heading" className="text-heading font-bold text-primary">
        Усі групи
      </h2>
      {body}
    </section>
  );
}
