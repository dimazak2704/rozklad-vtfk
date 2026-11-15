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
  changesHistoryDays: { min: 0, max: 365 },
  /** genfix §3.4: `generation.teacherMaxPairsPerDay`. */
  teacherMaxPairsPerDay: { min: 1, max: 8 },
} as const;

/** Типове очікування підтвердження заміни в core (хвилини), якщо його не змінювали (V1_802). */
export const DEFAULT_CONFIRM_TIMEOUT_MINUTES = 120;
/** Скільки днів назад гості бачать заміни й розклад, якщо налаштування не змінювали (як у core). */
export const DEFAULT_CHANGES_HISTORY_DAYS = 7;
/** Найбільше пар на день у викладача для генератора, якщо не змінювали (як у core, genfix §3.4). */
export const DEFAULT_TEACHER_MAX_PAIRS_PER_DAY = 4;
