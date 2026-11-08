import type {
  AbsenceReasonCategory,
  ChangeKind,
  ChangeStatus,
  LessonKind,
  LessonViewKind,
  Parity,
  ReasonCode,
  RoomType,
  WeekParity,
} from '@/api/types';

/** Назви днів тижня, індекс 1 = понеділок (ISO). */
export const WEEKDAY_NAMES = [
  '',
  'понеділок',
  'вівторок',
  'середа',
  'четвер',
  'пʼятниця',
  'субота',
  'неділя',
] as const;

export const WEEKDAY_SHORT = ['', 'Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб', 'Нд'] as const;

/** Знахідний відмінок — після «на»: «на середу», «на пʼятницю». */
export const WEEKDAY_ACCUSATIVE = ['', 'понеділок', 'вівторок', 'середу', 'четвер', 'пʼятницю', 'суботу', 'неділю'] as const;

/** Родовий відмінок — після «до»: «до понеділка», «до середи». */
export const WEEKDAY_GENITIVE = ['', 'понеділка', 'вівторка', 'середи', 'четверга', 'пʼятниці', 'суботи', 'неділі'] as const;

/** Родовий відмінок: «19 жовтня». */
export const MONTHS_GENITIVE = [
  'січня',
  'лютого',
  'березня',
  'квітня',
  'травня',
  'червня',
  'липня',
  'серпня',
  'вересня',
  'жовтня',
  'листопада',
  'грудня',
] as const;

export const MONTHS_NOMINATIVE = [
  'січень',
  'лютий',
  'березень',
  'квітень',
  'травень',
  'червень',
  'липень',
  'серпень',
  'вересень',
  'жовтень',
  'листопад',
  'грудень',
] as const;

export function weekdayName(isoWeekday: number): string {
  return WEEKDAY_NAMES[isoWeekday] ?? '';
}

export function weekdayShort(isoWeekday: number): string {
  return WEEKDAY_SHORT[isoWeekday] ?? '';
}

export function capitalize(text: string): string {
  return text.length > 0 ? text.charAt(0).toLocaleUpperCase('uk') + text.slice(1) : text;
}

export const PARITY_LABEL: Record<Parity, string> = {
  BOTH: 'щотижня',
  NUMERATOR: 'чисельник',
  DENOMINATOR: 'знаменник',
};

/** Пояснення для підказки над бейджем парності. */
export const PARITY_HINT: Record<WeekParity, string> = {
  NUMERATOR:
    'Тижні семестру чергуються: чисельник і знаменник. У чисельник проходять пари з верхнього рядка друкованого розкладу.',
  DENOMINATOR:
    'Тижні семестру чергуються: чисельник і знаменник. У знаменник проходять пари з нижнього рядка друкованого розкладу.',
};

export const LESSON_KIND_LABEL: Record<LessonKind, string> = {
  LECTURE: 'лекція',
  PRACTICE: 'практичне',
  LAB: 'лабораторна',
  SEMINAR: 'семінар',
  MIXED: 'змішане',
};

export const LESSON_VIEW_KIND_LABEL: Record<LessonViewKind, string> = {
  REGULAR: 'За розкладом',
  SUBSTITUTED: 'Заміна',
  CANCELLED: 'Пари не буде',
  ADDITIONAL: 'Додаткова пара',
  MOVED: 'Перенесено',
  ROOM_CHANGED: 'Інша аудиторія',
  BLOCK: 'Блок пар',
};

export const CHANGE_KIND_LABEL: Record<ChangeKind, string> = {
  REPLACE_TEACHER: 'Заміна викладача',
  REPLACE_DISCIPLINE: 'Заміна дисципліни',
  SWAP: 'Перестановка пар',
  CANCEL: 'Скасування пари',
  ADDITIONAL: 'Додаткова пара',
  ROOM_CHANGE: 'Зміна аудиторії',
  BLOCK: 'Блок пар',
  DISCIPLINE_FINISHED: 'Дисципліну вичитано',
};

export const CHANGE_STATUS_LABEL: Record<ChangeStatus, string> = {
  DRAFT: 'Чернетка',
  PENDING_CONFIRMATION: 'Чекає підтвердження',
  CONFIRMED: 'Підтверджено',
  DECLINED: 'Відхилено',
  CANCELLED: 'Скасовано',
  APPLIED: 'Внесено в розпорядження',
};

/** Скорочення причин так, як їх друкують у розпорядженні. */
export const REASON_CODE_LABEL: Record<ReasonCode, string> = {
  ABSENT: 'Відп.',
  MUTUAL: 'Зв.зам.',
  FINISHED: 'Вичит.',
  BUSINESS_TRIP: 'Відр.',
  OTHER: 'Інше',
};

export const REASON_CODE_HINT: Record<ReasonCode, string> = {
  ABSENT: 'Викладач відсутній',
  MUTUAL: 'Взаємна заміна',
  FINISHED: 'Години дисципліни вичитано',
  BUSINESS_TRIP: 'Відрядження',
  OTHER: 'Інша причина',
};

export const ABSENCE_REASON_LABEL: Record<AbsenceReasonCategory, string> = {
  SICK_LEAVE: 'Лікарняний',
  VACATION: 'Відпустка',
  BUSINESS_TRIP: 'Відрядження',
  PERSONAL: 'Особисті обставини',
  OTHER: 'Інше',
};

export const ROOM_TYPE_LABEL: Record<RoomType, string> = {
  CLASSROOM: 'аудиторія',
  COMPUTER_LAB: 'компʼютерний клас',
  LAB: 'лабораторія',
  GYM: 'спортзал',
  SPORTS_GROUND: 'спортмайданчик',
  OTHER: 'приміщення',
};

const pluralRules = new Intl.PluralRules('uk');

/**
 * Українська множина: plural(3, ['пара', 'пари', 'пар']) → «пари».
 * Форми: [одна, кілька, багато].
 */
export function plural(count: number, forms: readonly [one: string, few: string, many: string]): string {
  const rule = pluralRules.select(count);
  if (rule === 'one') return forms[0];
  if (rule === 'few') return forms[1];
  return forms[2];
}

export function countLabel(count: number, forms: readonly [string, string, string]): string {
  return `${count}\u00a0${plural(count, forms)}`;
}

export const PAIR_FORMS = ['пара', 'пари', 'пар'] as const;
export const MINUTE_FORMS = ['хвилина', 'хвилини', 'хвилин'] as const;

/** «23 хв», «1 год 5 хв» — для зворотного відліку. */
export function durationLabel(totalMinutes: number): string {
  const minutes = Math.max(0, Math.round(totalMinutes));
  if (minutes < 60) return `${minutes}\u00a0хв`;
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  return rest === 0 ? `${hours}\u00a0год` : `${hours}\u00a0год ${rest}\u00a0хв`;
}

/** «2 пара», «1–4 пари» (блок). */
export function pairLabel(from: number, to?: number | null): string {
  if (to != null && to !== from) return `${from}–${to} пари`;
  return `${from} пара`;
}

/** Підгрупа: 0 — уся група. */
export function subgroupLabel(subgroup: number): string | null {
  return subgroup > 0 ? `${subgroup} підгрупа` : null;
}
