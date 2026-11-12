/**
 * Редактор розкладу і 409 `stale-data`: коли пару щойно змінив інший диспетчер, кеш занять перечитується,
 * наступна дія йде з новою версією, а старі записи журналу не перезаписують чужу правку.
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '@/api/client';
import { STALE_DATA_MESSAGE } from '@/api/errors';
import { qk } from '@/api/queryKeys';
import { useVersionLessons } from '@/api/timetable';
import type { TemplateLessonDto } from '@/api/types';
import { DEMO_DRAFT_ID } from '@/mocks/handlers/timetable';
import { useJournal } from '@/lib/undo/journal';
import { toLessonRequest } from './model/history';
import { timetableScope, useLessonEditing } from './useLessonEditing';

const toast = vi.hoisted(() => ({ undo: vi.fn(), info: vi.fn(), warning: vi.fn(), error: vi.fn(), success: vi.fn() }));
vi.mock('@/components/ui', async (importOriginal) => ({ ...(await importOriginal<object>()), notify: toast }));
vi.mock('@/components/ui/toast', () => ({ notify: toast }));

const lessonsPath = `/admin/timetable/versions/${DEMO_DRAFT_ID}/lessons`;

/**
 * Демо-заняття мока не мають `version` (тоді сервер-мок версію не перевіряє), а в core вона є завжди:
 * одна правка дає пару з версією, як після справжнього сервера.
 */
async function versionedPlainLesson(): Promise<void> {
  const lesson = plainLesson(await api.get<TemplateLessonDto[]>(lessonsPath));
  await api.put(`${lessonsPath}/${lesson.id}`, { ...toLessonRequest(lesson), version: undefined });
}

async function setup() {
  await versionedPlainLesson();
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
  const hook = renderHook(
    () => ({ editing: useLessonEditing(DEMO_DRAFT_ID), lessons: useVersionLessons(DEMO_DRAFT_ID) }),
    { wrapper },
  );
  return { queryClient, hook };
}

/** Пара без потоку: переміщення не тягне інших учасників. */
function plainLesson(lessons: readonly TemplateLessonDto[]): TemplateLessonDto {
  const lesson = lessons.find((l) => !l.streamId && l.group.code === '4КІ1');
  if (!lesson) throw new Error('немає пари 4КІ1 без потоку');
  return lesson;
}

const cached = (queryClient: QueryClient, id: string) =>
  queryClient.getQueryData<TemplateLessonDto[]>(qk.admin.timetable.lessons(DEMO_DRAFT_ID, {}))?.find((l) => l.id === id);

/** Інший диспетчер змінює пару: версія на сервері росте, а в нашому кеші лишається стара. */
async function foreignEdit(lesson: TemplateLessonDto, change: Partial<TemplateLessonDto> = {}) {
  const body = toLessonRequest({ ...lesson, ...change });
  await api.put(`${lessonsPath}/${lesson.id}`, body);
}

beforeEach(() => {
  useJournal.getState().clear();
  Object.values(toast).forEach((fn) => fn.mockReset());
});

describe('редактор розкладу: 409 stale-data', () => {
  it('після 409 перечитує заняття, тож друге перетягування тієї ж пари проходить', async () => {
    const { queryClient, hook } = await setup();
    await waitFor(() => expect(hook.result.current.lessons.data).toBeDefined());
    const lesson = plainLesson(hook.result.current.lessons.data ?? []);
    await foreignEdit(lesson, { online: !lesson.online });

    const target = { weekday: lesson.weekday, pair: lesson.pair, parity: lesson.parity };
    let moved = true;
    await act(async () => {
      moved = await hook.result.current.editing.moveLesson(lesson, target, null);
    });
    expect(moved).toBe(false);
    // спокійно пояснюємо, що сталося, і що робити далі
    expect(toast.warning).toHaveBeenCalledWith('Пару щойно змінив хтось інший', expect.objectContaining({ description: STALE_DATA_MESSAGE }));
    // кеш перечитано: там уже версія після чужої правки
    await waitFor(() => expect(cached(queryClient, lesson.id)?.version).toBe((lesson.version ?? 0) + 1));

    const fresh = cached(queryClient, lesson.id);
    if (!fresh) throw new Error('пари немає в кеші');
    await act(async () => {
      moved = await hook.result.current.editing.moveLesson(fresh, target, null);
    });
    expect(moved).toBe(true);
  });

  it('діалог зі старою парою: після 409 повторне «Зберегти» йде зі свіжою версією, а скасування повертає чужий стан', async () => {
    const { queryClient, hook } = await setup();
    await waitFor(() => expect(hook.result.current.lessons.data).toBeDefined());
    const snapshot = plainLesson(hook.result.current.lessons.data ?? []);
    await foreignEdit(snapshot, { online: !snapshot.online });
    const body = { ...toLessonRequest(snapshot), pinned: !snapshot.pinned };

    await act(async () => {
      await expect(hook.result.current.editing.updateLesson(snapshot, body)).rejects.toMatchObject({ status: 409 });
    });
    await waitFor(() => expect(cached(queryClient, snapshot.id)?.version).toBe((snapshot.version ?? 0) + 1));

    // той самий знімок із відкритого діалогу — диспетчера вже попередили, тож друга спроба свідома
    let saved: TemplateLessonDto | null = null;
    await act(async () => {
      saved = await hook.result.current.editing.updateLesson(snapshot, body);
    });
    expect(saved).toMatchObject({ pinned: !snapshot.pinned, online: !snapshot.online });

    // Ctrl+Z повертає стан після чужої правки, а не наш старий знімок
    await act(async () => {
      await hook.result.current.editing.undo();
    });
    const lessons = await api.get<TemplateLessonDto[]>(lessonsPath);
    expect(lessons.find((l) => l.id === snapshot.id)).toMatchObject({ pinned: snapshot.pinned, online: !snapshot.online });
  });

  it('старий запис журналу не перезаписує чужу правку навіть після нашої вдалої повторної дії', async () => {
    const { queryClient, hook } = await setup();
    await waitFor(() => expect(hook.result.current.lessons.data).toBeDefined());
    const lesson = plainLesson(hook.result.current.lessons.data ?? []);

    // наша правка (у журналі), потім чужа, потім наша повторна після 409
    await act(async () => {
      await hook.result.current.editing.updateLesson(lesson, { ...toLessonRequest(lesson), pinned: !lesson.pinned });
    });
    const ours = cached(queryClient, lesson.id);
    if (!ours) throw new Error('пари немає в кеші');
    await foreignEdit(ours, { online: !ours.online });
    const target = { weekday: ours.weekday, pair: ours.pair, parity: ours.parity };
    await act(async () => {
      await hook.result.current.editing.moveLesson(ours, target, null);
    });
    await waitFor(() => expect(cached(queryClient, lesson.id)?.version).toBe((ours.version ?? 0) + 1));
    const fresh = cached(queryClient, lesson.id);
    if (!fresh) throw new Error('пари немає в кеші');
    await act(async () => {
      expect(await hook.result.current.editing.moveLesson(fresh, target, null)).toBe(true);
    });

    // скасовуємо нове перенесення — ок; старе «Змінено пару» вже не наше: журнал чесно відмовляє
    await act(async () => {
      await hook.result.current.editing.undo();
    });
    await act(async () => {
      await hook.result.current.editing.undo();
    });
    const scope = timetableScope(DEMO_DRAFT_ID);
    const [latest, older] = useJournal.getState().entries.filter((e) => e.scope === scope);
    expect(latest?.status).toBe('undone');
    expect(older?.status).toBe('stale');
    const lessons = await api.get<TemplateLessonDto[]>(lessonsPath);
    // чужа правка (online) на місці, а наша перша (pinned) — теж: її ніхто наосліп не відкотив
    expect(lessons.find((l) => l.id === lesson.id)).toMatchObject({ online: !lesson.online, pinned: !lesson.pinned });
  });
});
