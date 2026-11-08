/**
 * Налаштування системи (CONTRACTS §2: GET/PUT /api/admin/settings). Дзвінки (GET /api/public/bells) мокує
 * область публічних сторінок (handlers/public.ts), витрати ШІ — область імпорту (handlers/import.ts).
 */
import { http, HttpResponse } from 'msw';
import { DEFAULT_CONFIRM_TIMEOUT_MINUTES, SETTINGS_LIMITS } from '@/api/settings-limits';
import type { ApprovalMode, FieldErrorDto, SettingsDto } from '@/api/types';
import { ACTIVE_BELL_PROFILE, BELL_PROFILES } from '../fixtures';
import { apiPath, badRequest, readJson } from '../kit';
import { createMockStore } from '../store';
import { COLLEGE_NAME } from './meta';

/** Початкові значення — типові значення core (V1_802): «Заступник директора з НР» / «Микола ПОЛІЩУК», 2 год на пару, 120 хв. */
const seed = (): SettingsDto => ({
  approvalMode: 'CONFIRM',
  confirmTimeoutMinutes: DEFAULT_CONFIRM_TIMEOUT_MINUTES,
  signatoryPosition: 'Заступник директора з НР',
  signatoryName: 'Микола ПОЛІЩУК',
  collegeName: COLLEGE_NAME,
  hoursPerPair: 2,
  showTeacherFullNames: false,
  activeBellProfileId: ACTIVE_BELL_PROFILE.id,
});

const settings = createMockStore<SettingsDto>(seed);

/** Поточні налаштування — для інших областей мок-API (режим погодження замін, підписант розпорядження). */
export function currentSettings(): SettingsDto {
  return settings.get();
}

const APPROVAL_MODES: readonly ApprovalMode[] = ['AUTO', 'CONFIRM'];

function isInteger(value: unknown, min: number, max: number): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value >= min && value <= max;
}

function textError(value: unknown, field: string, max: number, empty: string, min = 1): FieldErrorDto | null {
  if (typeof value !== 'string' || value.trim().length < min) return { field, message: empty };
  if (value.trim().length > max) return { field, message: `Не довше ${max} символів` };
  return null;
}

/** Перевірка як у core (Bean Validation): усі помилки полів одразу, українською. */
export function validateSettings(body: Partial<Record<keyof SettingsDto, unknown>>): FieldErrorDto[] {
  const errors: FieldErrorDto[] = [];
  const push = (error: FieldErrorDto | null) => {
    if (error) errors.push(error);
  };
  if (!APPROVAL_MODES.includes(body.approvalMode as ApprovalMode)) {
    errors.push({ field: 'approvalMode', message: 'Оберіть режим погодження замін' });
  }
  const timeout = SETTINGS_LIMITS.confirmTimeoutMinutes;
  if (!isInteger(body.confirmTimeoutMinutes, timeout.min, timeout.max)) {
    errors.push({ field: 'confirmTimeoutMinutes', message: `Ціле число від ${timeout.min} до ${timeout.max} хвилин` });
  }
  push(textError(body.signatoryPosition, 'signatoryPosition', SETTINGS_LIMITS.signatoryPosition, 'Вкажіть посаду підписанта'));
  push(textError(body.signatoryName, 'signatoryName', SETTINGS_LIMITS.signatoryName, 'Вкажіть імʼя підписанта'));
  push(
    textError(
      body.collegeName,
      'collegeName',
      SETTINGS_LIMITS.collegeName.max,
      'Вкажіть назву коледжу',
      SETTINGS_LIMITS.collegeName.min,
    ),
  );
  const hours = SETTINGS_LIMITS.hoursPerPair;
  if (!isInteger(body.hoursPerPair, hours.min, hours.max)) {
    errors.push({ field: 'hoursPerPair', message: `Ціле число від ${hours.min} до ${hours.max}` });
  }
  if (typeof body.showTeacherFullNames !== 'boolean') {
    errors.push({ field: 'showTeacherFullNames', message: 'Має бути так або ні' });
  }
  if (body.activeBellProfileId !== null && !BELL_PROFILES.some((p) => p.id === body.activeBellProfileId)) {
    errors.push({ field: 'activeBellProfileId', message: 'Такого профілю дзвінків немає' });
  }
  return errors;
}

export const handlers = [
  http.get(apiPath('/admin/settings'), () => HttpResponse.json(settings.get())),

  http.put(apiPath('/admin/settings'), async ({ request }) => {
    const body = await readJson<Partial<Record<keyof SettingsDto, unknown>>>(request);
    const errors = validateSettings(body);
    if (errors.length > 0) return badRequest('Перевірте налаштування.', errors);
    const next: SettingsDto = {
      approvalMode: body.approvalMode as ApprovalMode,
      confirmTimeoutMinutes: body.confirmTimeoutMinutes as number,
      signatoryPosition: (body.signatoryPosition as string).trim(),
      signatoryName: (body.signatoryName as string).trim(),
      collegeName: (body.collegeName as string).trim(),
      hoursPerPair: body.hoursPerPair as number,
      showTeacherFullNames: body.showTeacherFullNames as boolean,
      activeBellProfileId: (body.activeBellProfileId as string | null) ?? null,
    };
    settings.set(next);
    return HttpResponse.json(next);
  }),

];
