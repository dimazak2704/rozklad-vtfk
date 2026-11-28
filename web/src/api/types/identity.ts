import type { ApprovalMode, IsoInstant, Nullable, TeacherRef, Uuid } from './common';

// ───────────────────────── 2. Admin: ідентичність і налаштування ─────────────────────────

export interface CreateInviteRequest {
  teacherId: Uuid;
}

export interface InviteCreatedDto {
  token: string;
  url: string;
  expiresAt: IsoInstant;
}

/** Елемент GET /api/admin/invites. @inferred */
export interface InviteDto {
  id: Uuid;
  teacher: TeacherRef;
  url?: Nullable<string>;
  expiresAt: IsoInstant;
  usedAt: Nullable<IsoInstant>;
  createdAt: Nullable<IsoInstant>;
}

/** GET/PUT /api/admin/settings (межі — SETTINGS_LIMITS у api/settings-limits.ts, як у core SystemSettings). */
export interface SettingsDto {
  approvalMode: ApprovalMode;
  confirmTimeoutMinutes: number;
  signatoryPosition: string;
  signatoryName: string;
  collegeName: string;
  hoursPerPair: number;
  showTeacherFullNames: boolean;
  /**
   * Скільки днів назад гості бачать заміни, розпорядження й розклад (0..365, типово 7; 0 — лише від сьогодні).
   * PUT без поля (старий клієнт) лишає збережене значення.
   */
  changesHistoryDays: number;
  /**
   * Найбільше пар на день у викладача для генератора (1..8, типово 4; genfix §3.4). Старий сервер не надсилає —
   * тоді поля в формі немає; PUT без поля лишає збережене значення.
   */
  teacherMaxPairsPerDay?: number;
  /**
   * Найбільше пар у групи в пʼятницю — жорстке правило генератора (1..8, типово 3; власник, 15.11.2026: пʼятниця —
   * розвантажувальний день). Старий сервер не надсилає — тоді поля в формі немає; PUT без поля лишає збережене.
   */
  fridayMaxPairs?: number;
  activeBellProfileId: Nullable<Uuid>;
  /** Версія налаштувань з GET; PUT зі старішою — 409 `stale-data` (хтось зберіг раніше). */
  version: number;
}
