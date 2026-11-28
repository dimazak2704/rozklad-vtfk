import type { ControlForm, DisciplineRef, GroupRef, LessonKind, LoadSource, Nullable, PlanSource, RoomRef, RoomType, TeacherRef, Uuid } from './common';

// ───────────────────────── 2. Admin: навантаження ─────────────────────────

export interface LoadItemTeacherDto {
  subgroup: number;
  teacher: TeacherRef;
}

/** /api/admin/load */
export interface LoadItemDto {
  id: Uuid;
  semesterId: Uuid;
  group: GroupRef;
  discipline: DisciplineRef;
  lessonKind: LessonKind;
  hoursPerWeek: number;
  /** За семестр. AUTO — рахує система за днями, коли група на теорії; FIXED — число задали вручну чи імпортом. */
  plannedHours: number;
  planSource: PlanSource;
  splitSubgroups: boolean;
  teachers: LoadItemTeacherDto[];
  requiredRoomType: Nullable<RoomType>;
  preferredRoom: Nullable<RoomRef>;
  controlForm: ControlForm;
  source: LoadSource;
  /** Розкладка на тиждень: цілі пари і половинки (чисельник/знаменник). */
  pattern: { whole: number; half: number };
}
