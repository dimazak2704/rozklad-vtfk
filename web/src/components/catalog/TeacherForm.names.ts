/**
 * Як «Як у розкладі» і «Публічне імʼя» йдуть за ПІБ у формі викладача. Без цього адмін виправляв
 * прізвище, а в поля лишалось старе: сервер отримував їх як є, і розпорядження друкували старе прізвище.
 */
import { teacherSurname } from '@/lib/teacher-name';
import { shortNameOf } from './names';

export interface TeacherNameParts {
  lastName: string;
  firstName: string;
  middleName: string;
}

/**
 * - `derived` — значення складене з ПІБ («Кренцін М.Д.»): складаємо заново з нового ПІБ;
 * - `surname` — значення починається з прізвища («Кренцін», «Кренцін М.»): міняємо лише прізвище;
 * - `null` — написано інакше: не чіпаємо.
 */
export type NameFollow = 'derived' | 'surname' | null;

const clean = (value: string): string => value.trim().replace(/\s+/g, ' ');
const blank = (value: string): string | null => clean(value) || null;

function derivedOf(parts: TeacherNameParts): string {
  return shortNameOf(clean(parts.lastName), blank(parts.firstName), blank(parts.middleName));
}

/** Решта значення після прізвища, якщо значення з нього починається («Кренцін М.Д.» → « М.Д.», «ХарченкоГ.» → «Г.»). */
function afterSurname(value: string, lastName: string): string | null {
  const surname = clean(lastName);
  const text = clean(value);
  if (!surname || !text.startsWith(surname)) return null;
  const rest = text.slice(surname.length);
  return rest === '' || /^[\s\p{Lu}]/u.test(rest) ? rest : null;
}

/** Чи йде коротке імʼя за ПІБ. Порожнє поле й так складе сервер — йому слідувати не треба. */
export function shortNameFollow(shortName: string, parts: TeacherNameParts): NameFollow {
  if (!clean(shortName) || !clean(parts.lastName)) return null;
  if (clean(shortName) === derivedOf(parts)) return 'derived';
  return afterSurname(shortName, parts.lastName) === null ? null : 'surname';
}

/** Чи йде публічне імʼя за прізвищем («Кренцін», «Поліщук Л.»). */
export function displayNameFollow(displayName: string, lastName: string): NameFollow {
  if (!clean(displayName)) return null;
  return afterSurname(displayName, lastName) === null ? null : 'surname';
}

/**
 * Нове значення поля після правки ПІБ `before` → `after`. Без прізвища (його саме стерли) або коли
 * значення вже не починається зі старого прізвища — лишаємо як є.
 */
export function followName(value: string, follow: NameFollow, before: TeacherNameParts, after: TeacherNameParts): string {
  if (follow === null || !clean(after.lastName)) return value;
  if (follow === 'derived') return derivedOf(after);
  const rest = afterSurname(value, before.lastName);
  return rest === null ? value : `${clean(after.lastName)}${rest}`;
}

/**
 * Прізвище в короткому імені не таке, як у полі «Прізвище» (регістр не важить). Порожнє поле
 * чи порожнє прізвище — не попереджаємо.
 */
export function surnameMismatch(shortName: string, lastName: string): boolean {
  const surname = clean(lastName).toLocaleLowerCase('uk');
  if (!clean(shortName) || !surname) return false;
  return teacherSurname({ shortName: clean(shortName) }).toLocaleLowerCase('uk') !== surname;
}
