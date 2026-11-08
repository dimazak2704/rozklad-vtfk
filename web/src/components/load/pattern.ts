/**
 * Тижневий шаблон навантаження (DOMAIN §4): `пар на тиждень = години / годин на пару`;
 * ціла частина — пари щотижня, залишок від ¼ до ¾ — одна «половинка» (пара раз на два тижні:
 * чисельник або знаменник), залишок від ¾ — ще одна ціла пара. Ті самі правила, що в core (LoadPattern).
 */

/** Академічних годин на одну пару за замовчуванням (налаштування `accounting.hoursPerPair`). */
export const DEFAULT_HOURS_PER_PAIR = 2;

export interface WeeklyPattern {
  /** Пари щотижня. */
  whole: number;
  /** 0 або 1: пара раз на два тижні. */
  half: number;
}

export function loadPattern(hoursPerWeek: number, hoursPerPair = DEFAULT_HOURS_PER_PAIR): WeeklyPattern {
  if (!Number.isFinite(hoursPerWeek) || hoursPerWeek <= 0) return { whole: 0, half: 0 };
  if (!Number.isFinite(hoursPerPair) || hoursPerPair < 1) throw new RangeError('Годин на пару має бути не менше 1');
  const pairs = Math.round((hoursPerWeek / hoursPerPair) * 10_000) / 10_000;
  const whole = Math.floor(pairs);
  const remainder = pairs - whole;
  if (remainder >= 0.75) return { whole: whole + 1, half: 0 };
  if (remainder >= 0.25) return { whole, half: 1 };
  return { whole, half: 0 };
}

/** «1 + ½», «2», «½», «0». */
export function patternLabel({ whole, half }: WeeklyPattern): string {
  if (whole === 0 && half === 0) return '0';
  if (whole === 0) return '½';
  return half > 0 ? `${whole} + ½` : String(whole);
}

/** Пояснення людською мовою для підказки й форми. */
export function patternDescription({ whole, half }: WeeklyPattern): string {
  if (whole === 0 && half === 0) return 'Пар у розкладі не буде.';
  const every = whole > 0 ? `${whole} ${whole === 1 ? 'пара' : whole < 5 ? 'пари' : 'пар'} щотижня` : '';
  const alternate = half > 0 ? 'ще 1 пара через тиждень (лише чисельник або лише знаменник)' : '';
  if (every && alternate) return `${every} і ${alternate}.`;
  return `${every || alternate.charAt(0).toLocaleUpperCase('uk') + alternate.slice(1)}.`;
}

/** Пари на тиждень у середньому: 1 + ½ → 1,5. */
export function pairsPerWeek({ whole, half }: WeeklyPattern): number {
  return whole + half / 2;
}

/** «3», «1,5» — години з комою, як пишуть у плані. */
export function formatHours(hours: number): string {
  return Number.isInteger(hours) ? String(hours) : hours.toFixed(1).replace('.', ',');
}
