import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { downloadIcs, openOrderDocument } from './public';

vi.mock('@/auth/userManager', () => ({ getAccessToken: () => Promise.resolve('teacher-token') }));

const SERVER_NAME = 'rozporiadzhennia-35-2026-10-19.pdf';
const TITLE = 'Розпорядження №35 від 19.10.2026.pdf';

const fetchMock = vi.fn<typeof fetch>();
/** Назви файлів, які сторінка віддала браузеру на збереження (посилання з `download`). */
let saved: string[] = [];

function fileResponse(body: string, type: string, disposition?: string): Response {
  return new Response(body, { headers: { 'Content-Type': type, ...(disposition ? { 'Content-Disposition': disposition } : {}) } });
}

/** Вкладка «Завантажуємо розпорядження…», яку повертає `window.open`. */
function placeholderTab() {
  return { document: { title: '', body: { textContent: '' } }, location: { href: '' }, opener: {} as unknown, closed: false, close: vi.fn() };
}

beforeEach(() => {
  saved = [];
  vi.stubGlobal('fetch', fetchMock);
  Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:order-35'), revokeObjectURL: vi.fn() });
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
    saved.push(this.download);
  });
});

afterEach(() => {
  fetchMock.mockReset();
  // jsdom не має createObjectURL: прибираємо заглушки
  Reflect.deleteProperty(URL, 'createObjectURL');
  Reflect.deleteProperty(URL, 'revokeObjectURL');
});

describe('openOrderDocument (персонал, файл з токеном)', () => {
  it('PDF ставить у вкладку, відкриту в тому ж натисканні; вкладка без доступу до сайту', async () => {
    const tab = placeholderTab();
    vi.spyOn(window, 'open').mockReturnValue(tab as unknown as Window);
    fetchMock.mockResolvedValueOnce(fileResponse('%PDF', 'application/pdf', `inline; filename="${SERVER_NAME}"`));

    await openOrderDocument('o1', 'pdf', TITLE);

    expect(new Headers(fetchMock.mock.calls[0]?.[1]?.headers).get('Authorization')).toBe('Bearer teacher-token');
    expect(tab.location.href).toBe('blob:order-35');
    expect(tab.opener).toBeNull();
    expect(saved).toEqual([]);
  });

  it('браузер заблокував вкладку — файл зберігається під назвою від сервера', async () => {
    vi.spyOn(window, 'open').mockReturnValue(null);
    fetchMock.mockResolvedValueOnce(fileResponse('%PDF', 'application/pdf', `inline; filename="${SERVER_NAME}"`));

    await openOrderDocument('o1', 'pdf', TITLE);

    expect(saved).toEqual([SERVER_NAME]);
  });

  it('людина закрила вкладку «Завантажуємо…», поки файл ішов, — це скасування: нічого не зберігається', async () => {
    const tab = placeholderTab();
    vi.spyOn(window, 'open').mockReturnValue(tab as unknown as Window);
    fetchMock.mockImplementationOnce(() => {
      tab.closed = true;
      return Promise.resolve(fileResponse('%PDF', 'application/pdf', `inline; filename="${SERVER_NAME}"`));
    });

    await openOrderDocument('o1', 'pdf', TITLE);

    expect(saved).toEqual([]);
    expect(tab.location.href).toBe('');
  });

  it('DOCX завантажується без вкладки; сервер не дав назви — та, що дала сторінка', async () => {
    const open = vi.spyOn(window, 'open');
    fetchMock.mockResolvedValueOnce(fileResponse('PK', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'));

    await openOrderDocument('o1', 'docx', 'Розпорядження №35 від 19.10.2026.docx');

    expect(open).not.toHaveBeenCalled();
    expect(saved).toEqual(['Розпорядження №35 від 19.10.2026.docx']);
  });

  it('помилка закриває порожню вкладку й доходить до викликача', async () => {
    const tab = placeholderTab();
    vi.spyOn(window, 'open').mockReturnValue(tab as unknown as Window);
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 503 }));

    await expect(openOrderDocument('o1', 'pdf', TITLE)).rejects.toMatchObject({ status: 503 });
    expect(tab.close).toHaveBeenCalledOnce();
    expect(saved).toEqual([]);
  });
});

describe('downloadIcs (файл .ics із сайту)', () => {
  it('персонал завантажує з токеном (увесь семестр), гість — без нього', async () => {
    fetchMock.mockImplementation(() => Promise.resolve(fileResponse('BEGIN:VCALENDAR', 'text/calendar')));

    await downloadIcs('GROUP', 'g1', 'rozklad-4КІ1.ics', 'staff');
    await downloadIcs('GROUP', 'g1', 'rozklad-4КІ1.ics', 'guest');

    const authorization = fetchMock.mock.calls.map(([, init]) => new Headers(init?.headers).get('Authorization'));
    expect(authorization).toEqual(['Bearer teacher-token', null]);
    expect(saved).toEqual(['rozklad-4КІ1.ics', 'rozklad-4КІ1.ics']);
  });
});
