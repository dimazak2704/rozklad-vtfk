import type { DayDto, PracticeDto } from '@/api/types';
import { formatTeacherName, type TeacherNameOptions } from '@/lib/teacher-name';
import type { Perspective } from '@/lib/schedule';

/** Практика дня з погляду власника розкладу: група бачить свою практику, викладач — ту, якою керує. */
export function dayPractices(day: Pick<DayDto, 'practices'>, perspective: Perspective): PracticeDto[] {
  return perspective === 'room' ? [] : (day.practices ?? []);
}

/** Для кого практика: «2КІ3, 2КІ4», «4 курс» або порожньо (для всіх). */
export function practiceAudience(practice: Pick<PracticeDto, 'groups' | 'courses'>): string {
  if (practice.groups.length > 0) return practice.groups.map((g) => g.code).join(', ');
  if (practice.courses.length > 0) return `${practice.courses.join(', ')} курс`;
  return '';
}

/** «Керівник: Кренцін М. Д.», «Керівники: …»; null, якщо керівника ще не призначили (публічно тоді лише назва). */
export function supervisorsLine(practice: Pick<PracticeDto, 'supervisors'>, names?: TeacherNameOptions): string | null {
  if (practice.supervisors.length === 0) return null;
  const list = practice.supervisors.map((t) => formatTeacherName(t, 'grid', names)).join(', ');
  return `${practice.supervisors.length === 1 ? 'Керівник' : 'Керівники'}: ${list}`;
}

/** Заголовок блоку: «Практика: назва» для групи, «Керівництво практикою: назва, 2КІ3» для викладача. */
export function practiceTitle(practice: PracticeDto, perspective: Perspective): string {
  if (perspective === 'teacher') {
    const whom = practiceAudience(practice);
    return `Керівництво практикою: ${practice.title}${whom ? `, ${whom}` : ''}`;
  }
  return `Практика: ${practice.title}`;
}

/** Примітка дня без назв практик, які вже показує блок практики (інакше назва стояла б двічі). */
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
