import type { Employment, Nullable, Ref, RoomRef, RoomType, ShiftNumber, TeacherRef, Uuid } from './common';

// ───────────────────────── 2. Admin: каталог (@inferred з DOMAIN §3) ─────────────────────────

/** @inferred */
export interface DepartmentDto {
  id: Uuid;
  name: string;
  shortName: Nullable<string>;
}

/** @inferred */
export interface SpecialtyDto {
  id: Uuid;
  code: string;
  letters: string;
  name: string;
  departmentId: Uuid;
}

/** @inferred */
export interface CycleCommissionDto {
  id: Uuid;
  name: string;
}

/** @inferred */
export interface TeacherDto {
  id: Uuid;
  lastName: string;
  firstName: Nullable<string>;
  middleName: Nullable<string>;
  shortName: string;
  displayName: string;
  cycleCommission: Nullable<Ref>;
  employment: Employment;
  email: Nullable<string>;
  active: boolean;
  needsReview: boolean;
  /** Чи привʼязаний обліковий запис (сервіс входу auth). */
  linked?: boolean;
  version?: number;
}

/** @inferred */
export interface AliasDto {
  id: Uuid;
  alias: string;
}

/** @inferred */
export interface DisciplineDto {
  id: Uuid;
  name: string;
  shortName: string;
  color: Nullable<string>;
  active: boolean;
  needsReview: boolean;
  version?: number;
}

/** @inferred */
export interface BuildingDto {
  id: Uuid;
  name: string;
}

/** @inferred */
export interface RoomDto {
  id: Uuid;
  building: Nullable<Ref>;
  name: string;
  type: RoomType;
  capacity: Nullable<number>;
  maxParallel: number;
  active: boolean;
  version?: number;
}

/** @inferred */
export interface SubgroupDto {
  id: Uuid;
  number: number;
  name: Nullable<string>;
  size: Nullable<number>;
}

/** @inferred */
export interface GroupDto {
  id: Uuid;
  code: string;
  course: number;
  specialty: Nullable<Ref>;
  number: Nullable<number>;
  size: Nullable<number>;
  shift: ShiftNumber;
  curator: Nullable<TeacherRef>;
  homeRoom: Nullable<RoomRef>;
  active: boolean;
  subgroups?: SubgroupDto[];
  version?: number;
}
