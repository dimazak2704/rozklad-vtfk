import { useMemo } from 'react';
import { useParams } from 'react-router';
import { isApiError } from '@/api/errors';
import { usePublicSchedule, usePublicTeachers } from '@/api/public';
import type { ScheduleDto } from '@/api/types';
import { paths } from '@/app/routes';
import { ScheduleHeaderSkeleton } from '@/components/public/schedule/ScheduleHeader';
import { SchedulePage } from '@/components/public/schedule/SchedulePage';
import { ScheduleSkeleton } from '@/components/public/schedule/ScheduleSkeleton';
import { useScheduleParams } from '@/components/public/schedule/schedule-params';
import { TargetNotFound } from '@/components/public/schedule/TargetNotFound';
import { ErrorState } from '@/components/ui';

const UK = new Intl.Collator('uk');

/** «Цього тижня: СТП · 4КІ1, 4КІ2, 4КІ3» — що й кому викладач веде (з розкладу тижня на екрані). */
function weekSummary(schedule: ScheduleDto | undefined, teacherId: string): string | null {
  if (!schedule) return null;
  const disciplines = new Set<string>();
  const groups = new Set<string>();
  for (const day of schedule.days) {
    for (const lesson of day.lessons) {
      if (lesson.teacher?.id !== teacherId || lesson.kind === 'CANCELLED') continue;
      disciplines.add(lesson.discipline.shortName);
      groups.add(lesson.group.code);
    }
  }
  if (disciplines.size === 0) return null;
  const sorted = (values: Set<string>) => [...values].sort((a, b) => UK.compare(a, b)).join(', ');
  return `Цього тижня: ${sorted(disciplines)} · ${sorted(groups)}`;
}

/** Розклад викладача /t/:id — публічні короткі імена (UI.md §1). */
export default function TeacherSchedulePage() {
  const { id = '' } = useParams();
  const params = useScheduleParams();
  const teachers = usePublicTeachers();
  // id відомий з адреси — розклад вантажимо одразу, паралельно з довідником
  const query = usePublicSchedule('TEACHER', id, params.week.from, params.week.to);
  const teacher = teachers.data?.find((t) => t.id === id);
  const label = teacher?.shortName ?? query.data?.owner.label;
  const summary = useMemo(() => weekSummary(query.data, id), [query.data, id]);

  const missing = (isApiError(query.error) && query.error.isNotFound) || (teachers.isSuccess && !teacher);
  if (missing) {
    return (
      <TargetNotFound
        title="Такого викладача в розкладі немає"
        description="Посилання могло застаріти: знайдіть викладача за прізвищем — пошук розуміє й англійську розкладку."
      />
    );
  }

  if (!label) {
    if (query.isError) {
      return <ErrorState error={query.error} onRetry={() => void query.refetch()} retrying={query.isFetching} />;
    }
    return (
      <div className="flex flex-col gap-6">
        <ScheduleHeaderSkeleton />
        <ScheduleSkeleton view={params.view} />
      </div>
    );
  }

  // повне імʼя core надсилає лише тоді, коли це дозволено налаштуванням showTeacherFullNames
  const details = [teacher?.fullName ?? null, teacher?.cycleCommission ? `Циклова комісія: ${teacher.cycleCommission}` : null, summary]
    .filter(Boolean)
    .join(' · ');

  return (
    <SchedulePage
      target={{ type: 'TEACHER', id, label, url: paths.teacher(id) }}
      kindLabel="Розклад викладача"
      documentTitle={`${label} — розклад викладача`}
      details={details || undefined}
      perspective="teacher"
      query={query}
      params={params}
      ics={{ owner: 'TEACHER', id, fileName: `rozklad-${label.replace(/[^\p{L}\p{N}]+/gu, '-')}.ics` }}
    />
  );
}
