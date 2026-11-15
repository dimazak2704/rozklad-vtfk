/**
 * Примітка періоду для розпорядження — за шаблоном виду періоду, у тому вигляді, як її пишуть у коледжі
 * (розпорядження №35): «2КІ3 – Електрорадіомонтажна практика з 19.10.26р. по 30.10.26 р.»,
 * «Групи 4КІ з 01.09.26р. по 31.10.26 навчаються – дистанційно.».
 *
 * Мова шаблону спільна з importer (CONTRACTS, «Види періодів»): `{хто} {назва} {назва_мала} {вид} {від} {до}
 * {ауд} {режим}`; `[...]` — необовʼязкова частина, яка зникає, якщо хоч одне поле в ній порожнє; невідоме `{x}`
 * лишається як є; результат обрізано з країв.
 */
import type { IsoDate, PeriodScope, StudyMode } from '@/api/types';
import { parseIsoDate } from '@/lib/date';

/** Шаблон виду «група не на парах» (практика, НС); ним же складаємо примітку виду без шаблону. */
export const T_SUSPEND = '{хто} – {назва} з {від}р. по {до} р.[, ауд. {ауд}]';
/** Шаблон вихідних видів (канікули, без занять). */
export const T_DAYOFF = '{хто}: {назва_мала} з {від}р. по {до} р.';
/** Шаблон видів «лише позначка». */
export const T_INFO = '{хто} – {назва} з {від}р. по {до} р.';

/** Поля шаблону з поясненням — підказка в довіднику видів. */
export const NOTE_PLACEHOLDERS: ReadonlyArray<{ token: string; meaning: string }> = [
  { token: '{хто}', meaning: '«4КІ1», «Групи 4КІ1, 4КІ2», «3 курс» чи «Усі групи»' },
  { token: '{назва}', meaning: 'назва періоду' },
  { token: '{назва_мала}', meaning: 'назва з малої літери («занять немає», якщо назви немає)' },
  { token: '{вид}', meaning: 'назва виду' },
  { token: '{від}', meaning: 'перший день, 16.11.26' },
  { token: '{до}', meaning: 'останній день, 27.11.26' },
  { token: '{ауд}', meaning: 'аудиторія періоду' },
  { token: '{режим}', meaning: '«навчаються – дистанційно» тощо' },
];

/** «19.10.26» — дата з двома цифрами року, як у розпорядженнях. */
export function orderDate(iso: IsoDate): string {
  const { year, month, day } = parseIsoDate(iso);
  return `${String(day).padStart(2, '0')}.${String(month).padStart(2, '0')}.${String(year % 100).padStart(2, '0')}`;
}

export interface NoteInput {
  /** Шаблон виду; null — `T_SUSPEND` (так «Скласти» працює й для виду без шаблону). */
  template: string | null;
  /** Назва виду для `{вид}`. */
  kindName: string;
  title: string;
  startsOn: IsoDate;
  endsOn: IsoDate;
  scope: PeriodScope;
  courses: readonly number[];
  /** Коди груп у порядку вибору. */
  groupCodes: readonly string[];
  studyMode: StudyMode | null;
  /** Назва аудиторії періоду або null. */
  roomName: string | null;
}

/** Хто: «2КІ3», «Групи 4КІ1, 4КІ2», «3 курс», «1, 2 курси», «Усі групи». */
function audience(input: NoteInput): string {
  if (input.scope === 'GROUPS') {
    if (input.groupCodes.length <= 1) return input.groupCodes[0] ?? '';
    return `Групи ${input.groupCodes.join(', ')}`;
  }
  if (input.scope === 'COURSES') {
    if (input.courses.length === 0) return '';
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

function lowerFirst(text: string): string {
  return text ? `${text.charAt(0).toLocaleLowerCase('uk')}${text.slice(1)}` : text;
}

/** Значення полів шаблону; порожній рядок — поля немає. */
function placeholderValues(input: NoteInput): Record<string, string> {
  const title = input.title.trim();
  return {
    хто: audience(input),
    назва: title,
    назва_мала: title ? lowerFirst(title) : 'занять немає',
    вид: input.kindName.trim(),
    від: input.startsOn ? orderDate(input.startsOn) : '',
    до: input.endsOn ? orderDate(input.endsOn) : '',
    ауд: input.roomName?.trim() ?? '',
    режим: input.studyMode ? MODE_TEXT[input.studyMode] : '',
  };
}

const PLACEHOLDER = /\{([^{}]+)\}/g;

interface Rendered {
  text: string;
  /** Обовʼязкове (поза `[...]`) поле шаблону вийшло порожнім. */
  incomplete: boolean;
}

function fill(part: string, values: Record<string, string>): { text: string; empty: boolean } {
  let empty = false;
  const text = part.replace(PLACEHOLDER, (whole: string, name: string) => {
    if (!(name in values)) return whole;
    const value = values[name] ?? '';
    if (!value) empty = true;
    return value;
  });
  return { text, empty };
}

/** Шаблон → текст: поля підставлено, `[...]` без порожніх полів лишається (без дужок), з порожніми — зникає. */
export function renderNoteTemplate(template: string, input: NoteInput): Rendered {
  const values = placeholderValues(input);
  let text = '';
  let incomplete = false;
  let rest = template;
  while (rest.length > 0) {
    const open = rest.indexOf('[');
    const close = open >= 0 ? rest.indexOf(']', open + 1) : -1;
    const plainEnd = open >= 0 && close > open ? open : rest.length;
    const plain = fill(rest.slice(0, plainEnd), values);
    text += plain.text;
    incomplete ||= plain.empty;
    if (plainEnd === rest.length) break;
    const optional = fill(rest.slice(open + 1, close), values);
    if (!optional.empty) text += optional.text;
    rest = rest.slice(close + 1);
  }
  return { text: text.trim(), incomplete };
}

/**
 * Чернетка примітки з полів періоду за шаблоном виду. Порожній рядок, якщо даних не досить (немає дат, груп,
 * назви чи режиму, які шаблон вимагає): тоді кнопка «Скласти» неактивна, а не підставляє неповний текст.
 */
export function composeOrderNote(input: NoteInput): string {
  if (!input.startsOn || !input.endsOn) return '';
  const rendered = renderNoteTemplate(input.template ?? T_SUSPEND, input);
  return rendered.incomplete ? '' : rendered.text;
}
