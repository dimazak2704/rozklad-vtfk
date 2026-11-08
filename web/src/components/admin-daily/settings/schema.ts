/**
 * Перевірка налаштувань на клієнті — ті самі межі, що й на сервері (SETTINGS_LIMITS = core SystemSettings;
 * мок — src/mocks/handlers/settings.ts). Повідомлення українською, поруч із полем.
 */
import { z } from 'zod';
import { SETTINGS_LIMITS } from '@/api/settings-limits';

export const LIMITS = SETTINGS_LIMITS;

const minutes = LIMITS.confirmTimeoutMinutes;
const hours = LIMITS.hoursPerPair;

export const settingsSchema = z.object({
  approvalMode: z.enum(['AUTO', 'CONFIRM'], { error: 'Оберіть режим погодження замін' }),
  confirmTimeoutMinutes: z
    .number({ error: 'Вкажіть, скільки хвилин чекати' })
    .int({ error: 'Лише ціле число хвилин' })
    .min(minutes.min, { error: `Не менше ${minutes.min} хвилин` })
    .max(minutes.max, { error: `Не більше ${minutes.max} хвилин` }),
  signatoryPosition: z
    .string()
    .trim()
    .min(1, { error: 'Вкажіть посаду підписанта' })
    .max(LIMITS.signatoryPosition, { error: `Не довше ${LIMITS.signatoryPosition} символів` }),
  signatoryName: z
    .string()
    .trim()
    .min(1, { error: 'Вкажіть імʼя підписанта' })
    .max(LIMITS.signatoryName, { error: `Не довше ${LIMITS.signatoryName} символів` }),
  collegeName: z
    .string()
    .trim()
    .min(LIMITS.collegeName.min, { error: 'Вкажіть назву коледжу' })
    .max(LIMITS.collegeName.max, { error: `Не довше ${LIMITS.collegeName.max} символів` }),
  hoursPerPair: z
    .number({ error: 'Вкажіть кількість годин' })
    .int({ error: 'Лише ціле число' })
    .min(hours.min, { error: `Від ${hours.min} до ${hours.max}` })
    .max(hours.max, { error: `Від ${hours.min} до ${hours.max}` }),
  showTeacherFullNames: z.boolean(),
  activeBellProfileId: z.string().nullable(),
});

export type SettingsFormValues = z.infer<typeof settingsSchema>;
