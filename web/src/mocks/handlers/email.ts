/**
 * Листи на пошту (CONTRACTS §6, «E-mail notifications»): мої налаштування листів, тестовий лист собі,
 * стан поштового сервера і журнал листів. Як у notification: зберігаються лише відмінності від типових
 * значень, тож зміна типового значення доходить до всіх, хто цей вид не чіпав.
 *
 * Мок не бачить ролей (як і handlers/telegram.ts): демо-користувач krentsin — і викладач, і диспетчер, тому
 * віддаються обидві групи листів. Стенд мока — тестова пошта (Mailpit): нічого нікуди не надсилається.
 */
import { http, HttpResponse } from 'msw';
import type {
  EmailAudience,
  EmailDeliveryDto,
  EmailDeliveryStatus,
  EmailKind,
  EmailPreferencesDto,
  EmailStatusDto,
  IsoInstant,
} from '@/api/types';
import { teacherNamed } from '../fixtures';
import { mockId } from '../ids';
import { apiPath, badRequest, businessRule, conflict, paginate, problem, readJson } from '../kit';
import { createMockCollection, createMockStore } from '../store';

interface KindSpec {
  kind: EmailKind;
  audience: EmailAudience;
  defaultEnabled: boolean;
  switchable: boolean;
  /** Назва й опис виду, як у `EmailKind` notification (SPA показує власні тексти). */
  label: string;
  description: string;
}

/** Як `EmailKind` у notification: порядок — як у списку на сторінці. */
export const EMAIL_KINDS: readonly KindSpec[] = [
  { kind: 'SUBSTITUTION_NOTICE', audience: 'TEACHER', defaultEnabled: true, switchable: true, label: 'Вам поставили заміну', description: 'Нова заміна, про яку диспетчер повідомляє (можна відповісти «Не можу»).' },
  { kind: 'SUBSTITUTION_REQUEST', audience: 'TEACHER', defaultEnabled: true, switchable: true, label: 'Прохання про заміну', description: 'Заміна, яку треба підтвердити до строку.' },
  { kind: 'SUBSTITUTION_CHANGED', audience: 'TEACHER', defaultEnabled: true, switchable: true, label: 'Заміну змінено', description: 'Диспетчер змінив аудиторію чи інші деталі заміни, про яку ви вже отримали лист.' },
  { kind: 'SUBSTITUTION_CANCELLED', audience: 'TEACHER', defaultEnabled: true, switchable: true, label: 'Заміну скасовано', description: 'Заміну, про яку ви отримали лист, скасовано або передано іншому викладачу.' },
  { kind: 'SUBSTITUTION_REMINDER', audience: 'TEACHER', defaultEnabled: true, switchable: true, label: 'Нагадування про відповідь', description: 'Строк підтвердити заміну минув, а відповіді ще немає.' },
  { kind: 'ORDER_TEACHER', audience: 'TEACHER', defaultEnabled: false, switchable: true, label: 'Розпорядження з вашими змінами', description: 'Рядки опублікованого розпорядження, що стосуються вас.' },
  { kind: 'SECURITY_TELEGRAM_RELINKED', audience: 'TEACHER', defaultEnabled: true, switchable: false, label: 'Telegram підключено в іншому чаті', description: 'Попередження безпеки: його не можна вимкнути.' },
  { kind: 'GENERATION_FINISHED', audience: 'ADMIN', defaultEnabled: true, switchable: true, label: 'Генерацію розкладу завершено', description: 'Генератор закінчив роботу, не впорався або його зупинили.' },
  { kind: 'SUBSTITUTION_DECLINED', audience: 'ADMIN', defaultEnabled: true, switchable: true, label: 'Відмова від заміни', description: 'Викладач не може взяти заміну: потрібен інший варіант.' },
  { kind: 'SUBSTITUTION_OBJECTION', audience: 'ADMIN', defaultEnabled: true, switchable: true, label: 'Потрібне доповнення до розпорядження', description: 'Викладач не може взяти заміну з опублікованого розпорядження.' },
  { kind: 'REQUEST_OVERDUE', audience: 'ADMIN', defaultEnabled: true, switchable: true, label: 'Немає відповіді на запит заміни', description: 'Строк підтвердження минув, а викладач не відповів.' },
  { kind: 'ABSENCE_REPORTED', audience: 'ADMIN', defaultEnabled: false, switchable: true, label: 'Відсутність викладача', description: 'Викладач повідомив про відсутність (причина в листі не вказується).' },
  { kind: 'TEACHER_UNREACHABLE', audience: 'ADMIN', defaultEnabled: false, switchable: true, label: 'Викладач без Telegram', description: 'Викладач не підключив Telegram, тож про заміну треба сказати самим.' },
];

/** ДЕМО: адреса облікового запису krentsin, підтверджена входом через Google. */
export const DEMO_ADDRESS = 'krentsin@vtc.vn.ua';
/** Не більше трьох тестових листів на годину на обліковий запис. */
const TEST_LIMIT_PER_HOUR = 3;
const DELIVERIES_MAX_PAGE = 200;
const STATUSES: readonly EmailDeliveryStatus[] = ['QUEUED', 'RETRYING', 'SENT', 'FAILED', 'SUPERSEDED'];

interface PreferenceState {
  enabled: boolean;
  kindsOn: EmailKind[];
  kindsOff: EmailKind[];
  version: number;
}

/** Як `EmailSettingsDto` у notification: порядок полів не важливий, набір — так (contract.test.ts). */
const preference = createMockStore<PreferenceState>(() => ({ enabled: true, kindsOn: [], kindsOff: [], version: 0 }));
const testSent = createMockStore<number[]>(() => []);

function preferencesDto(): EmailPreferencesDto {
  const state = preference.get();
  return {
    channel: { enabled: true, reason: null },
    address: DEMO_ADDRESS,
    addressStatus: 'VERIFIED',
    verifiedBy: 'GOOGLE',
    enabled: state.enabled,
    kinds: EMAIL_KINDS.map((spec) => ({
      kind: spec.kind,
      audience: spec.audience,
      label: spec.label,
      description: spec.description,
      defaultEnabled: spec.defaultEnabled,
      switchable: spec.switchable,
      enabled: !spec.switchable || (state.kindsOn.includes(spec.kind) ? true : state.kindsOff.includes(spec.kind) ? false : spec.defaultEnabled),
    })),
    version: state.version,
  };
}

const at = (iso: string): IsoInstant => new Date(iso).toISOString();

// ДЕМО: кілька листів стенду (адреси під маскою, як MailAddresses.mask у notification)
const seedDeliveries = (): EmailDeliveryDto[] => {
  const krentsin = teacherNamed('Кренцін').displayName;
  const letter = (key: string, row: Omit<EmailDeliveryDto, 'id'>): EmailDeliveryDto => ({ id: mockId('email', key), ...row });
  return [
    letter('generation-1', {
      kind: 'GENERATION_FINISHED',
      to: 'k•••@vtc.vn.ua',
      accountName: krentsin,
      subject: 'Розклад згенеровано: 0 жорстких порушень, штраф 35',
      status: 'SENT',
      attempts: 1,
      error: null,
      createdAt: at('2026-11-13T10:16:00Z'),
      sentAt: at('2026-11-13T10:16:04Z'),
      nextAttemptAt: null,
    }),
    letter('notice-1', {
      kind: 'SUBSTITUTION_NOTICE',
      to: 'k•••@vtc.vn.ua',
      accountName: krentsin,
      subject: 'Вам поставили заміну: пн, 16 листопада, 3 пара, 4КІ1',
      status: 'SENT',
      attempts: 1,
      error: null,
      createdAt: at('2026-11-13T08:02:00Z'),
      sentAt: at('2026-11-13T08:04:01Z'),
      nextAttemptAt: null,
    }),
    letter('notice-0', {
      kind: 'SUBSTITUTION_NOTICE',
      to: 'k•••@vtc.vn.ua',
      accountName: krentsin,
      subject: 'Вам поставили заміну: пн, 16 листопада, 3 пара, 4КІ1',
      status: 'SUPERSEDED',
      attempts: 0,
      error: null,
      createdAt: at('2026-11-13T08:01:00Z'),
      sentAt: null,
      nextAttemptAt: null,
    }),
    letter('declined-1', {
      kind: 'SUBSTITUTION_DECLINED',
      to: 'k•••@vtc.vn.ua',
      accountName: krentsin,
      subject: 'Відмова від заміни: Прокопчук, 2 пара 2ОБ, чт 12.11',
      status: 'FAILED',
      attempts: 6,
      error: '535 5.7.8 Username and Password not accepted — перевірте пароль застосунку',
      createdAt: at('2026-11-12T07:40:00Z'),
      sentAt: null,
      nextAttemptAt: null,
    }),
  ];
};

const deliveries = createMockCollection<EmailDeliveryDto>('Лист', seedDeliveries);

/** Через скільки «надсилається» тестовий лист (лише в браузері; у Vitest — одразу). */
const SEND_DELAY_MS = 1_500;
const IN_TESTS = import.meta.env.MODE === 'test';

function markSent(id: string): void {
  deliveries.update(id, (current) => ({ ...current, status: 'SENT', attempts: current.attempts + 1, sentAt: new Date().toISOString(), nextAttemptAt: null }));
}

export const handlers = [
  http.get(apiPath('/notify/email/me'), () => HttpResponse.json(preferencesDto())),

  http.put(apiPath('/notify/email/me'), async ({ request }) => {
    const body = await readJson<{ enabled?: unknown; kinds?: unknown; version?: unknown }>(request);
    if (typeof body.enabled !== 'boolean') {
      return badRequest('Перевірте дані запиту.', [{ field: 'enabled', message: 'Вкажіть, чи надсилати листи.' }]);
    }
    const kinds = body.kinds ?? {};
    if (typeof kinds !== 'object' || Array.isArray(kinds)) {
      return badRequest('Перевірте дані запиту.', [{ field: 'kinds', message: 'Очікується обʼєкт «вид листа → увімкнено».' }]);
    }
    const entries = Object.entries(kinds as Record<string, unknown>);
    const unknown = entries.filter(([kind, value]) => !EMAIL_KINDS.some((s) => s.kind === kind) || typeof value !== 'boolean');
    if (unknown.length > 0) {
      return badRequest(
        'Невідомий вид листів.',
        unknown.map(([kind]) => ({ field: `kinds.${kind}`, message: 'Такого виду листів немає.' })),
      );
    }
    if (entries.some(([kind]) => !EMAIL_KINDS.find((s) => s.kind === kind)?.switchable)) {
      return businessRule('email-kind-not-allowed', 'Цей вид листів не можна вимкнути.');
    }
    const state = preference.get();
    if (typeof body.version === 'number' && body.version !== state.version) {
      return conflict('Налаштування листів уже змінено в іншій вкладці.');
    }
    // зберігаємо лише відмінності від типового: незгаданий вид лишається як був
    const on = new Set(state.kindsOn);
    const off = new Set(state.kindsOff);
    for (const [kind, value] of entries as Array<[EmailKind, boolean]>) {
      const spec = EMAIL_KINDS.find((s) => s.kind === kind);
      on.delete(kind);
      off.delete(kind);
      if (spec && value !== spec.defaultEnabled) (value ? on : off).add(kind);
    }
    preference.set({ enabled: body.enabled, kindsOn: [...on], kindsOff: [...off], version: state.version + 1 });
    return HttpResponse.json(preferencesDto());
  }),

  http.post(apiPath('/notify/email/me/test'), () => {
    const now = Date.now();
    const recent = testSent.get().filter((t) => now - t < 3_600_000);
    if (recent.length >= TEST_LIMIT_PER_HOUR) {
      return problem({ status: 429, slug: 'email-test-limit', code: 'email-test-limit', detail: 'Забагато тестових листів. Спробуйте за годину.' });
    }
    testSent.set([...recent, now]);
    const created = new Date(now).toISOString();
    const record = deliveries.insert({
      id: mockId('email', `test@${created}`),
      kind: 'TEST',
      to: 'k•••@vtc.vn.ua',
      accountName: teacherNamed('Кренцін').displayName,
      subject: 'Тестовий лист від «Розклад ВТФК»',
      status: 'QUEUED',
      attempts: 0,
      error: null,
      createdAt: created,
      sentAt: null,
      nextAttemptAt: created,
    });
    if (IN_TESTS) markSent(record.id);
    else setTimeout(() => deliveries.find(record.id) && markSent(record.id), SEND_DELAY_MS);
    return HttpResponse.json({ status: 'QUEUED' }, { status: 202 });
  }),

  http.get(apiPath('/notify/email/status'), () => {
    const status: EmailStatusDto = {
      enabled: true,
      mode: 'DEV_CATCHER',
      host: 'mailpit',
      port: 1025,
      from: 'Розклад ВТФК <rozklad@rozklad.local>',
      replyTo: null,
      tls: 'NONE',
      problem: null,
    };
    return HttpResponse.json(status);
  }),

  http.get(apiPath('/notify/email/deliveries'), ({ request }) => {
    const url = new URL(request.url);
    const status = url.searchParams.get('status');
    const kind = url.searchParams.get('kind');
    if (status && !STATUSES.includes(status as EmailDeliveryStatus)) {
      return badRequest('Невідомий стан листа.', [{ field: 'status', message: STATUSES.join(', ') }]);
    }
    const list = deliveries
      .all()
      .filter((d) => (!status || d.status === status) && (!kind || d.kind === kind))
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt) || a.id.localeCompare(b.id));
    return HttpResponse.json(paginate(list, url, 20, DELIVERIES_MAX_PAGE));
  }),
];
