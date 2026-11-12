import { useMemo } from 'react';
import { useParams } from 'react-router';
import { isApiError } from '@/api/errors';
import { useBellProfiles, usePublicRooms, usePublicSchedule } from '@/api/public';
import { paths } from '@/app/routes';
import { RoomNowStatus } from '@/components/public/schedule/RoomOccupancy';
import { ScheduleHeaderSkeleton, ScheduleLoadError } from '@/components/public/schedule/ScheduleHeader';
import { SchedulePage } from '@/components/public/schedule/SchedulePage';
import { ScheduleSkeleton } from '@/components/public/schedule/ScheduleSkeleton';
import { useScheduleParams } from '@/components/public/schedule/schedule-params';
import { mainDisciplines, roomDetails } from '@/components/public/schedule/schedule-utils';
import { TargetNotFound } from '@/components/public/schedule/TargetNotFound';
import type { PublicRoomDto } from '@/api/types';
import { capitalize, ROOM_TYPE_LABEL } from '@/lib/labels';
import { roomLabel } from '@/lib/schedule';

/**
 * Назва великим: «Аудиторія 3», «Спортзал». Скорочення «ауд. 3» лишається для клітинок і списків —
 * у заголовку з малої літери воно виглядає недописаним.
 */
function roomHeading(name: string, room: PublicRoomDto | undefined): string {
  if (/^\d/.test(name)) return `Аудиторія ${name}`;
  if (room && room.type !== 'CLASSROOM' && room.type !== 'OTHER') return capitalize(ROOM_TYPE_LABEL[room.type]);
  return capitalize(name);
}

/** Розклад аудиторії /r/:id і її зайнятість за тиждень. */
export default function RoomSchedulePage() {
  const { id = '' } = useParams();
  const params = useScheduleParams();
  const rooms = usePublicRooms();
  const query = usePublicSchedule('ROOM', id, params.week.from, params.week.to);
  const profiles = useBellProfiles();
  const room = rooms.data?.find((r) => r.id === id);
  const name = room?.name ?? query.data?.owner.label;
  const loaded = query.isPlaceholderData ? undefined : query.data;
  const disciplines = useMemo(() => mainDisciplines(loaded).join(', '), [loaded]);

  const missing = (isApiError(query.error) && query.error.isNotFound) || (rooms.isSuccess && !room);
  if (missing) {
    return (
      <TargetNotFound
        title="Такої аудиторії в розкладі немає"
        description="Можливо, посилання застаріло. Знайдіть аудиторію за номером — наприклад, 301 або «с.з.» для спортзалу."
      />
    );
  }
  if (!name) {
    if (query.isError) {
      return (
        <ScheduleLoadError heading="Розклад аудиторії" error={query.error} onRetry={() => void query.refetch()} retrying={query.isFetching} />
      );
    }
    return (
      <div className="flex flex-col gap-6">
        <ScheduleHeaderSkeleton />
        <ScheduleSkeleton view={params.view} />
      </div>
    );
  }

  const label = roomLabel(name) ?? name;
  const heading = roomHeading(name, room);
  // рядок контексту: що це за приміщення (якщо відомо більше, ніж «аудиторія»), інакше — що тут ведуть цього тижня
  const facts = room && roomDetails(room) !== 'Аудиторія' ? roomDetails(room) : null;
  const details = facts ?? (disciplines ? `Цього тижня тут ${disciplines}` : undefined);
  const activeProfile = profiles.data?.profiles?.find((p) => p.id === profiles.data.profile.id);

  return (
    <SchedulePage
      target={{ type: 'ROOM', id, label, url: paths.room(id) }}
      kindLabel="Розклад аудиторії"
      heading={heading}
      documentTitle={`${heading} — розклад`}
      details={details}
      status={
        query.data && !query.isPlaceholderData && activeProfile ? (
          <RoomNowStatus schedule={query.data} roomId={id} profile={activeProfile} today={params.today} />
        ) : null
      }
      perspective="room"
      query={query}
      params={params}
      doorSign
    />
  );
}
