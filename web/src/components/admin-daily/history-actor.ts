/** Хто діяв в історії рішення — словами: імʼя викладача, «Диспетчер» чи службовий запис замість логіна. */
import type { ChangeDto, ChangeHistoryEntry, TeacherRef } from '@/api/types';
import type { TeacherLabel } from './teacher-label';

/** Примітки, які core пише сам, коли викладач нічого не додав: це не його слова, тож без лапок. */
export const SYSTEM_COMMENTS = new Set(['Підтверджено викладачем', 'Викладач відмовився']);

/** Службові облікові записи, від яких бувають записи історії (бот повідомляє про відсутність тощо). */
const SERVICE_ACTORS: Record<string, string> = { 'telegram-bot': 'Telegram-бот', system: 'Система' };

/** Відповідь викладача, записана його логіном (кабінет): відмова чи відповідь на повідомлення без зміни стану. */
function isTeacherAnswer(entry: ChangeHistoryEntry): boolean {
  if (entry.to === 'DECLINED') return true;
  if (entry.from === 'PENDING_CONFIRMATION' && entry.to === 'CONFIRMED') return entry.comment !== 'Підтверджено диспетчером';
  return entry.from === entry.to && (entry.to === 'CONFIRMED' || entry.to === 'APPLIED');
}

/**
 * Хто діяв — словами, а не логіном: core пише відповідь з Telegram як «Кренцін М.Д. (Telegram)», а з кабінету й
 * дії диспетчера — логіном облікового запису. Викладача зміни показуємо його іменем (і його слова — в лапках),
 * службові записи — назвою, решту логінів — «Диспетчер».
 */
export function historyActor(
  entry: ChangeHistoryEntry,
  change: Pick<ChangeDto, 'new' | 'original'>,
  name: TeacherLabel,
): { label: string; teacher: boolean } {
  const teachers = [change.new.teacher, change.original.teacher].filter((t): t is TeacherRef => Boolean(t));
  for (const teacher of teachers) {
    if (entry.actor === teacher.shortName) return { label: name(teacher), teacher: true };
    if (entry.actor.startsWith(`${teacher.shortName} (`)) return { label: `${name(teacher)} ${entry.actor.slice(teacher.shortName.length + 1)}`, teacher: true };
  }
  const service = SERVICE_ACTORS[entry.actor];
  if (service) return { label: service, teacher: false };
  // логін без пробілів: відповідь викладача з кабінету — його ім'ям, решта — дії диспетчера
  if (!/\s/.test(entry.actor)) {
    if (change.new.teacher && isTeacherAnswer(entry)) return { label: name(change.new.teacher), teacher: true };
    return { label: 'Диспетчер', teacher: false };
  }
  return { label: entry.actor, teacher: false };
}

