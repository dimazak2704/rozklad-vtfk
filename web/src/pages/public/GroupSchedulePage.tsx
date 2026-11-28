import { useMemo } from 'react';
import { Navigate, useLocation, useParams } from 'react-router';
import { usePublicGroups, usePublicSchedule } from '@/api/public';
import type { PublicGroupDto } from '@/api/types';
import { paths } from '@/app/routes';
import { groupHintText } from '@/components/public/group-hint';
import { ScheduleHeaderSkeleton, ScheduleLoadError } from '@/components/public/schedule/ScheduleHeader';
import { SchedulePage } from '@/components/public/schedule/SchedulePage';
import { ScheduleSkeleton } from '@/components/public/schedule/ScheduleSkeleton';
import { useScheduleParams } from '@/components/public/schedule/schedule-params';
import { subgroupsIn } from '@/components/public/schedule/schedule-utils';
import { TargetNotFound } from '@/components/public/schedule/TargetNotFound';
import { canonicalGroupCode, fuzzyFilter } from '@/lib/search';
import { safeDecode } from '@/lib/url';

/** «Компʼютерна інженерія · 4 курс» (те саме, що в підказках до кодів). Зміну навчання не показуємо: «1 зміна» читається як «одна заміна». */
function groupDetails(group: PublicGroupDto): string {
  return groupHintText(group);
}

function GroupSchedule({ group }: { group: PublicGroupDto }) {
  const params = useScheduleParams({ rememberSubgroupFor: group.id });
  const query = usePublicSchedule('GROUP', group.id, params.week.from, params.week.to);
  const subgroups = useMemo(() => subgroupsIn(query.data), [query.data]);
  const url = paths.group(group.code);

  return (
    <SchedulePage
      target={{ type: 'GROUP', id: group.id, label: group.code, url }}
      kindLabel="Розклад групи"
      documentTitle={`${group.code} — розклад групи`}
      details={groupDetails(group)}
      perspective="group"
      query={query}
      params={params}
      subgroups={subgroups}
      ics={{ owner: 'GROUP', id: group.id, fileName: `rozklad-${group.code}.ics` }}
      doorSign
    />
  );
}

/** Розклад групи /g/:code — код у будь-якому написанні (4ki1, 4КИ1) веде на канонічну адресу. */
export default function GroupSchedulePage() {
  const { code: rawCode = '' } = useParams();
  const location = useLocation();
  const groups = usePublicGroups();
  const decoded = safeDecode(rawCode).trim();
  const canonical = canonicalGroupCode(decoded);

  if (canonical && canonical !== decoded) {
    return <Navigate to={{ pathname: paths.group(canonical), search: location.search }} replace />;
  }
  const code = canonical ?? decoded;

  if (groups.isPending) {
    return (
      <div className="flex flex-col gap-6">
        <ScheduleHeaderSkeleton />
        <ScheduleSkeleton view="week" />
      </div>
    );
  }
  if (groups.isError) {
    return (
      <ScheduleLoadError heading={code} display error={groups.error} onRetry={() => void groups.refetch()} retrying={groups.isFetching} />
    );
  }

  const group = groups.data.find((g) => g.code === code);
  if (!group) {
    const similar = fuzzyFilter(groups.data, code, (g) => [g.code], 40).slice(0, 8);
    return (
      <TargetNotFound
        title={`Групи «${code}» немає в розкладі`}
        description="Перевірте код: у розкладі групи записані як 4КІ1 — курс, літери спеціальності й номер."
        suggestions={similar.map((g) => ({ label: g.code, to: paths.group(g.code), plate: true, type: 'GROUP' as const }))}
        searchQuery={code}
      />
    );
  }

  return <GroupSchedule key={group.id} group={group} />;
}
