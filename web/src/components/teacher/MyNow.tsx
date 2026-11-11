import { useMemo } from 'react';
import type { BellSlotDto, DayDto, IsoDate } from '@/api/types';
import { activePairs } from '@/components/public/schedule/schedule-utils';
import { NowIndicator } from '@/components/schedule';
import { useNow } from '@/hooks/useNow';
import { formatWeekdayDate } from '@/lib/date';
import { lessonPhrase, nextDaySentence, ownLessons } from './my-day';

interface MyNowProps {
  teacherId: string;
  today: IsoDate;
  /** Дні поточного тижня викладача (з замінами). */
  days: readonly DayDto[];
  bells: readonly BellSlotDto[];
}

/**
 * «Зараз» для викладача: відлік до дзвінка, доріжка його власних пар і одне речення про те, що він веде
 * зараз або далі. Єдине, що рухається на сторінці.
 */
export function MyNow({ teacherId, today, days, bells }: MyNowProps) {
  const day = days.find((d) => d.date === today);
  const lessons = useMemo(() => ownLessons(day, teacherId), [day, teacherId]);
  const pairs = useMemo(() => activePairs(day ? { ...day, lessons } : undefined), [day, lessons]);
  const onlyPairs = useMemo(() => new Set(pairs), [pairs]);
  const { status } = useNow(bells, { date: today, onlyPairs });

  // що саме веду зараз або веду наступним: без цього відлік не каже, куди йти
  const focusPair = status?.kind === 'in-pair' ? status.current.pair : status?.kind === 'break' || status?.kind === 'before-day' ? status.next.pair : null;
  const focus = focusPair === null ? [] : lessons.filter((lesson) => lesson.pair === focusPair);
  const aside =
    focus.length > 0 ? (
      <>
        {status?.kind === 'in-pair' ? 'Зараз у вас ' : status?.kind === 'break' ? 'Далі у вас ' : 'У вас '}
        <span className="font-[650] text-primary">{focus.map(lessonPhrase).join('; ')}</span>
      </>
    ) : null;

  return (
    <NowIndicator
      variant="hero"
      bells={bells}
      lessonPairs={pairs}
      date={today}
      dateLine={formatWeekdayDate(today)}
      aside={aside}
      afterDay={nextDaySentence(days, today, teacherId) ?? undefined}
    />
  );
}
