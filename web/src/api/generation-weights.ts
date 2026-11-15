/**
 * Ваги генератора (contracts `GenerationWeights`, genfix §1.1). Окремий модуль без HTTP-клієнта:
 * його імпортують і інтерфейс, і мок-API, тож тут не можна тягнути `./client` чи `./live`.
 */
import type { GenerationWeights, GenerationWeightsWire } from './types';

/**
 * Ваги за замовчуванням — ті самі, що підставляє core для `weights: null` (GenerationWeights.DEFAULT,
 * genfix §1.1 після оцінки на реальних запитах; відповіді власника 15.11.2026: 4-та пара зміни вимкнена, підгрупи
 * разом — найсильніше: 30, 10, 0, 5, 1, 10, 3, 1, 30, 10, 5, 3, 10, 30).
 */
export const DEFAULT_WEIGHTS: Readonly<GenerationWeights> = {
  windows: 30,
  brokenDays: 10,
  latePairs: 0,
  preferences: 5,
  homeRoom: 1,
  teacherWindows: 10,
  teacherShiftWait: 3,
  stability: 1,
  subgroupsTogether: 30,
  studentOnePairDay: 10,
  teacherDays: 5,
  teacherOverload: 3,
  disciplineTwice: 10,
  specialRooms: 30,
};

/** Межа ваги з контракту (`@Max(1000)`). */
export const WEIGHT_LIMIT = 1000;

/**
 * Ваги задачі в повному вигляді, як їх нормалізує core: стара задача має лише перші пʼять —
 * `teacherWindows` тоді дорівнює `windows`, решта типові. null — ваг немає (старий сервер).
 */
export function normalizeWeights(weights: GenerationWeightsWire | null | undefined): GenerationWeights | null {
  if (!weights) return null;
  const full: GenerationWeights = { ...DEFAULT_WEIGHTS, teacherWindows: weights.windows };
  for (const key of Object.keys(DEFAULT_WEIGHTS) as Array<keyof GenerationWeights>) {
    const value = weights[key];
    if (typeof value === 'number') full[key] = value;
  }
  return full;
}

