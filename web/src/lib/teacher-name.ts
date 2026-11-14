/**
 * Як писати імʼя викладача. Одне правило на весь сайт:
 * - `grid` — клітинка розкладу, де місця мало: прізвище («Кренцін»); якщо в тому самому розкладі є
 *   однофамільці — коротке імʼя з ініціалами («Овчар Ін.М.»), щоб їх розрізнити;
 * - `full` — картка заняття, заголовок сторінки викладача, списки й форми: «Прізвище Імʼя», коли імʼя
 *   відоме, інакше прізвище з ініціалами через пробіл («Кренцін М. Д.»). По батькові тут не потрібне;
 * - `formal` — документи (розпорядження, друк): коротке імʼя як у паперах коледжу («Кренцін М.Д.»).
 */

export type TeacherNameStyle = 'grid' | 'full' | 'formal';

export interface TeacherNameSource {
  shortName: string;
  /** Підпис для клітинок з каталогу (зазвичай прізвище). */
  displayName?: string | null;
  lastName?: string | null;
  /** Імʼя — лише там, де сервер його віддає (адмінка, публічні сторінки з налаштуванням повних імен). */
  firstName?: string | null;
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

/** Прізвище викладача: з каталогу, а якщо його немає — з короткої форми. */
export function teacherSurname(teacher: TeacherNameSource): string {
  return teacher.lastName?.trim() || splitShortName(teacher.shortName).surname;
}

/** Підпис клітинки сітки без урахування однофамільців. */
function gridLabel(teacher: TeacherNameSource): string {
  return teacher.displayName?.trim() || teacherSurname(teacher);
}

export function formatTeacherName(
  teacher: TeacherNameSource,
  style: TeacherNameStyle,
  options: { ambiguous?: ReadonlySet<string> } = {},
): string {
  switch (style) {
    case 'formal':
      return teacher.shortName;
    case 'grid': {
      const label = gridLabel(teacher);
      return options.ambiguous?.has(label) ? teacher.shortName : label;
    }
    case 'full': {
      const first = teacher.firstName?.trim();
      if (first) return `${teacherSurname(teacher)}${NBSP}${first}`;
      const { surname, initials } = splitShortName(teacher.shortName);
      return initials.length > 0 ? `${surname}${NBSP}${initials.join(NBSP)}` : surname;
    }
  }
}

/**
 * Підписи сітки, які в цьому розкладі носять різні викладачі (однофамільці): для них `grid` пише ініціали.
 * Передайте всіх викладачів, видимих на екрані (повтори й null — можна).
 */
export function ambiguousGridNames(
  teachers: Iterable<(TeacherNameSource & { id: string }) | null | undefined>,
): ReadonlySet<string> {
  const owners = new Map<string, string>();
  const ambiguous = new Set<string>();
  for (const teacher of teachers) {
    if (!teacher) continue;
    const label = gridLabel(teacher);
    const owner = owners.get(label);
    if (owner === undefined) owners.set(label, teacher.id);
    else if (owner !== teacher.id) ambiguous.add(label);
  }
  return ambiguous;
}
