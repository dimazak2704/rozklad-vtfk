/**
 * Telegram-бот і розсилки (CONTRACTS §6, сервіс notification): стан бота, привʼязані чати, коди привʼязки,
 * журнал доставок, тестове повідомлення. Сюди ж — журнал розсилки після публікації розпорядження і запитів
 * на підтвердження замін (їх викликає область admin-daily).
 *
 * Запасні обробники довідників GET /api/public/groups і /api/public/teachers (потрібні для вибору групи чи
 * викладача): обробники публічних сторінок мають перевагу, бо реєстр підключає файли за алфавітом.
 */
import { http, HttpResponse } from 'msw';
import type {
  BindingCodeDto,
  BindingCodeRequest,
  BindingType,
  BotStatusDto,
  ChatBindingDto,
  DeliveryDto,
  DeliveryStatus,
  IsoInstant,
  Uuid,
} from '@/api/types';
import {
  findGroup,
  findTeacher,
  groupNamed,
  teacherNamed,
  TEACHERS,
} from '../fixtures';
import { mockId } from '../ids';
import { apiPath, badRequest, readJson } from '../kit';
import { createMockCollection, createMockStore } from '../store';

export const BOT_USERNAME = 'scheduler_vtfk_bot';
const BOT_LINK = `https://t.me/${BOT_USERNAME}`;

const BINDING_TYPES: readonly BindingType[] = ['GROUP_CHAT', 'TEACHER', 'ADMIN_CHAT', 'SUBSCRIBER'];
/** Скільки діє код: чат групи привʼязує куратор протягом дня, особистий чат — одразу. */
const CODE_TTL_MS: Record<Exclude<BindingType, 'SUBSCRIBER'>, number> = {
  GROUP_CHAT: 24 * 60 * 60_000,
  TEACHER: 2 * 60 * 60_000,
  ADMIN_CHAT: 2 * 60 * 60_000,
};

const at = (iso: string): IsoInstant => new Date(iso).toISOString();

/** Маскований id чату, як віддає сервіс: група — відʼємний id супергрупи, приватний чат — додатний. */
function maskedChatId(type: BindingType, key: string): string {
  const tail = String(Number.parseInt(mockId('chat', key).slice(0, 6), 16) % 10_000).padStart(4, '0');
  return type === 'GROUP_CHAT' || type === 'ADMIN_CHAT' ? `−100•••${tail}` : `•••${tail}`;
}

function groupBinding(code: string, createdAt: string): ChatBindingDto {
  const group = groupNamed(code);
  return {
    id: mockId('binding', `group:${code}`),
    type: 'GROUP_CHAT',
    chatId: maskedChatId('GROUP_CHAT', code),
    title: `${code} — група`,
    groupId: group.id,
    teacherId: null,
    createdAt: at(createdAt),
  };
}

function teacherBinding(shortName: string, createdAt: string): ChatBindingDto {
  const teacher = teacherNamed(shortName);
  return {
    id: mockId('binding', `teacher:${shortName}`),
    type: 'TEACHER',
    chatId: maskedChatId('TEACHER', shortName),
    title: teacher.shortName,
    groupId: null,
    teacherId: teacher.id,
    createdAt: at(createdAt),
  };
}

/** Демо-викладачі, що точно підключили бота (обліковий запис і перші кандидати демо-ранку). */
const ALWAYS_BOUND = new Set(['Кренцін', 'Непийвода', 'Прокопчук', 'Вернигора', 'Можаровська О.Е.']);

/** Більшість викладачів підключили бота у вересні; приблизно кожен сьомий — ні (запит до нього не дійде). */
function teacherBindings(): ChatBindingDto[] {
  const start = Date.parse('2026-09-01T06:00:00Z');
  return TEACHERS.filter((t) => ALWAYS_BOUND.has(t.shortName) || Number.parseInt(mockId('bot', t.shortName).slice(0, 4), 16) % 7 !== 0).map(
    (teacher) => {
      const offsetHours = Number.parseInt(mockId('bot-at', teacher.shortName).slice(0, 6), 16) % (45 * 24);
      return teacherBinding(teacher.shortName, new Date(start + offsetHours * 3_600_000).toISOString());
    },
  );
}

/** Чи підключив викладач бота (є привʼязка особистого чату). */
export function isTeacherBound(teacherId: Uuid): boolean {
  return bindings.all().some((b) => b.type === 'TEACHER' && b.teacherId === teacherId);
}

// ДЕМО: привʼязки чатів — синтетичні (групи й викладачі справжні, з розкладу)
const seedBindings = (): ChatBindingDto[] => [
  {
    id: mockId('binding', 'admin-chat'),
    type: 'ADMIN_CHAT',
    chatId: maskedChatId('ADMIN_CHAT', 'admin'),
    title: 'Диспетчерська ВТФК',
    groupId: null,
    teacherId: null,
    createdAt: at('2026-08-28T09:12:00Z'),
  },
  groupBinding('4КІ1', '2026-09-01T07:40:00Z'),
  groupBinding('4КІ2', '2026-09-01T07:52:00Z'),
  groupBinding('4КІ3', '2026-09-02T10:05:00Z'),
  groupBinding('1КІ2', '2026-09-03T11:30:00Z'),
  groupBinding('1М', '2026-09-04T08:15:00Z'),
  groupBinding('1ТК1', '2026-09-08T12:20:00Z'),
  groupBinding('2ОБ', '2026-09-09T13:45:00Z'),
  groupBinding('2ВЕ', '2026-09-09T14:05:00Z'),
  groupBinding('2Ф', '2026-09-10T09:30:00Z'),
  groupBinding('3Ф', '2026-09-15T09:00:00Z'),
  ...teacherBindings(),
];

const bindings = createMockCollection<ChatBindingDto>('Привʼязку', seedBindings);

// ДЕМО: журнал розсилки розпорядження №35 (19.10.2026) у чати груп, що мали зміни
const seedDeliveries = (): DeliveryDto[] => {
  const orderId = mockId('order', 'published-35');
  const sent = (code: string, minute: number): DeliveryDto => ({
    id: mockId('delivery', `35:${code}`),
    orderId,
    chatTitle: `${code} — група`,
    bindingType: 'GROUP_CHAT',
    status: 'SENT',
    attempts: 1,
    error: null,
    createdAt: at(`2026-10-16T12:40:00Z`),
    sentAt: at(`2026-10-16T12:4${minute}:00Z`),
  });
  return [
    sent('1КІ2', 0),
    sent('1М', 0),
    sent('1ТК1', 1),
    sent('3Ф', 1),
    sent('4КІ1', 1),
    sent('4КІ2', 2),
    {
      id: mockId('delivery', '35:2ОБ'),
      orderId,
      chatTitle: '2ОБ — група',
      bindingType: 'GROUP_CHAT',
      status: 'FAILED',
      attempts: 3,
      error: 'Бота вилучено з чату групи — привʼяжіть чат знову',
      createdAt: at('2026-10-16T12:40:00Z'),
      sentAt: null,
    },
    {
      id: mockId('delivery', '35:admin'),
      orderId,
      chatTitle: 'Диспетчерська ВТФК',
      bindingType: 'ADMIN_CHAT',
      status: 'SENT',
      attempts: 1,
      error: null,
      createdAt: at('2026-10-16T12:40:00Z'),
      sentAt: at('2026-10-16T12:40:00Z'),
    },
  ];
};

const deliveries = createMockCollection<DeliveryDto>('Доставку', seedDeliveries);
const codeCounter = createMockStore(() => 0);

/** Через скільки «надсилається» повідомлення з черги (лише в браузері; у Vitest — одразу). */
const SEND_DELAY_MS = 1_500;
const IN_TESTS = import.meta.env.MODE === 'test';

function deliver(id: Uuid): void {
  deliveries.update(id, (current) => ({ ...current, status: 'SENT', attempts: current.attempts + 1, sentAt: new Date().toISOString() }));
}

/** Додає запис у журнал; без привʼязки чату доставка одразу неуспішна (як у сервісі notification). */
export function recordDelivery(input: {
  key: string;
  chatTitle: string;
  bindingType: BindingType;
  orderId?: Uuid | null;
  bound: boolean;
}): DeliveryDto {
  const now = new Date().toISOString();
  const record = deliveries.insert({
    id: mockId('delivery', `${input.key}@${now}`),
    orderId: input.orderId ?? null,
    chatTitle: input.chatTitle,
    bindingType: input.bindingType,
    status: input.bound ? 'QUEUED' : 'FAILED',
    attempts: input.bound ? 0 : 1,
    error: input.bound ? null : 'Чат не привʼязано до бота — повідомлення нікуди надіслати',
    createdAt: now,
    sentAt: null,
  });
  if (input.bound) {
    if (IN_TESTS) deliver(record.id);
    // запис міг зникнути після скидання сховищ — тоді надсилати нічого
    else setTimeout(() => deliveries.find(record.id) && deliver(record.id), SEND_DELAY_MS);
  }
  return record;
}

/** Розсилка опублікованого розпорядження: чати груп зі змінами + чат диспетчерів. */
export function recordOrderDeliveries(orderId: Uuid, groupIds: readonly Uuid[]): void {
  for (const groupId of groupIds) {
    const group = findGroup(groupId);
    if (!group) continue;
    const binding = bindings.all().find((b) => b.type === 'GROUP_CHAT' && b.groupId === groupId);
    // група без чату просто не отримує повідомлення: це не помилка, а відсутність привʼязки
    if (!binding) continue;
    recordDelivery({ key: `order:${orderId}:${group.code}`, chatTitle: binding.title, bindingType: 'GROUP_CHAT', orderId, bound: true });
  }
  const admin = bindings.all().find((b) => b.type === 'ADMIN_CHAT');
  if (admin) recordDelivery({ key: `order:${orderId}:admin`, chatTitle: admin.title, bindingType: 'ADMIN_CHAT', orderId, bound: true });
}

/** Запит на підтвердження заміни в особистий чат викладача. */
export function recordConfirmationRequest(changeId: Uuid, teacherId: Uuid): void {
  const teacher = findTeacher(teacherId);
  if (!teacher) return;
  const binding = bindings.all().find((b) => b.type === 'TEACHER' && b.teacherId === teacherId);
  recordDelivery({
    key: `confirm:${changeId}`,
    chatTitle: binding?.title ?? teacher.shortName,
    bindingType: 'TEACHER',
    bound: binding !== undefined,
  });
}

function newCode(): string {
  const counter = codeCounter.update((n) => n + 1);
  // без схожих символів (0/O, 1/I): код диктують і переписують руками
  const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  const seed = mockId('binding-code', `${counter}@${Date.now()}`).replace(/-/g, '');
  let code = '';
  for (let i = 0; i < 8; i += 1) code += alphabet[Number.parseInt(seed.slice(i * 2, i * 2 + 2), 16) % alphabet.length];
  return code;
}

const DELIVERY_STATUSES: readonly DeliveryStatus[] = ['QUEUED', 'SENT', 'FAILED', 'RETRYING'];

export const handlers = [
  http.get(apiPath('/notify/bot'), () => {
    const status: BotStatusDto = { username: BOT_USERNAME, link: BOT_LINK, mode: 'POLLING', connected: true };
    return HttpResponse.json(status);
  }),

  http.get(apiPath('/notify/bindings'), () =>
    HttpResponse.json([...bindings.all()].sort((a, b) => b.createdAt.localeCompare(a.createdAt))),
  ),

  http.delete(apiPath('/notify/bindings/:id'), ({ params }) => {
    bindings.remove(String(params.id));
    return new HttpResponse(null, { status: 204 });
  }),

  http.post(apiPath('/notify/bindings/codes'), async ({ request }) => {
    const body = await readJson<Partial<BindingCodeRequest>>(request);
    if (!body.type || !BINDING_TYPES.includes(body.type)) {
      return badRequest('Оберіть, що привʼязуємо.', [{ field: 'type', message: 'Оберіть тип чату' }]);
    }
    if (body.type === 'SUBSCRIBER') {
      return badRequest('Підписники привʼязуються самі через кнопку «Моя група» в боті.', [
        { field: 'type', message: 'Для підписників код не потрібен' },
      ]);
    }
    if (body.type === 'GROUP_CHAT' && !findGroup(body.groupId ?? '')) {
      return badRequest('Оберіть групу, чий чат привʼязуємо.', [{ field: 'groupId', message: 'Оберіть групу' }]);
    }
    if (body.type === 'TEACHER' && !findTeacher(body.teacherId ?? '')) {
      return badRequest('Оберіть викладача.', [{ field: 'teacherId', message: 'Оберіть викладача' }]);
    }
    const code = newCode();
    const result: BindingCodeDto = {
      code,
      // чат групи: бот додається в групу й одразу отримує код (?startgroup), особистий чат — /start <код>
      deepLink: body.type === 'GROUP_CHAT' ? `${BOT_LINK}?startgroup=${code}` : `${BOT_LINK}?start=${code}`,
      expiresAt: new Date(Date.now() + CODE_TTL_MS[body.type]).toISOString(),
    };
    return HttpResponse.json(result, { status: 201 });
  }),

  http.get(apiPath('/notify/deliveries'), ({ request }) => {
    const url = new URL(request.url);
    const orderId = url.searchParams.get('orderId');
    const status = url.searchParams.get('status');
    if (status && !DELIVERY_STATUSES.includes(status as DeliveryStatus)) {
      return badRequest('Невідомий стан доставки.', [{ field: 'status', message: 'QUEUED, SENT, FAILED або RETRYING' }]);
    }
    const list = deliveries
      .all()
      .filter((d) => (!orderId || d.orderId === orderId) && (!status || d.status === status))
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt) || a.chatTitle.localeCompare(b.chatTitle, 'uk'))
      .slice(0, 200);
    return HttpResponse.json(list);
  }),

  http.post(apiPath('/notify/test'), () => {
    recordDelivery({ key: 'test', chatTitle: 'Власник бота (тестове повідомлення)', bindingType: 'ADMIN_CHAT', bound: true });
    return new HttpResponse(null, { status: 202 });
  }),

];
