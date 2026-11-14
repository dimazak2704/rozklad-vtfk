import { useMemo } from 'react';
import { useParams } from 'react-router';
import { isApiError } from '@/api/errors';
import { usePublicSchedule, usePublicTeachers } from '@/api/public';
import { paths } from '@/app/routes';
import { isHistoryClosed } from '@/components/public/history';
import { ScheduleHeaderSkeleton, ScheduleLoadError } from '@/components/public/schedule/ScheduleHeader';
import { SchedulePage } from '@/components/public/schedule/SchedulePage';
import { ScheduleSkeleton } from '@/components/public/schedule/ScheduleSkeleton';
import { useScheduleParams } from '@/components/public/schedule/schedule-params';
import { mainDisciplines } from '@/components/public/schedule/schedule-utils';
import { TargetNotFound } from '@/components/public/schedule/TargetNotFound';
import { formatTeacherName } from '@/lib/teacher-name';

/** Розклад викладача /t/:id — «Прізвище Імʼя По батькові» лише з дозволу налаштувань, інакше з ініціалами (UI.md §1). */
export default function TeacherSchedulePage() {
  const { id = '' } = useParams();
  const params = useScheduleParams();
  const teachers = usePublicTeachers();
  // id відомий з адреси — розклад вантажимо одразу, паралельно з довідником
  const query = usePublicSchedule('TEACHER', id, params.week.from, params.week.to);
  const teacher = teachers.data?.find((t) => t.id === id);
  // «Прізвище Імʼя По батькові», якщо імʼя відоме й налаштування дозволяє його показувати; інакше «Кренцін М. Д.»
  const label = teacher ? formatTeacherName(teacher, 'full') : query.data?.owner.label;
  const loaded = query.isPlaceholderData ? undefined : query.data;
  // одна-дві дисципліни — повними назвами: клітинки кажуть лише скорочення, а підзаголовок має місце
  const disciplines = useMemo(() => {
    const short = mainDisciplines(loaded);
    return (short.length <= 2 ? mainDisciplines(loaded, 2, 'full') : short).join(', ');
  }, [loaded]);

  // 404 «архів недоступний» (давній тиждень для гостя) — не «такого викладача немає»: пояснює сам розклад
  const historyClosed = isHistoryClosed(query.error);
  const missing = (isApiError(query.error) && query.error.isNotFound && !historyClosed) || (teachers.isSuccess && !teacher);
  if (missing) {
    return (
      <TargetNotFound
        title="Такого викладача в розкладі немає"
        description="Посилання могло застаріти: знайдіть викладача за прізвищем — пошук розуміє й англійську розкладку."
      />
    );
  }

  if (!label) {
    if (query.isError && !historyClosed) {
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

  // під іменем — циклова комісія і що викладач веде цього тижня («СТП, ДК»): імʼя саме по собі мало каже
  const details = [teacher?.cycleCommission, disciplines ? `Веде ${disciplines}` : null].filter(Boolean).join(' · ');

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
