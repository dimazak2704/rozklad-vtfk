import { useMemo } from 'react';
import { useMySchedule } from '@/api/me';
import type { BellSlotDto, DayDto, IsoDate } from '@/api/types';
import { studyWeek } from '@/components/public/schedule/schedule-params';
import { activePairs } from '@/components/public/schedule/schedule-utils';
import { NowIndicator } from '@/components/schedule';
import { useNow } from '@/hooks/useNow';
import { addDays, formatWeekdayDate, isWeekend } from '@/lib/date';
import { lessonPhrase, nextDaySentence, ownLessons } from './my-day';

interface MyNowProps {
  teacherId: string;
  today: IsoDate;
  /** Дні поточного тижня викладача (з замінами). */
  days: readonly DayDto[];
  /** Дзвінки на сьогодні; у вихідний — порожньо. */
  bells: readonly BellSlotDto[];
}

/**
 * Коли пар сьогодні вже (чи зовсім) немає: тихий короткий блок — заголовок і наступний факт, без порожньої
 * доріжки з сірих смуг. Той самий тон, що й у героя, щоб сторінка не «стрибала» між станами.
 */
function DayDone({ title, dateLine, next }: { title: string; dateLine: string; next: string | null }) {
  return (
    <section aria-label="Стан навчального дня" className="flex flex-col gap-1 rounded-[20px] bg-today-wash px-5 py-5 md:rounded-hero md:px-10 md:py-7">
      <p className="text-body text-secondary">{dateLine}</p>
      <p className="font-display text-[1.5rem] leading-[1.875rem] font-semibold tracking-[-0.02em] text-primary sm:text-headline">{title}</p>
      {next ? <p className="text-body text-secondary">{next}</p> : null}
    </section>
  );
}

/**
 * «Зараз» для викладача: відлік до дзвінка, доріжка його власних пар і одне речення про те, що він веде
 * зараз або далі. Єдине, що рухається на сторінці. Після останньої пари й у дні без пар — що буде далі:
 * перша пара наступного дня з парами (за потреби дивимося й наступний тиждень).
 */
export function MyNow({ teacherId, today, days, bells }: MyNowProps) {
  const day = days.find((d) => d.date === today);
  const lessons = useMemo(() => ownLessons(day, teacherId), [day, teacherId]);
  const pairs = useMemo(() => activePairs(day ? { ...day, lessons } : undefined), [day, lessons]);
  const onlyPairs = useMemo(() => new Set(pairs), [pairs]);
  const { status } = useNow(bells, { date: today, onlyPairs });

  const done = status === null || status.kind === 'after-day' || status.kind === 'no-classes';
  const thisWeek = done ? nextDaySentence(days, today, teacherId) : null;
  // у цьому тижні моїх пар більше немає — перша пара наступного тижня
  const nextWeek = studyWeek(addDays(studyWeek(today).from, 7));
  const later = useMySchedule(nextWeek.from, nextWeek.to, { enabled: done && thisWeek === null });

  if (done) {
    const laterData = later.data && !later.isPlaceholderData ? later.data : null;
    const next =
      thisWeek ??
      (laterData ? (nextDaySentence(laterData.days, today, teacherId) ?? 'Наступного тижня пар у вас немає.') : null);
    const title = status?.kind === 'after-day' ? 'На сьогодні все' : isWeekend(today) ? 'Сьогодні вихідний' : 'Сьогодні пар немає';
    return <DayDone title={title} dateLine={formatWeekdayDate(today)} next={next} />;
  }

  // що саме веду зараз або веду наступним: без цього відлік не каже, куди йти
  const focusPair = status.kind === 'in-pair' ? status.current.pair : status.next.pair;
  const focus = lessons.filter((lesson) => lesson.pair === focusPair);
  const aside =
    focus.length > 0 ? (
      <>
        {status.kind === 'in-pair' ? 'Зараз у вас ' : status.kind === 'break' ? 'Далі у вас ' : 'У вас '}
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
    />
  );
}
