/** Тексти для карток запитів на заміну: що саме треба провести і замість кого. */
import type { ChangeDto } from '@/api/types';
import { formatWeekdayDate, relativeDayLabel, todayInKyiv } from '@/lib/date';
import { REASON_CODE_LABEL } from '@/lib/labels';
import { roomLabel } from '@/lib/schedule';

/** Яку дисципліну проводить новий викладач: нову (взаємна заміна) або ту саму. */
export function changeDiscipline(change: ChangeDto): string {
  return (change.new.discipline ?? change.original.discipline)?.shortName ?? 'Пара';
}

export function changeDisciplineName(change: ChangeDto): string | null {
  const discipline = change.new.discipline ?? change.original.discipline;
  return discipline && discipline.name !== discipline.shortName ? discipline.name : null;
}

export function changeRoom(change: ChangeDto): string | null {
  return roomLabel((change.new.room ?? change.original.room)?.name);
}

/** «Відп.» або власний текст причини. */
export function changeReason(change: ChangeDto): string | null {
  return change.reasonCode === 'OTHER' ? change.reasonText : REASON_CODE_LABEL[change.reasonCode];
}

/** «Завтра, 10 листопада» або «Середа, 11 листопада». */
export function changeDay(change: ChangeDto, today: string = todayInKyiv()): string {
  const relative = relativeDayLabel(change.date, today);
  const full = formatWeekdayDate(change.date);
  return relative ? `${relative}, ${full.split(', ')[1] ?? full}` : full;
}

/** Підпис для aria/тосту: «4КІ2, завтра, 2 пара». */
export function changeTitle(change: ChangeDto, today?: string): string {
  return `${change.group.code}, ${changeDay(change, today).toLocaleLowerCase('uk')}, ${change.pairFrom} пара`;
}
