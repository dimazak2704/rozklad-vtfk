/**
 * Власний пароль (CONTRACTS §0.1) — сервіс входу `auth`, не gateway: шлях `<видавець>/api/account/password`,
 * тому обробник ловить будь-яке походження (`*`). Відмови — як в `auth`: `type` `urn:rozklad:auth:<code>`,
 * той самий `code` окремим полем і українське `detail`.
 *
 * Мок не знає облікових записів: «поточний» пароль — `MOCK_CURRENT_PASSWORD`, після зміни — новий.
 * Перевірка нового пароля — спрощена `PasswordPolicy` (довжина, поширені, той самий, що й поточний).
 */
import { http, HttpResponse } from 'msw';
import type { ChangeOwnPasswordRequest, OwnPasswordInfoDto } from '@/api/account';
import { readJson } from '../kit';
import { createMockStore } from '../store';

/** ДЕМО: пароль фіктивного користувача мока. */
export const MOCK_CURRENT_PASSWORD = 'Rozklad-demo-2026';
const MIN_LENGTH = 12;
const MAX_LENGTH = 64;
const COMMON = new Set(['password1234', 'qwertyuiop123', '123456789012']);

const passwordStore = createMockStore(() => ({ current: MOCK_CURRENT_PASSWORD, failures: 0 }));

const PATH = '*/auth/api/account/password';

function authProblem(status: number, code: string, detail: string, extensions: Record<string, unknown> = {}): Response {
  return HttpResponse.json(
    { type: `urn:rozklad:auth:${code}`, title: detail, status, detail, code, ...extensions },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  );
}

/** Як `PasswordPolicy` у auth (без логіна й e-mail: мок їх не знає). */
function rejection(next: string, current: string): { reason: string; detail: string } | null {
  const length = [...next].length;
  if (length < MIN_LENGTH) return { reason: 'too-short', detail: `Новий пароль має містити щонайменше ${MIN_LENGTH} символів.` };
  if (length > MAX_LENGTH) return { reason: 'too-long', detail: `Новий пароль задовгий: не більше ${MAX_LENGTH} символів.` };
  if (COMMON.has(next.toLowerCase())) return { reason: 'too-common', detail: 'Цей пароль надто поширений. Придумайте інший.' };
  if (next === current) return { reason: 'same-as-current', detail: 'Новий пароль збігається з поточним.' };
  return null;
}

export const handlers = [
  http.get(PATH, () => HttpResponse.json<OwnPasswordInfoDto>({ status: 'CHANGEABLE', minLength: MIN_LENGTH, maxLength: MAX_LENGTH })),

  http.post(PATH, async ({ request }) => {
    const body = await readJson<Partial<ChangeOwnPasswordRequest>>(request);
    if (!body.currentPassword || !body.newPassword) {
      return authProblem(400, 'validation', 'Вкажіть поточний і новий пароль.');
    }
    const state = passwordStore.get();
    if (state.failures >= 10) {
      return authProblem(429, 'too-many-attempts', 'Забагато невдалих спроб. Спробуйте пізніше.');
    }
    if (body.currentPassword !== state.current) {
      passwordStore.set({ ...state, failures: state.failures + 1 });
      return authProblem(422, 'current-password-wrong', 'Поточний пароль неправильний.');
    }
    const refused = rejection(body.newPassword, state.current);
    if (refused) return authProblem(400, 'password-rejected', refused.detail, { reason: refused.reason });
    passwordStore.set({ current: body.newPassword, failures: 0 });
    return new HttpResponse(null, { status: 204 });
  }),
];
