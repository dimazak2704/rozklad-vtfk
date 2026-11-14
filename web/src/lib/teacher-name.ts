/**
 * Як писати імʼя викладача. Одне правило на весь сайт:
 * - `grid` — клітинка розкладу, списки замін: прізвище з ініціалами («Кренцін М. Д.»). Ініціали —
 *   з короткої форми коледжу (вона розрізняє однофамільців: «Овчар Ін. М.»), а якщо в ній їх немає —
 *   з імені й по батькові, коли вони відомі. Якщо на екрані все ж двоє з тим самим підписом —
 *   для них пишемо імʼя повністю («Коваль Олена П.»), коли воно відоме;
 * - `full` — картка заняття, заголовок сторінки викладача, списки й форми: повне імʼя
 *   «Прізвище Імʼя По батькові», коли воно відоме (публічно — лише з налаштуванням `showTeacherFullNames`),
 *   інакше те саме, що в сітці («Кренцін М. Д.»);
 * - `formal` — документи (розпорядження, друк): коротке імʼя як у паперах коледжу («Кренцін М.Д.»).
 */

export type TeacherNameStyle = 'grid' | 'full' | 'formal';

export interface TeacherNameSource {
  shortName: string;
  /** Підпис для клітинок з каталогу (зазвичай прізвище). Сітка його не бере: там завжди прізвище з ініціалами. */
  displayName?: string | null;
  lastName?: string | null;
  /** Імʼя — лише там, де сервер його віддає (адмінка, публічні сторінки з налаштуванням повних імен). */
  firstName?: string | null;
  middleName?: string | null;
  /** Готове «Прізвище Імʼя По батькові» (GET /api/public/teachers) — лише з дозволу налаштувань. */
  fullName?: string | null;
}

const NBSP = ' ';

/**
 * Прізвище та ініціали короткої форми, як `TeacherNames.lastNameOf` у core: «Кренцін М.Д.», «Овчар Ін.М.»,
 * «Крисак Л.», «ХарченкоГ.» (без пробілу). Без ініціалів — уся форма є прізвищем.
 */
const SHORT_FORM = /^(.+?)\s*((?:\p{Lu}\p{Ll}?\.\s*){1,2})$/u;

function splitShortName(shortName: string): { surname: string; initials: string[] } {
  const cleaned = shortName.trim().replace(/\s+/g, ' ');
  const match = SHORT_FORM.exec(cleaned);
  if (!match) return { surname: cleaned, initials: [] };
  const initials = (match[2] ?? '').match(/\p{Lu}\p{Ll}?\./gu) ?? [];
  return { surname: (match[1] ?? cleaned).trim(), initials };
}

const clean = (value: string | null | undefined): string => value?.trim().replace(/\s+/g, ' ') ?? '';

const initialOf = (name: string): string => `${name.charAt(0).toLocaleUpperCase('uk')}.`;

/** Прізвище викладача: з каталогу, а якщо його немає — з короткої форми. */
export function teacherSurname(teacher: TeacherNameSource): string {
  return clean(teacher.lastName) || splitShortName(teacher.shortName).surname;
}

/**
 * Ініціали: з імені й по батькові, коли вони дають більше, ніж коротка форма коледжу («Колесник А.» →
 * «Колесник А. І.»), інакше з короткої форми. Двобуквені ініціали короткої форми («Овчар Ін.М.») розрізняють
 * однофамільців — їх лишаємо завжди.
 */
function teacherInitials(teacher: TeacherNameSource): string[] {
  const fromShort = splitShortName(teacher.shortName).initials;
  if (fromShort.some((initial) => initial.length > 2)) return fromShort;
  const fromNames = [clean(teacher.firstName), clean(teacher.middleName)].filter(Boolean).map(initialOf);
  return fromNames.length > fromShort.length ? fromNames : fromShort;
}

/** «Кренцін М. Д.»: ініціали не розриваються, рядок може перенестися лише після прізвища. */
function initialsLabel(teacher: TeacherNameSource): string {
  const surname = teacherSurname(teacher);
  const initials = teacherInitials(teacher);
  return initials.length > 0 ? `${surname} ${initials.join(NBSP)}` : surname;
}

/** «Прізвище Імʼя По батькові» або null, коли імені не знаємо. */
function fullNameOf(teacher: TeacherNameSource): string | null {
  const ready = clean(teacher.fullName);
  if (ready) return ready;
  const first = clean(teacher.firstName);
  if (!first) return null;
  return [teacherSurname(teacher), first, clean(teacher.middleName)].filter(Boolean).join(' ');
}

/** Імена з довідника за id: посилання на викладача в розкладі (TeacherRef) несе лише коротку форму. */
export type TeacherNameParts = Pick<TeacherNameSource, 'lastName' | 'firstName' | 'middleName' | 'fullName'>;
export type TeacherNameDirectory = ReadonlyMap<string, TeacherNameParts>;

export interface TeacherNameOptions {
  /** Підписи сітки, які на цьому екрані носять різні викладачі (`ambiguousGridNames`). */
  ambiguous?: ReadonlySet<string>;
  /** Довідник імен: публічний (з налаштуванням повних імен) або каталог адмінки. */
  names?: TeacherNameDirectory;
}

/**
 * Частини імені з публічного довідника: `firstName` і готове `fullName` («Прізвище Імʼя По батькові»).
 * По батькові — те, що в `fullName` стоїть після імені; прізвище — те, що перед ним.
 */
export function namePartsFromFull(fullName: string | null | undefined, firstName: string | null | undefined): TeacherNameParts {
  const full = clean(fullName);
  const first = clean(firstName);
  if (!full || !first) return { fullName: full || null, firstName: first || null };
  const words = full.split(' ');
  const at = words.indexOf(first, 1);
  if (at < 1) return { fullName: full, firstName: first };
  return { fullName: full, firstName: first, lastName: words.slice(0, at).join(' '), middleName: words.slice(at + 1).join(' ') || null };
}

/** Викладач, доповнений іменами з довідника (власні поля DTO мають перевагу). */
function withDirectory(teacher: TeacherNameSource & { id?: string }, names: TeacherNameDirectory | undefined): TeacherNameSource {
  const known = teacher.id ? names?.get(teacher.id) : undefined;
  if (!known) return teacher;
  return {
    ...teacher,
    lastName: clean(teacher.lastName) || known.lastName,
    firstName: clean(teacher.firstName) || known.firstName,
    middleName: clean(teacher.middleName) || known.middleName,
    fullName: clean(teacher.fullName) || known.fullName,
  };
}

export function formatTeacherName(
  source: TeacherNameSource & { id?: string },
  style: TeacherNameStyle,
  options: TeacherNameOptions = {},
): string {
  const teacher = withDirectory(source, options.names);
  switch (style) {
    case 'formal':
      return teacher.shortName;
    case 'grid': {
      const label = initialsLabel(teacher);
      const first = clean(teacher.firstName);
      if (!options.ambiguous?.has(label) || !first) return label;
      const middle = clean(teacher.middleName);
      return `${teacherSurname(teacher)} ${first}${middle ? ` ${initialOf(middle)}` : ''}`;
    }
    case 'full':
      return fullNameOf(teacher) ?? initialsLabel(teacher);
  }
}

/**
 * Підписи сітки, які в цьому розкладі носять різні викладачі: для них `grid` пише імʼя повністю, якщо воно відоме.
 * Передайте всіх викладачів, видимих на екрані (повтори й null — можна).
 */
export function ambiguousGridNames(
  teachers: Iterable<(TeacherNameSource & { id: string }) | null | undefined>,
  names?: TeacherNameDirectory,
): ReadonlySet<string> {
  const owners = new Map<string, string>();
  const ambiguous = new Set<string>();
  for (const teacher of teachers) {
    if (!teacher) continue;
    const label = initialsLabel(withDirectory(teacher, names));
    const owner = owners.get(label);
    if (owner === undefined) owners.set(label, teacher.id);
    else if (owner !== teacher.id) ambiguous.add(label);
  }
  return ambiguous;
}
