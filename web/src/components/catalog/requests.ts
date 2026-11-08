/**
 * Рядок довідника → тіло PUT із зміненим полем (наприклад, «зробити неактивною»). Версія запису
 * передається завжди: якщо хтось змінив запис паралельно, сервер відповість 409, а не перезапише.
 */
import type {
  CatalogDiscipline,
  CatalogGroup,
  CatalogRoom,
  CatalogTeacher,
  DisciplineRequest,
  GroupRequest,
  RoomRequest,
  TeacherRequest,
} from '@/api/catalog';

export const roomRequest = (room: CatalogRoom, patch: Partial<RoomRequest> = {}): RoomRequest => ({
  buildingId: room.building?.id ?? null,
  name: room.name,
  type: room.type,
  capacity: room.capacity,
  maxParallel: room.maxParallel,
  active: room.active,
  version: room.version,
  ...patch,
});

export const disciplineRequest = (discipline: CatalogDiscipline, patch: Partial<DisciplineRequest> = {}): DisciplineRequest => ({
  name: discipline.name,
  shortName: discipline.shortName,
  color: discipline.color,
  active: discipline.active,
  needsReview: discipline.needsReview,
  version: discipline.version,
  ...patch,
});

export const teacherRequest = (teacher: CatalogTeacher, patch: Partial<TeacherRequest> = {}): TeacherRequest => ({
  lastName: teacher.lastName,
  firstName: teacher.firstName,
  middleName: teacher.middleName,
  shortName: teacher.shortName,
  displayName: teacher.displayName,
  cycleCommissionId: teacher.cycleCommission?.id ?? null,
  employment: teacher.employment,
  email: teacher.email,
  active: teacher.active,
  needsReview: teacher.needsReview,
  version: teacher.version,
  ...patch,
});

export const groupRequest = (group: CatalogGroup, patch: Partial<GroupRequest> = {}): GroupRequest => ({
  code: group.code,
  specialtyId: group.specialtyId,
  size: group.size,
  shift: group.shift,
  curatorTeacherId: group.curator?.id ?? null,
  homeRoomId: group.homeRoom?.id ?? null,
  active: group.active,
  version: group.version,
  ...patch,
});
