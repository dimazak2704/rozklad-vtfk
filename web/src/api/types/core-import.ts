import type { IsoDate, Nullable, Parity, Uuid, Weekday } from './common';

// ───────────────────────── 2. Admin: імпорт у core (ADMIN або SERVICE) ─────────────────────────

/** POST /api/admin/import/catalog — upsert за природними ключами. Вміст елементів @inferred. */
export interface ImportCatalogRequest {
  departments: Array<Record<string, unknown>>;
  specialties: Array<Record<string, unknown>>;
  teachers: Array<Record<string, unknown>>;
  disciplines: Array<Record<string, unknown>>;
  rooms: Array<Record<string, unknown>>;
  groups: Array<Record<string, unknown>>;
  aliases: Array<Record<string, unknown>>;
}

export interface ImportCatalogResult {
  created: number;
  updated: number;
  ids: Record<string, unknown>;
}

export interface ImportTimetableLesson {
  weekday: Weekday;
  pair: number;
  parity: Parity;
  groupCode: string;
  subgroup: number;
  disciplineShortName: string;
  teacherShortName?: Nullable<string>;
  roomName?: Nullable<string>;
  online: boolean;
}

/** POST /api/admin/import/timetable → id чернетки */
export interface ImportTimetableRequest {
  semesterId: Uuid;
  name: string;
  lessons: ImportTimetableLesson[];
}

/** @inferred — контракт каже лише «draft version id» */
export interface ImportTimetableResult {
  versionId: Uuid;
}

export interface ImportLoadRequest {
  semesterId: Uuid;
  items: Array<Record<string, unknown>>;
}

export interface ImportChangesRequest {
  date: IsoDate;
  items: Array<Record<string, unknown>>;
}
