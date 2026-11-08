import { useMemo } from 'react';
import { Navigate, useLocation, useParams } from 'react-router';
import { usePublicGroups, usePublicSchedule } from '@/api/public';
import type { PublicGroupDto } from '@/api/types';
import { paths } from '@/app/routes';
import { ScheduleHeaderSkeleton } from '@/components/public/schedule/ScheduleHeader';
import { SchedulePage } from '@/components/public/schedule/SchedulePage';
import { ScheduleSkeleton } from '@/components/public/schedule/ScheduleSkeleton';
import { useScheduleParams } from '@/components/public/schedule/schedule-params';
import { subgroupsIn } from '@/components/public/schedule/schedule-utils';
import { TargetNotFound } from '@/components/public/schedule/TargetNotFound';
import { Badge, ErrorState } from '@/components/ui';
import { canonicalGroupCode, fuzzyFilter } from '@/lib/search';

function safeDecode(value: string): string {
  try {
    return decodeURIComponent(value);
  } catch {
    return value;
  }
}

/** «4 курс · Компʼютерна інженерія · Компʼютерне відділення»; невідому назву спеціальності не показуємо. */
function groupDetails(group: PublicGroupDto): string {
  const specialty = group.specialty.name !== group.specialty.letters ? group.specialty.name : null;
  return [`${group.course} курс`, specialty, group.department].filter(Boolean).join(' · ');
}

function GroupSchedule({ group }: { group: PublicGroupDto }) {
  const params = useScheduleParams();
  const query = usePublicSchedule('GROUP', group.id, params.week.from, params.week.to);
  const subgroups = useMemo(() => subgroupsIn(query.data), [query.data]);
  const url = paths.group(group.code);

  return (
    <SchedulePage
      target={{ type: 'GROUP', id: group.id, label: group.code, url }}
      kindLabel="Розклад групи"
      documentTitle={`${group.code} — розклад групи`}
      details={groupDetails(group)}
      badges={<Badge>{group.shift} зміна</Badge>}
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
      <ErrorState
        error={groups.error}
        title="Не вдалося завантажити список груп"
        onRetry={() => void groups.refetch()}
        retrying={groups.isFetching}
      />
    );
  }

  const group = groups.data.find((g) => g.code === code);
  if (!group) {
    const similar = fuzzyFilter(groups.data, code, (g) => [g.code], 40).slice(0, 8);
    return (
      <TargetNotFound
        title={`Групи «${code}» немає в розкладі`}
        description="Перевірте код: у розкладі групи записані як 4КІ1 — курс, літери спеціальності й номер."
        suggestions={similar.map((g) => ({ label: g.code, to: paths.group(g.code), plate: true }))}
        searchQuery={code}
      />
    );
  }

  return <GroupSchedule key={group.id} group={group} />;
}
