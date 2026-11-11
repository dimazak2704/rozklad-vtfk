import { useMemo } from 'react';
import { useParams } from 'react-router';
import { isApiError } from '@/api/errors';
import { usePublicSchedule, usePublicTeachers } from '@/api/public';
import { paths } from '@/app/routes';
import { ScheduleHeaderSkeleton, ScheduleLoadError } from '@/components/public/schedule/ScheduleHeader';
import { SchedulePage } from '@/components/public/schedule/SchedulePage';
import { ScheduleSkeleton } from '@/components/public/schedule/ScheduleSkeleton';
import { useScheduleParams } from '@/components/public/schedule/schedule-params';
import { mainDisciplines } from '@/components/public/schedule/schedule-utils';
import { TargetNotFound } from '@/components/public/schedule/TargetNotFound';

/** Розклад викладача /t/:id — публічні короткі імена (UI.md §1). */
export default function TeacherSchedulePage() {
  const { id = '' } = useParams();
  const params = useScheduleParams();
  const teachers = usePublicTeachers();
  // id відомий з адреси — розклад вантажимо одразу, паралельно з довідником
  const query = usePublicSchedule('TEACHER', id, params.week.from, params.week.to);
  const teacher = teachers.data?.find((t) => t.id === id);
  const label = teacher?.shortName ?? query.data?.owner.label;
  const loaded = query.isPlaceholderData ? undefined : query.data;
  const disciplines = useMemo(() => mainDisciplines(loaded).join(', '), [loaded]);

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
      return (
        <ScheduleLoadError heading="Розклад викладача" error={query.error} onRetry={() => void query.refetch()} retrying={query.isFetching} />
      );
    }
    return (
      <div className="flex flex-col gap-6">
        <ScheduleHeaderSkeleton />
        <ScheduleSkeleton view={params.view} />
      </div>
    );
  }

  // повне імʼя core надсилає лише тоді, коли це дозволено налаштуванням showTeacherFullNames; інакше — імʼя з
  // ініціалами чи циклова комісія. Поруч — що викладач веде цього тижня («СТП, ДК»): прізвище саме по собі мало каже
  const who = teacher?.fullName ?? (teacher?.displayName && teacher.displayName !== label ? teacher.displayName : null) ?? teacher?.cycleCommission ?? null;
  const details = [who, disciplines ? `Веде ${disciplines}` : null].filter(Boolean).join(' · ');

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
