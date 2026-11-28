/**
 * Власний пароль (CONTRACTS §0.1) — сервіс входу `auth`, не gateway: шлях `<видавець>/api/account/password`,
 * тому обробник ловить будь-яке походження (`*`). Відмови — як в `auth`: `type` `urn:rozklad:auth:<code>`,
 * той самий `code` окремим полем і українське `detail`.
 *
 * Мок не знає облікових записів: «поточний» пароль — `MOCK_CURRENT_PASSWORD`, після зміни — новий.
 * Перевірка нового пароля — спрощена `PasswordPolicy` (довжина, поширені, той самий, що й поточний).
 *
 * Вхід лише через Google (перший пароль): `sessionStorage.setItem('rozklad.mockPassword', 'google')` і
 * перезавантаження — пароля немає, меню каже «Встановити пароль». «Підтвердити через Google» у мок-режимі не йде
 * до Google: `POST <видавець>/mock/google-reauth` запамʼятовує час (у sessionStorage, бо сторінка перезавантажується)
 * і сторінка повертається з `?password=set`. Підтвердження діє 10 хвилин, як у сервісі входу.
 */
import { http, HttpResponse } from 'msw';
import type { ChangeOwnPasswordRequest, OwnPasswordInfoDto, SetInitialPasswordRequest } from '@/api/account';
import { readJson } from '../kit';
import { createMockStore } from '../store';

/** ДЕМО: пароль фіктивного користувача мока. */
export const MOCK_CURRENT_PASSWORD = 'Rozklad-demo-2026';
/** Ключ sessionStorage: `'google'` — фіктивний користувач входить лише через Google і пароля не має. */
export const MOCK_PASSWORD_STORAGE_KEY = 'rozklad.mockPassword';
/** Ключ sessionStorage: коли мок «підтвердив вхід через Google» (мс). */
export const MOCK_GOOGLE_REAUTH_STORAGE_KEY = 'rozklad.mockGoogleReauthAt';
const REAUTH_WINDOW_MS = 10 * 60_000;
const MIN_LENGTH = 12;
const MAX_LENGTH = 64;
const COMMON = new Set(['password1234', 'qwertyuiop123', '123456789012']);

function storage(): Storage | null {
  try {
    return typeof window === 'undefined' ? null : window.sessionStorage;
  } catch {
    return null;
  }
}

const passwordStore = createMockStore(() => {
  const session = storage();
  const googleOnly = session?.getItem(MOCK_PASSWORD_STORAGE_KEY) === 'google';
  const reauthAt = Number(session?.getItem(MOCK_GOOGLE_REAUTH_STORAGE_KEY) ?? '') || null;
  return { current: googleOnly ? null : MOCK_CURRENT_PASSWORD, failures: 0, reauthAt };
});

function reauthFresh(reauthAt: number | null): boolean {
  return reauthAt !== null && Date.now() - reauthAt <= REAUTH_WINDOW_MS;
}

const PATH = '*/auth/api/account/password';
const INITIAL_PATH = '*/auth/api/account/initial-password';
const MOCK_REAUTH_PATH = '*/auth/mock/google-reauth';

function authProblem(status: number, code: string, detail: string, extensions: Record<string, unknown> = {}): Response {
  return HttpResponse.json(
    { type: `urn:rozklad:auth:${code}`, title: detail, status, detail, code, ...extensions },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  );
}

/** Як `PasswordPolicy` у auth (без логіна й e-mail: мок їх не знає). */
function rejection(next: string, current: string | null): { reason: string; detail: string } | null {
  const length = [...next].length;
  if (length < MIN_LENGTH) return { reason: 'too-short', detail: `Новий пароль має містити щонайменше ${MIN_LENGTH} символів.` };
  if (length > MAX_LENGTH) return { reason: 'too-long', detail: `Новий пароль задовгий: не більше ${MAX_LENGTH} символів.` };
  if (COMMON.has(next.toLowerCase())) return { reason: 'too-common', detail: 'Цей пароль надто поширений. Придумайте інший.' };
  if (next === current) return { reason: 'same-as-current', detail: 'Новий пароль збігається з поточним.' };
  return null;
}

export const handlers = [
  http.get(PATH, () => {
    const { current, reauthAt } = passwordStore.get();
    return HttpResponse.json<OwnPasswordInfoDto>({
      status: current === null ? 'NO_PASSWORD' : 'CHANGEABLE',
      minLength: MIN_LENGTH,
      maxLength: MAX_LENGTH,
      reauthRequired: current === null && !reauthFresh(reauthAt),
    });
  }),

  http.post(INITIAL_PATH, async ({ request }) => {
    const body = await readJson<Partial<SetInitialPasswordRequest>>(request);
    if (!body.newPassword) return authProblem(400, 'validation', 'Вкажіть новий пароль.');
    const state = passwordStore.get();
    if (state.current !== null) {
      return authProblem(409, 'password-already-set', 'У цього облікового запису вже є пароль. Змініть його, вказавши поточний.');
    }
    if (!reauthFresh(state.reauthAt)) {
      return authProblem(403, 'reauth-required', 'Щоб встановити пароль, підтвердьте вхід через Google ще раз у цьому браузері.');
    }
    const refused = rejection(body.newPassword, null);
    if (refused) return authProblem(400, 'password-rejected', refused.detail, { reason: refused.reason });
    // підтвердження використано; далі — звичайна зміна пароля
    passwordStore.set({ current: body.newPassword, failures: 0, reauthAt: null });
    storage()?.removeItem(MOCK_GOOGLE_REAUTH_STORAGE_KEY);
    storage()?.removeItem(MOCK_PASSWORD_STORAGE_KEY);
    return new HttpResponse(null, { status: 204 });
  }),

  /** Лише мок: замість Google — «щойно підтвердили вхід» у цьому браузері. */
  http.post(MOCK_REAUTH_PATH, () => {
    const now = Date.now();
    passwordStore.update((state) => ({ ...state, reauthAt: now }));
    storage()?.setItem(MOCK_GOOGLE_REAUTH_STORAGE_KEY, String(now));
    return new HttpResponse(null, { status: 204 });
  }),

  http.post(PATH, async ({ request }) => {
    const body = await readJson<Partial<ChangeOwnPasswordRequest>>(request);
    if (!body.currentPassword || !body.newPassword) {
      return authProblem(400, 'validation', 'Вкажіть поточний і новий пароль.');
    }
    const state = passwordStore.get();
    if (state.failures >= 10) {
      return authProblem(429, 'too-many-attempts', 'Забагато невдалих спроб. Спробуйте пізніше.');
    }
    if (state.current === null) {
      return authProblem(409, 'password-not-set', 'У цього облікового запису ще немає пароля: вхід лише через Google.');
    }
    if (body.currentPassword !== state.current) {
      passwordStore.set({ ...state, failures: state.failures + 1 });
      return authProblem(422, 'current-password-wrong', 'Поточний пароль неправильний.');
    }
    const refused = rejection(body.newPassword, state.current);
    if (refused) return authProblem(400, 'password-rejected', refused.detail, { reason: refused.reason });
    passwordStore.set({ ...state, current: body.newPassword, failures: 0 });
    return new HttpResponse(null, { status: 204 });
  }),
];
