import type { PublicGroupDto } from '@/api/types';

/**
 * Підзаголовок групи з одного джерела — спеціальності: «Компʼютерна інженерія · 4 курс». Невідому назву
 * (вона збігається з літерами коду) не показуємо й відділенням не підмінюємо, щоб поруч не стояли
 * «Компʼютерна інженерія» і «Відділення електроенергетики…»: тоді лише «2 курс». Так само — у заголовку
 * сторінки групи й у підказках до кодів.
 */
export function groupHintText(group: Pick<PublicGroupDto, 'course' | 'specialty'>): string {
  return [specialtyName(group), `${group.course} курс`].filter(Boolean).join(' · ');
}

/**
 * Підказка до коду групи при наведенні. Лише «2 курс» нічого не додає до коду «2ЕА2» (курс — його перша
 * цифра), тож без відомої назви спеціальності підказки немає зовсім.
 */
export function groupTooltipText(group: Pick<PublicGroupDto, 'course' | 'specialty'>): string | null {
  return specialtyName(group) ? groupHintText(group) : null;
}

function specialtyName(group: Pick<PublicGroupDto, 'specialty'>): string | null {
  return group.specialty.name !== group.specialty.letters ? group.specialty.name : null;
}

