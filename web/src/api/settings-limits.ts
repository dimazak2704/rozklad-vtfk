/**
 * Межі значень налаштувань — рівно як у core (`SystemSettings` + Bean Validation у `SettingsDto`).
 * Окремий модуль без HTTP-клієнта: його імпортують і форма, і мок-API.
 */
export const SETTINGS_LIMITS = {
  confirmTimeoutMinutes: { min: 5, max: 1440 },
  hoursPerPair: { min: 1, max: 4 },
  signatoryPosition: 200,
  signatoryName: 100,
  collegeName: { min: 1, max: 200 },
} as const;

/** Типове очікування підтвердження заміни в core (хвилини), якщо його не змінювали (V1_802). */
export const DEFAULT_CONFIRM_TIMEOUT_MINUTES = 120;
