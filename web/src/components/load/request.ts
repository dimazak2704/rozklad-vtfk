import type { LoadItem, LoadItemRequest } from '@/api/load';

/** Запис навантаження → тіло PUT (для повернення попередніх значень). */
export function loadRequest(item: LoadItem): LoadItemRequest {
  return {
    semesterId: item.semesterId,
    groupId: item.group.id,
    disciplineId: item.discipline.id,
    lessonKind: item.lessonKind,
    hoursPerWeek: item.hoursPerWeek,
    plannedHours: item.plannedHours,
    splitSubgroups: item.splitSubgroups,
    teachers: item.teachers.map((t) => ({ subgroup: t.subgroup, teacherId: t.teacher.id })),
    requiredRoomType: item.requiredRoomType,
    preferredRoomId: item.preferredRoom?.id ?? null,
    controlForm: item.controlForm,
    streamId: item.streamId,
    note: item.note,
    version: item.version,
  };
}
