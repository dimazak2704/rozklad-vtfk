import type { DayDto, LessonView } from '@/api/types';
import { formatShortWeekdayDate } from '@/lib/date';

/** Моя пара в один з обраних днів. */
export type OwnLesson = { day: DayDto; lesson: LessonView };


/** Одна пара за номером у межах обраних днів: час і що саме я веду (у кожен із днів). */
export interface PairChoice {
  pair: number;
  startsAt: string;
  lessons: readonly OwnLesson[];
}

/** Мої пари в обрані дні, згруповані за номером: відсутність у core задає номери пар для кожного дня діапазону. */
export function pairChoices(own: readonly OwnLesson[]): PairChoice[] {
  const byPair = new Map<number, OwnLesson[]>();
  for (const item of own) byPair.set(item.lesson.pair, [...(byPair.get(item.lesson.pair) ?? []), item]);
  return [...byPair.entries()]
    .sort(([a], [b]) => a - b)
    .map(([pair, lessons]) => ({ pair, startsAt: lessons[0]?.lesson.startsAt ?? '', lessons }));
}

/** «4КІ2, СТП» для одного дня; «Ср, 11.11 — 4КІ2, СТП; Чт, 12.11 — 4КІ1, СТП» для кількох. */
export function choiceText(choice: PairChoice, oneDay: boolean): string {
  const what = ({ lesson }: OwnLesson) => `${lesson.group.code}, ${lesson.discipline.shortName}`;
  if (oneDay) return choice.lessons.map(what).join('; ');
  return choice.lessons.map((item) => `${formatShortWeekdayDate(item.day.date)} — ${what(item)}`).join('; ');
}
