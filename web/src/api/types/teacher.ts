import type { Nullable, PreferenceKind, Role, TeacherRef, Uuid, Weekday } from './common';
import type { AbsenceRequest } from './changes';

// ───────────────────────── 3. Teacher ─────────────────────────

/** GET /api/me */
export interface MeDto {
  userId: Uuid;
  username: string;
  roles: Role[];
  teacher?: Nullable<TeacherRef>;
}

/** POST /api/me/absences (без teacherId — сервер бере з токена) @inferred */
export type MyAbsenceRequest = Omit<AbsenceRequest, 'teacherId'>;

/** POST /api/me/changes/{id}/confirm|decline */
export interface ChangeReplyRequest {
  comment?: Nullable<string>;
}

/** GET/PUT /api/me/preferences?semesterId */
export interface PreferenceDto {
  weekday: Weekday;
  pair: number;
  kind: PreferenceKind;
  note?: Nullable<string>;
}
