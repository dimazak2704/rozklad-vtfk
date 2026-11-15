/**
 * Блоки дня — періоди, коли група не на парах (практика, НС, збори): `DayDto.practices` (назва поля історична).
 * Група бачить «{Вид}: назва, ауд. 401» з керівниками, керівник — «Керівництво: {Вид}: назва, група». Старіший
 * core не дає виду — тоді це практика, тексти як раніше («Практика: …», «Керівництво практикою: …»).
 */
import type { DayDto, PracticeDto } from '@/api/types';
import { formatTeacherName, type TeacherNameOptions } from '@/lib/teacher-name';
import type { Perspective } from '@/lib/schedule';

/** Назва виду, коли core її не дав (старіший core знав лише практику). */
const PRACTICE = 'Практика';

/** Блоки дня з погляду власника розкладу: група бачить свої, викладач — ті, якими керує; аудиторія — жодного. */
export function dayPractices(day: Pick<DayDto, 'practices'>, perspective: Perspective): PracticeDto[] {
  return perspective === 'room' ? [] : (day.practices ?? []);
}

/** Назва виду блоку: «Національний спротив», «Практика». */
export function blockKindName(practice: Pick<PracticeDto, 'kind'>): string {
  return practice.kind?.name?.trim() || PRACTICE;
}

/** Для кого блок: «2КІ3, 2КІ4», «4 курс», «1, 2 курси» або порожньо (для всіх). */
export function practiceAudience(practice: Pick<PracticeDto, 'groups' | 'courses'>): string {
  if (practice.groups.length > 0) return practice.groups.map((g) => g.code).join(', ');
  if (practice.courses.length > 0) return `${practice.courses.join(', ')} ${practice.courses.length === 1 ? 'курс' : 'курси'}`;
  return '';
}

/** Назва періоду лише повторює назву виду («Сесія» виду «Сесія»): тоді вид пишемо один раз. */
function repeatsKind(title: string, kind: string): boolean {
  return title.trim().toLocaleLowerCase('uk') === kind.toLocaleLowerCase('uk');
}

/** «Керівник: Кренцін М. Д.», «Керівники: …»; null, якщо керівника ще не призначили (публічно тоді лише назва). */
export function supervisorsLine(practice: Pick<PracticeDto, 'supervisors'>, names?: TeacherNameOptions): string | null {
  if (practice.supervisors.length === 0) return null;
  const list = practice.supervisors.map((t) => formatTeacherName(t, 'grid', names)).join(', ');
  return `${practice.supervisors.length === 1 ? 'Керівник' : 'Керівники'}: ${list}`;
}

/**
 * Заголовок блоку: група — «Національний спротив: Основи національного спротиву, ауд. 401»; викладач —
 * «Керівництво: Національний спротив: назва, 4КІ1, ауд. 401», а для практики, як і раніше,
 * «Керівництво практикою: назва, 2КІ3». Назву, що лише повторює вид (з графіка: «Сесія», «Збори»), не дублюємо:
 * «Сесія», «Керівництво: Сесія, 4КІ1».
 */
export function blockTitle(practice: PracticeDto, perspective: Perspective): string {
  const kind = blockKindName(practice);
  const room = practice.room ? `, ауд. ${practice.room.name}` : '';
  const what = repeatsKind(practice.title, kind) ? kind : `${kind}: ${practice.title}`;
  if (perspective === 'teacher') {
    const whom = practiceAudience(practice);
    const lead = kind === PRACTICE ? `Керівництво практикою: ${practice.title}` : `Керівництво: ${what}`;
    return `${lead}${whom ? `, ${whom}` : ''}${room}`;
  }
  return `${what}${room}`;
}

/**
 * Підпис групи блоків (рядок тижня керівника, список для читача екрана): «Практика», якщо всі блоки — практика;
 * для керівника інакше «Керівництво» (вузька колонка пар не вміщує довгих назв видів); для групи — назва виду,
 * якщо вид один, інакше «Без пар».
 */
export function blocksLabel(practices: readonly PracticeDto[], perspective: Perspective): string {
  const kinds = new Set(practices.map(blockKindName));
  const only = kinds.size === 1 ? [...kinds][0] : undefined;
  if (only === PRACTICE) return PRACTICE;
  if (perspective === 'teacher') return 'Керівництво';
  return only ?? 'Без пар';
}

/** Примітка дня без назв блоків, які вже показано (інакше назва стояла б двічі). */
export function noteWithoutPractices(day: Pick<DayDto, 'note' | 'practices'>, practices: readonly PracticeDto[]): string | null {
  if (!day.note) return null;
  if (practices.length === 0) return day.note;
  const shown = new Set(practices.map((p) => p.title.trim()));
  const rest = day.note
    .split('; ')
    .filter((part) => !shown.has(part.trim()))
    .join('; ');
  return rest.trim() ? rest : null;
}
