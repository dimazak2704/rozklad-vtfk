/**
 * Примітка періоду для розпорядження — у тому вигляді, як її пишуть у коледжі (розпорядження №35):
 * «2КІ3 – Електрорадіомонтажна практика з 19.10.26р. по 30.10.26 р.»,
 * «Групи 4КІ з 01.09.26р. по 31.10.26 навчаються – дистанційно.».
 */
import type { CalendarPeriodKind, IsoDate, PeriodScope, StudyMode } from '@/api/types';
import { parseIsoDate } from '@/lib/date';

/** «19.10.26» — дата з двома цифрами року, як у розпорядженнях. */
export function orderDate(iso: IsoDate): string {
  const { year, month, day } = parseIsoDate(iso);
  return `${String(day).padStart(2, '0')}.${String(month).padStart(2, '0')}.${String(year % 100).padStart(2, '0')}`;
}

export interface NoteInput {
  kind: CalendarPeriodKind;
  title: string;
  startsOn: IsoDate;
  endsOn: IsoDate;
  scope: PeriodScope;
  courses: readonly number[];
  /** Коди груп у порядку вибору. */
  groupCodes: readonly string[];
  studyMode: StudyMode | null;
}

/** Хто: «2КІ3», «Групи 4КІ1, 4КІ2», «3 курс», «Усі групи». */
function audience(input: NoteInput): string {
  if (input.scope === 'GROUPS') {
    if (input.groupCodes.length === 1) return input.groupCodes[0] ?? '';
    return `Групи ${input.groupCodes.join(', ')}`;
  }
  if (input.scope === 'COURSES') {
    const sorted = [...input.courses].sort((a, b) => a - b);
    return sorted.length === 1 ? `${sorted[0]} курс` : `${sorted.join(', ')} курси`;
  }
  return 'Усі групи';
}

const MODE_TEXT: Record<StudyMode, string> = {
  ONLINE: 'навчаються – дистанційно',
  MIXED: 'навчаються – у змішаному режимі',
  OFFLINE: 'навчаються очно',
};

/**
 * Чернетка примітки з полів періоду. Порожній рядок, якщо даних не досить (немає дат чи груп):
 * тоді кнопка «Скласти» неактивна, а не підставляє неповний текст.
 */
export function composeOrderNote(input: NoteInput): string {
  if (!input.startsOn || !input.endsOn) return '';
  if (input.scope === 'GROUPS' && input.groupCodes.length === 0) return '';
  if (input.scope === 'COURSES' && input.courses.length === 0) return '';
  const who = audience(input);
  const range = `з ${orderDate(input.startsOn)}р. по ${orderDate(input.endsOn)} р.`;
  const title = input.title.trim();
  switch (input.kind) {
    case 'STUDY_MODE':
      return input.studyMode ? `${who} ${range.replace(/ р\.$/, '')} ${MODE_TEXT[input.studyMode]}.` : '';
    case 'PRACTICE':
    case 'EXAM_SESSION':
      return title ? `${who} – ${title} ${range}` : '';
    case 'HOLIDAY':
    case 'NO_CLASSES':
      return `${who}: ${title ? `${title.charAt(0).toLocaleLowerCase('uk')}${title.slice(1)}` : 'занять немає'} ${range}`;
  }
}
