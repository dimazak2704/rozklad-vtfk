import { useMemo } from 'react';
import { useBells } from '@/api/meta';
import { useMyChanges, useMySchedule } from '@/api/me';
import type { BellSlotDto, DayDto, IsoDate } from '@/api/types';
import { studyWeek } from '@/components/public/schedule/schedule-params';
import { activePairs } from '@/components/public/schedule/schedule-utils';
import { NowIndicator } from '@/components/schedule';
import { useNow } from '@/hooks/useNow';
import { addDays, formatWeekdayDate, isWeekend } from '@/lib/date';
import {
  assignmentPairs,
  assignmentPhrase,
  confirmedAssignments,
  laterRequestSentence,
  lessonPhrase,
  nextDaySentence,
  nextOwnDay,
  nextRequest,
  ownLessons,
  pendingRequests,
  requestPhrase,
  withRequest,
} from './my-day';
import { useAssignedScheduleState } from './useAssignedSchedule';

interface MyNowProps {
  teacherId: string;
  today: IsoDate;
  /** Дні поточного тижня викладача з опублікованого розкладу; підтверджені заміни MyNow додає сам. */
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

/** Початок речення «що я веду» за станом дня. */
function focusLead(kind: 'in-pair' | 'break' | 'before-day'): string {
  if (kind === 'in-pair') return 'Зараз у вас ';
  if (kind === 'break') return 'Далі у вас ';
  return 'У вас ';
}

/**
 * «Зараз» для викладача: відлік до дзвінка, доріжка його власних пар (разом із підтвердженими замінами, яких
 * ще немає в опублікованому розкладі) і одне речення про те, що він веде зараз або далі. Єдине, що рухається на сторінці. Після останньої пари й у дні без пар — що буде далі:
 * перша пара наступного дня з парами (за потреби дивимося й наступний тиждень) і запит на заміну, якщо він
 * чекає відповіді раніше: його пари ще немає в розкладі, а «Завтра пар немає» поруч із ним заплутує.
 */
export function MyNow({ teacherId, today, days, bells }: MyNowProps) {
  const day = days.find((d) => d.date === today);
  const lessons = useMemo(() => ownLessons(day, teacherId), [day, teacherId]);
  // той самий кеш, що й у списку запитів нижче: окремого запиту до сервера немає
  const changes = useMyChanges();
  // підтверджена заміна сьогодні — теж моя пара, хоч розпорядження ще не опубліковане
  const assignments = useMemo(() => confirmedAssignments(changes.data ?? [], today), [changes.data, today]);
  // запит на пізнішу пару сьогодні, що чекає відповіді, — теж пара, яку я, можливо, вестиму: поки вона попереду,
  // день не «скінчився». Пару, що вже минула, не рахуємо — годинник без дзвінків, щоб не залежати від `pairs`.
  const { clock: wall } = useNow();
  const requests = useMemo(
    () => pendingRequests(changes.data ?? [], today, { bells, minutes: wall.minutes }),
    [changes.data, today, bells, wall.minutes],
  );
  const pairs = useMemo(
    () =>
      [...new Set([...activePairs(day ? { ...day, lessons } : undefined), ...assignmentPairs(assignments), ...assignmentPairs(requests)])].sort(
        (a, b) => a - b,
      ),
    [day, lessons, assignments, requests],
  );
  const onlyPairs = useMemo(() => new Set(pairs), [pairs]);
  const { status, clock } = useNow(bells, { date: today, onlyPairs });

  const done = status === null || status.kind === 'after-day' || status.kind === 'no-classes';
  // «що далі» — за тим самим розкладом, що й сітка нижче: разом із підтвердженими, ще не опублікованими замінами.
  // Сьогоднішній день беремо з опублікованого розкладу вище: заміни сьогодні фраза «зараз» називає окремо, без повтору.
  const week = useMemo(() => ({ days: [...days] }), [days]);
  const merged = useAssignedScheduleState(week);
  const weekDays = merged.data?.days ?? days;
  const thisWeek = done ? nextDaySentence(weekDays, today, teacherId) : null;
  // у цьому тижні моїх пар більше немає — перша пара наступного тижня
  const nextWeek = studyWeek(addDays(studyWeek(today).from, 7));
  const later = useMySchedule(nextWeek.from, nextWeek.to, { enabled: done && !merged.pending && thisWeek === null });
  const laterMerged = useAssignedScheduleState(later.data && !later.isPlaceholderData ? later.data : undefined);
  const request = done && changes.data ? nextRequest(changes.data, today, { bells, minutes: clock.minutes }) : null;
  const requestBells = useBells(request?.date);
  const requestStart = requestBells.data?.slots.find((slot) => slot.pair === request?.pairFrom)?.startsAt;

  // доки не знаємо моїх замін (і дзвінків до них), не кажемо «пар немає»: підтверджена заміна це речення спростувала б
  if (done && (changes.isPending || merged.pending)) return null;

  if (done) {
    const laterData = laterMerged.data && !laterMerged.pending ? laterMerged.data : null;
    const own =
      thisWeek ??
      (laterData ? (nextDaySentence(laterData.days, today, teacherId) ?? 'Наступного тижня пар у вас немає.') : null);
    const ownDay = nextOwnDay(weekDays, today, teacherId) ?? (laterData ? nextOwnDay(laterData.days, today, teacherId) : null);
    // поки наступний тиждень вантажиться, речення немає зовсім: запит до нього не чіпляємо, щоб текст не «стрибав»
    const next = own === null ? null : withRequest(own, request, today, ownDay?.day.date ?? null, requestStart);
    const title = status?.kind === 'after-day' ? 'На сьогодні все' : isWeekend(today) ? 'Сьогодні вихідний' : 'Сьогодні пар немає';
    return <DayDone title={title} dateLine={formatWeekdayDate(today)} next={next} />;
  }

  // що саме веду зараз або веду наступним: без цього відлік не каже, куди йти
  const focusPair = status.kind === 'in-pair' ? status.current.pair : status.next.pair;
  const focus = [
    ...lessons.filter((lesson) => lesson.pair === focusPair).map(lessonPhrase),
    ...assignments.filter((c) => c.pairFrom <= focusPair && focusPair <= c.pairTo).map(assignmentPhrase),
    ...requests.filter((c) => c.pairFrom <= focusPair && focusPair <= c.pairTo).map(requestPhrase),
  ];
  // запит на пару після тієї, про яку речення: відлік уже веде до неї, тож кажемо, що це за пара
  const laterRequest = requests.find((c) => c.pairFrom > focusPair);
  const lead = focusLead(status.kind);
  const aside =
    focus.length > 0 || laterRequest ? (
      <>
        {focus.length > 0 ? (
          <>
            {lead}
            <span className="font-[650] text-primary">{focus.join('; ')}</span>
            {laterRequest ? '. ' : null}
          </>
        ) : null}
        {laterRequest ? laterRequestSentence(laterRequest) : null}
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
