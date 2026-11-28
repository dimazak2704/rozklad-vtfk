import type { IsoInstant, Nullable, Uuid } from './common';

// ── Листи на пошту (CONTRACTS §6, «E-mail notifications», 15.11.2026) ──

/** Кому призначений вид листа: викладачу про його заміни чи диспетчеру. */
export type EmailAudience = 'TEACHER' | 'ADMIN';

/**
 * Вид листа (`EmailKind` у notification). Типові значення й те, чи можна вимкнути, приходять із сервера
 * (`defaultEnabled`, `switchable`); новий вид, якого SPA ще не знає, показується з загальним підписом.
 */
export type EmailKind =
  | 'SUBSTITUTION_NOTICE'
  | 'SUBSTITUTION_REQUEST'
  | 'SUBSTITUTION_CHANGED'
  | 'SUBSTITUTION_CANCELLED'
  | 'SUBSTITUTION_REMINDER'
  | 'ORDER_TEACHER'
  | 'SECURITY_TELEGRAM_RELINKED'
  | 'GENERATION_FINISHED'
  | 'SUBSTITUTION_DECLINED'
  | 'SUBSTITUTION_OBJECTION'
  | 'REQUEST_OVERDUE'
  | 'ABSENCE_REPORTED'
  | 'TEACHER_UNREACHABLE';

/** VERIFIED — листи йдуть; UNVERIFIED — адресу ніхто не підтвердив; MISSING — на обліковому записі немає e-mail. */
export type EmailAddressStatus = 'VERIFIED' | 'UNVERIFIED' | 'MISSING';
/** Чим підтверджено адресу: входом через Google або корпоративним доменом коледжу. */
export type EmailVerifiedBy = 'GOOGLE' | 'TRUSTED_DOMAIN';

export interface EmailKindSettingDto {
  kind: EmailKind;
  audience: EmailAudience;
  /** Вибір людини для цього виду; зберігається й тоді, коли загальний перемикач вимкнено. */
  enabled: boolean;
  defaultEnabled: boolean;
  /** `false` — лист приходить завжди (лист безпеки): замість перемикача — «Завжди». */
  switchable: boolean;
  /** Назва й опис виду від сервера: SPA показує власні тексти, а ці — для виду, якого ще не знає. */
  label?: Nullable<string>;
  description?: Nullable<string>;
}

/**
 * GET/PUT /api/notify/email/me (TEACHER, ADMIN): мої налаштування листів. Адреса — власна, без маски,
 * лише для читання (її бере сервер з облікового запису). Види — лише ті, що стосуються мене:
 * викладацькі, якщо обліковий запис повʼязаний з викладачем, і диспетчерські для ролі ADMIN.
 */
export interface EmailPreferencesDto {
  /** Чи надсилає сервер листи взагалі (налаштовано SMTP); `reason` — чому ні. */
  channel: { enabled: boolean; reason?: Nullable<string> };
  address?: Nullable<string>;
  addressStatus: EmailAddressStatus;
  verifiedBy?: Nullable<EmailVerifiedBy>;
  /** Загальний перемикач «Надсилати листи». */
  enabled: boolean;
  kinds: EmailKindSettingDto[];
  /** Версія для оптимістичного блокування (необовʼязкова: сервер може її не віддавати). */
  version?: Nullable<number>;
}

/**
 * PUT /api/notify/email/me. `kinds` — лише ті види, що змінюються (незгадані лишаються як були); SPA шле стан
 * усіх перемикачів, а сервер зберігає лише відмінності від типових. 400 `errors[kinds.X]` — невідомий вид;
 * 422 `email-kind-not-allowed`; 409 — застаріла `version`.
 */
export interface EmailPreferencesRequest {
  enabled: boolean;
  kinds: Partial<Record<EmailKind, boolean>>;
  version?: Nullable<number>;
}

/**
 * POST /api/notify/email/me/test → 202. 422 `email-account-inactive`/`email-missing`/`email-not-verified`,
 * 503 `email-disabled`, 429 `email-test-limit`.
 */
export interface EmailTestResultDto {
  status: string;
}

/** SMTP — справжня пошта; DEV_CATCHER — Mailpit на стенді (листи нікуди не йдуть); OFF — не налаштовано. */
export type EmailMode = 'SMTP' | 'DEV_CATCHER' | 'OFF';

/** Шифрування зʼєднання з SMTP: STARTTLS (порт 587), SSL (465) або NONE (Mailpit на стенді). */
export type EmailTls = 'STARTTLS' | 'SSL' | 'NONE';

/**
 * GET /api/notify/email/status (ADMIN). Ніколи не містить логіна й пароля SMTP. `host`, `port`, `tls` —
 * `null`, коли пошту не налаштовано; `problem` — чому листи не йдуть або попередження.
 */
export interface EmailStatusDto {
  enabled: boolean;
  mode: EmailMode;
  host: Nullable<string>;
  port: Nullable<number>;
  from: Nullable<string>;
  replyTo: Nullable<string>;
  tls: Nullable<EmailTls>;
  problem?: Nullable<string>;
}

/** SUPERSEDED — замість цього листа пішов новіший про ту саму заміну. */
export type EmailDeliveryStatus = 'QUEUED' | 'RETRYING' | 'SENT' | 'FAILED' | 'SUPERSEDED';

/** Запис журналу GET /api/notify/email/deliveries (ADMIN; сторінка `Page<EmailDeliveryDto>`, нові спершу). */
export interface EmailDeliveryDto {
  id: Uuid;
  /** Вид листа; `TEST` — тестовий лист собі. */
  kind: EmailKind | 'TEST';
  /** Маскована адреса: `k•••@vtc.vn.ua`. */
  to: string;
  accountName: Nullable<string>;
  subject: string;
  status: EmailDeliveryStatus;
  attempts: number;
  error: Nullable<string>;
  createdAt: IsoInstant;
  sentAt: Nullable<IsoInstant>;
  nextAttemptAt: Nullable<IsoInstant>;
}

export type DocumentFormat = 'docx' | 'pdf' | 'png';
