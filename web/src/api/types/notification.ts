import type { IsoInstant, Nullable, Uuid } from './common';

// ───────────────────────── 6. Notification ─────────────────────────

export type BotMode = 'POLLING' | 'WEBHOOK';
export type BindingType = 'GROUP_CHAT' | 'TEACHER' | 'ADMIN_CHAT' | 'SUBSCRIBER';

/** GET /api/notify/bot */
export interface BotStatusDto {
  username: string;
  link: string;
  mode: BotMode;
  connected: boolean;
  /** Чому бот не працює (лише коли `connected=false`), адитивне поле notification. */
  problem?: Nullable<string>;
}

/** GET /api/notify/bindings. Адитивно до контракту: `groupCode`, `digestEnabled`. */
/** GET /api/notify/bindings/me — чи привʼязав викладач (власник облікового запису) свій Telegram. */
export interface MyBindingDto {
  teacherId: Uuid;
  teacherName: string;
  bound: boolean;
  binding: Nullable<ChatBindingDto>;
  botUsername: Nullable<string>;
  botLink: Nullable<string>;
}

export interface ChatBindingDto {
  id: Uuid;
  type: BindingType;
  /** Маскований id чату: `-•••7890` (група) або `•••7890` (особистий). */
  chatId: string;
  title: string;
  groupId: Nullable<Uuid>;
  groupCode: Nullable<string>;
  teacherId: Nullable<Uuid>;
  /**
   * Ранковий розклад о 7:30 (лише GROUP_CHAT і SUBSCRIBER). Новий чат групи — `false` (вмикає адміністратор
   * чату командою /rozklad_on або диспетчер тут), підписник — `true`. Змінює `PATCH /notify/bindings/{id}`.
   */
  digestEnabled: boolean;
  createdAt: IsoInstant;
  /**
   * Telegram-акаунт, що привʼязав чат викладача: «Імʼя (@nick)». `null` для інших чатів і для чатів викладачів,
   * привʼязаних раніше, ніж notification почав його записувати.
   */
  telegramAccount: Nullable<string>;
}

/** PATCH /api/notify/bindings/{id} (адитивно, раунд 7). 422 `digest-not-supported` для викладача й диспетчерської. */
export interface BindingUpdateRequest {
  digestEnabled: boolean;
}

/** POST /api/notify/bindings/codes */
export interface BindingCodeRequest {
  type: BindingType;
  groupId?: Nullable<Uuid>;
  teacherId?: Nullable<Uuid>;
}

/**
 * POST /api/notify/bindings/codes → 200. Адитивно: `type`, `label` (код групи чи імʼя викладача),
 * `groupLink` (`?startgroup=` — додає бота в групу) і `command` (`/bind XXXX-XXXX`) — лише для чатів, що
 * можуть бути групою (GROUP_CHAT, ADMIN_CHAT). 422 `teacher-not-linked` — обліковий запис викладача не привʼязано.
 */
export interface BindingCodeDto {
  /** Як показувати людині: `ABCD-EFGH`. */
  code: string;
  /** `https://t.me/<bot>?start=<code>` — особистий чат. */
  deepLink: string;
  expiresAt: IsoInstant;
  type: BindingType;
  label: string;
  groupLink: Nullable<string>;
  command: Nullable<string>;
}

export type DeliveryStatus = 'QUEUED' | 'SENT' | 'FAILED' | 'RETRYING';
/** Навіщо поставили повідомлення в чергу (фільтр журналу). */
export type DeliveryPurpose =
  | 'ORDER_GROUP'
  | 'ORDER_TEACHER'
  | 'ORDER_ADMIN'
  | 'SUBSTITUTION_REQUEST'
  | 'SUBSTITUTION_NOTICE'
  | 'SUBSTITUTION_REMINDER'
  | 'SUBSTITUTION_STATUS'
  | 'SUBSTITUTION_ADMIN'
  | 'ABSENCE_ADMIN'
  | 'GENERATION_ADMIN'
  | 'DIGEST'
  | 'TEST';
export type OutboundKind = 'TEXT' | 'PHOTO' | 'EDIT_TEXT';

/** Запис журналу GET /api/notify/deliveries (сторінка `Page<DeliveryDto>`, нові спершу). */
export interface DeliveryDto {
  id: Uuid;
  orderId: Nullable<Uuid>;
  /** Маскований id чату. */
  chatId: string;
  /** Назва чату на момент постановки в чергу; null — тестове повідомлення власнику. */
  chatTitle: Nullable<string>;
  /** null — чат без привʼязки (тестове повідомлення власнику). */
  bindingType: Nullable<BindingType>;
  purpose: DeliveryPurpose;
  kind: OutboundKind;
  status: DeliveryStatus;
  attempts: number;
  /** Остання причина збою. */
  error: Nullable<string>;
  createdAt: IsoInstant;
  sentAt: Nullable<IsoInstant>;
  /** Коли наступна спроба (лише для QUEUED/RETRYING). */
  nextAttemptAt: Nullable<IsoInstant>;
}
