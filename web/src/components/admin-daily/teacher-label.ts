import { formatTeacherName, type TeacherNameSource } from '@/lib/teacher-name';

/**
 * Як панель диспетчера пише викладача: одне правило з `lib/teacher-name` — «Прізвище І. Б.» (`grid`),
 * ініціали з каталогу, коли коротка форма їх не має («Непийвода» → «Непийвода М. В.»).
 * Чисті функції моделі приймають цю функцію параметром; компоненти беруть її з `useTeacherLabel()`.
 */
export type TeacherLabel = (teacher: TeacherNameSource & { id?: string }) => string;

/** Без довідника імен: ініціали лише з короткої форми («Стахова О.А.» → «Стахова О. А.»). */
export const gridTeacherName: TeacherLabel = (teacher) => formatTeacherName(teacher, 'grid');

/**
 * Текст сервера з короткою формою викладача («КМ — Колесник А.») — тим самим підписом, що й усюди.
 * Якщо короткої форми в тексті немає, текст лишається як є.
 */
export function withTeacherLabel(text: string, teacher: (TeacherNameSource & { id?: string }) | null | undefined, label: TeacherLabel): string {
  if (!teacher?.shortName) return text;
  const at = text.lastIndexOf(teacher.shortName);
  if (at < 0) return text;
  return `${text.slice(0, at)}${label(teacher)}${text.slice(at + teacher.shortName.length)}`;
}

const escapeRegExp = (text: string): string => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/**
 * Довільний текст сервера (причини й застереження варіанта: «Харченко О.А. уже веде Ін.мова») — кожну
 * згадку відомих викладачів тим самим підписом, що й назва варіанта. Коротка форма має стояти окремим
 * словом: «Крисак Л.» не чіпаємо всередині «Крисак Л.В.». Довші форми мають перевагу.
 */
export function withTeacherLabels(
  text: string,
  teachers: ReadonlyArray<(TeacherNameSource & { id?: string }) | null | undefined>,
  label: TeacherLabel,
): string {
  const byShort = new Map<string, TeacherNameSource & { id?: string }>();
  for (const teacher of teachers) if (teacher?.shortName.trim()) byShort.set(teacher.shortName, teacher);
  if (byShort.size === 0) return text;
  const forms = [...byShort.keys()].sort((a, b) => b.length - a.length).map(escapeRegExp);
  const pattern = new RegExp(`(?<!\\p{L})(?:${forms.join('|')})(?![\\p{L}.])`, 'gu');
  return text.replace(pattern, (found) => {
    const teacher = byShort.get(found);
    return teacher ? label(teacher) : found;
  });
}

