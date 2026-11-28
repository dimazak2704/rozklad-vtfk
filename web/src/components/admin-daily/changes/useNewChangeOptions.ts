import { useMemo } from 'react';
import { useCatalogOptions } from '@/api/catalog';
import type { LoadItem } from '@/api/load';
import type { PublicGroupDto, PublicTeacherDto, TeacherAvailabilityDto, Uuid } from '@/api/types';
import { roomLabel } from '@/lib/schedule';
import { availabilityLabel } from '../model';
import { useTeacherLabel } from '../useTeacherLabel';

interface OptionsInput {
  open: boolean;
  groupId: Uuid | null;
  groups: readonly PublicGroupDto[] | undefined;
  teachers: readonly PublicTeacherDto[] | undefined;
  load: readonly LoadItem[] | undefined;
  availability: readonly TeacherAvailabilityDto[] | undefined;
}

/** Списки вибору форми «Нова зміна»: групи, дисципліни (спершу дисципліни групи), викладачі й аудиторії. */
export function useNewChangeOptions({ open, groupId, groups, teachers, load, availability }: OptionsInput) {
  const name = useTeacherLabel();
  const disciplines = useCatalogOptions('disciplines', {}, { enabled: open });
  const rooms = useCatalogOptions('rooms', {}, { enabled: open });

  const group = useMemo(
    () =>
      (groups ?? []).map((g) => ({
        value: g.id,
        label: g.code,
        keywords: [g.department ?? ''],
      })),
    [groups],
  );
  const discipline = useMemo(() => {
    // спершу дисципліни групи з навантаження — їх і відпрацьовують
    const ofGroup = new Set((load ?? []).filter((item) => item.group.id === groupId).map((item) => item.discipline.id));
    return (disciplines.items ?? [])
      .map((d) => ({
        value: d.id,
        label: d.shortName,
        description: d.name !== d.shortName ? d.name : undefined,
        keywords: [d.name],
      }))
      .sort((a, b) => Number(ofGroup.has(b.value)) - Number(ofGroup.has(a.value)));
  }, [disciplines.items, load, groupId]);
  const teacher = useMemo(
    () =>
      (teachers ?? []).map((t) => ({
        value: t.id,
        label: name(t),
        keywords: [t.shortName, t.displayName],
        description: t.cycleCommission ?? undefined,
      })),
    [teachers, name],
  );
  const freeTeacher = useMemo(
    () =>
      (availability ?? []).map((item) => ({
        value: item.teacher.id,
        label: name(item.teacher),
        keywords: [item.teacher.shortName],
        description: availabilityLabel(item),
        disabled: item.status !== 'FREE',
      })),
    [availability, name],
  );
  const room = useMemo(
    () =>
      (rooms.items ?? [])
        .filter((r) => r.active)
        .map((r) => ({
          value: r.id,
          label: roomLabel(r.name) ?? r.name,
          keywords: [r.name],
        })),
    [rooms.items],
  );

  return { group, discipline, teacher, freeTeacher, room, disciplinesLoading: disciplines.isPending, roomsLoading: rooms.isPending };
}
