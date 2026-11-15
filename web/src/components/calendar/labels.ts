import type { CalendarPeriodKind, PeriodEffect, PeriodScope, StudyMode } from '@/api/types';
import type { BadgeTone, RadioOption } from '@/components/ui';

/** Ефект словами, як його бачить диспетчер у довіднику видів. */
export const EFFECT_LABEL: Record<PeriodEffect, string> = {
  DAY_OFF: 'Вихідний',
  SUSPEND: 'Група не на парах',
  ONLINE: 'Форма навчання',
  INFO: 'Лише позначка',
};

/** Що період цього виду робить із заняттями — підказка у формах. */
export const EFFECT_HINT: Record<PeriodEffect, string> = {
  DAY_OFF: 'Пар немає ні в кого з указаних груп; для всіх груп день стає вихідним для коледжу.',
  SUSPEND: 'Пари групи знімаються, викладачі працюють. Можна вказати аудиторію й керівників.',
  ONLINE: 'Пари лишаються, але позначаються як онлайн чи змішані; аудиторія вільна.',
  INFO: 'Пари не змінюються; період видно в календарі й розпорядженнях.',
};

/** Приклади кодів графіка коледжу для кожного ефекту. */
export const EFFECT_EXAMPLES: Record<PeriodEffect, string> = {
  DAY_OFF: 'канікули, святкові дні',
  SUSPEND: 'практика, НС, збори, сесія',
  ONLINE: 'дистанційне чи змішане навчання',
  INFO: 'резерв навчального часу',
};

export const EFFECT_ORDER: readonly PeriodEffect[] = ['DAY_OFF', 'SUSPEND', 'ONLINE', 'INFO'];

export const EFFECT_TONE: Record<PeriodEffect, BadgeTone> = {
  DAY_OFF: 'cancel',
  SUSPEND: 'change',
  ONLINE: 'room',
  INFO: 'accent',
};

/** Крапка ефекту, коли вид не має свого кольору (як смуга на шкалі семестру). */
export const EFFECT_DOT: Record<PeriodEffect, string> = {
  DAY_OFF: 'bg-[var(--fill-cancel)]',
  SUSPEND: 'bg-[var(--fill-change)]',
  ONLINE: 'bg-[var(--fill-room)]',
  INFO: 'bg-accent',
};

export const EFFECT_OPTIONS: ReadonlyArray<RadioOption<PeriodEffect>> = EFFECT_ORDER.map((effect) => ({
  value: effect,
  label: EFFECT_LABEL[effect],
  description: `${EFFECT_HINT[effect]} Напр.: ${EFFECT_EXAMPLES[effect]}.`,
}));

/**
 * Вбудований вид для старого `kind` (CONTRACTS: HOLIDAY→К, NO_CLASSES→БЗ, PRACTICE→НП, STUDY_MODE→ФН,
 * EXAM_SESSION→І): так інтерфейс показує період від старішого core, який ще не віддає `periodKind`.
 */
export const LEGACY_KIND: Record<CalendarPeriodKind, { code: string; name: string; effect: PeriodEffect }> = {
  HOLIDAY: { code: 'К', name: 'Канікули', effect: 'DAY_OFF' },
  NO_CLASSES: { code: 'БЗ', name: 'Без занять', effect: 'DAY_OFF' },
  PRACTICE: { code: 'НП', name: 'Практика', effect: 'SUSPEND' },
  STUDY_MODE: { code: 'ФН', name: 'Форма навчання', effect: 'ONLINE' },
  EXAM_SESSION: { code: 'І', name: 'Сесія', effect: 'SUSPEND' },
};

export const STUDY_MODE_LABEL: Record<StudyMode, string> = {
  OFFLINE: 'Очно',
  ONLINE: 'Дистанційно',
  MIXED: 'Змішано',
};

export const SCOPE_LABEL: Record<PeriodScope, string> = {
  ALL: 'Усі групи',
  COURSES: 'Курси',
  GROUPS: 'Окремі групи',
};
