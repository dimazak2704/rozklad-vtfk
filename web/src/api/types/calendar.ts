import type { CalendarPeriodKind, Conflict, GroupRef, IsoDate, Nullable, PeriodEffect, PeriodScope, PeriodSource, RoomRef, ShiftNumber, StudyMode, TeacherRef, Uuid, WeekParity, Weekday } from './common';

// ───────────────────────── 2. Admin: календар ─────────────────────────

/** @inferred */
export interface SemesterDto {
  id: Uuid;
  academicYearId?: Nullable<Uuid>;
  number: number;
  name: string;
  startsOn: IsoDate;
  endsOn: IsoDate;
  firstWeekParity: WeekParity;
  weeksCount: Nullable<number>;
  bellProfileId: Nullable<Uuid>;
  active: boolean;
}

/** GET /api/admin/semesters/{id}/weeks @inferred */
export interface SemesterWeekDto {
  weekStart: IsoDate;
  weekNumber: number;
  parity: WeekParity;
  overridden: boolean;
}

/** Вид періоду коротко: у періоді календаря, у блоці дня. */
export interface PeriodKindRef {
  id: Uuid;
  /** Код як у графіку коледжу: «НС», «К», «НП». */
  code: string;
  name: string;
  /** `#RRGGBB` з легенди графіка або null. */
  color: Nullable<string>;
  effect: PeriodEffect;
}

/** GET /api/admin/period-kinds — вид періоду з довідника. */
export interface PeriodKindDto {
  id: Uuid;
  code: string;
  name: string;
  effect: PeriodEffect;
  color: Nullable<string>;
  /** Інші написання коду (у легенді «З», у клітинках «ЗУ»). */
  aliases: string[];
  /** Шаблон примітки для розпорядження: `{хто} – {назва} з {від}р. по {до} р.[, ауд. {ауд}]`. */
  noteTemplate: Nullable<string>;
  /** Вбудований: код не змінюється, видалити не можна (лише вимкнути). */
  builtin: boolean;
  active: boolean;
  position: number;
  /** Скільки періодів календаря цього виду. */
  usage: number;
  version: number;
}

/** POST/PUT /api/admin/period-kinds */
export interface PeriodKindRequest {
  code: string;
  name: string;
  effect: PeriodEffect;
  color?: Nullable<string>;
  aliases?: string[];
  noteTemplate?: Nullable<string>;
  active?: boolean;
  position?: number;
  version?: number;
}

/** PUT /api/admin/period-kinds/{id}?dryRun=true — що зачепить зміна виду (нічого не зберігається). */
export interface PeriodKindImpactDto {
  /** Вид, яким він став би після збереження. */
  kind: PeriodKindDto;
  effectChanged: boolean;
  /** Скільки періодів цього виду. */
  periods: number;
  /** Навчальні дні (пн–пт) усіх періодів виду в межах їхніх семестрів. */
  dates: IsoDate[];
  groups: GroupRef[];
  lessons: { added: number; removed: number; updated: number };
  /** Живі заміни, які зміна зачепила б (як 409 правки періоду). */
  conflicts: Conflict[];
  /** Чому зміну не можна зберегти (тексти 409). */
  blockers: string[];
  canApply: boolean;
}

/**
 * GET/POST/PUT /api/admin/calendar-periods. Поведінку періоду визначає `periodKind.effect`; `kind` — старий
 * вид (@deprecated, ще один реліз). Поля довідника видів адитивні: старіший core їх не має.
 */
export interface CalendarPeriodDto {
  id: Uuid;
  semesterId: Uuid;
  /** @deprecated Старий вид: для К/БЗ/НП/ФН/І — свій, для решти — за ефектом. Читайте `periodKind`. */
  kind: CalendarPeriodKind;
  title: string;
  startsOn: IsoDate;
  endsOn: IsoDate;
  studyMode: Nullable<StudyMode>;
  orderNote: Nullable<string>;
  scope: PeriodScope;
  courses: Nullable<number[]>;
  groupIds: Nullable<Uuid[]>;
  /** Керівники (лише для виду з ефектом SUSPEND); старіший core полів не має. */
  supervisorIds?: Uuid[];
  supervisors?: TeacherRef[];
  kindId?: Uuid;
  periodKind?: PeriodKindRef;
  /** Аудиторія періоду (лише SUSPEND, «ауд. 401» у блоці дня); у кроці 1 лише показується. */
  roomId?: Nullable<Uuid>;
  room?: Nullable<RoomRef>;
  source?: PeriodSource;
  /** Ключ рядка графіка, з якого імпортовано період. */
  sourceKey?: Nullable<string>;
}

/** GET/PUT /api/admin/shift-windows */
export interface ShiftWindowDto {
  shift: ShiftNumber;
  weekday: Weekday;
  firstPair: number;
  lastPair: number;
}
