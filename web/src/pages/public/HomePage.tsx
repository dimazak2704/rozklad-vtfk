import { ArrowRight } from 'lucide-react';
import { Link } from 'react-router';
import { useBells, usePublicMeta } from '@/api/meta';
import { GroupsDirectory } from '@/components/public/home/GroupsDirectory';
import { LastViewedDay } from '@/components/public/home/LastViewedDay';
import { NowPanel } from '@/components/public/home/NowPanel';
import { ScheduleFinder } from '@/components/public/home/ScheduleFinder';
import { TelegramCard } from '@/components/public/home/TelegramCard';
import { TodayChanges } from '@/components/public/home/TodayChanges';
import { useHomeDay } from '@/components/public/home/useHomeDay';
import { BellsCompact } from '@/components/public/schedule/BellsCompact';
import { buttonClass, Skeleton } from '@/components/ui';
import { formatWeekdayDate } from '@/lib/date';

/** Дзвінки дня, про який розповідає головна (сьогодні або наступний навчальний). */
function BellsToday() {
  const home = useHomeDay();
  const bells = useBells(home.date);
  return (
    <section aria-labelledby="bells-today-heading" className="flex flex-col gap-3 rounded-panel border border-default bg-surface p-4">
      <div className="flex flex-wrap items-baseline justify-between gap-x-3">
        <h2 id="bells-today-heading" className="text-heading font-semibold text-primary">
          {home.isToday ? 'Дзвінки сьогодні' : `Дзвінки: ${formatWeekdayDate(home.date).toLocaleLowerCase('uk')}`}
        </h2>
        {bells.data ? <span className="text-caption text-muted">{bells.data.profile.name}</span> : null}
      </div>
      {bells.data ? (
        <BellsCompact slots={bells.data.slots} live={home.isToday} showBreaks={false} />
      ) : bells.isError ? (
        <p className="text-ui text-secondary">Дзвінки не завантажилися. Повний розклад дзвінків — на сторінці «Дзвінки».</p>
      ) : (
        <div className="flex flex-col gap-2" aria-hidden="true">
          {Array.from({ length: 6 }, (_, index) => (
            <Skeleton key={index} className="h-6 w-full" />
          ))}
        </div>
      )}
      <Link to="/bells" className={buttonClass({ variant: 'link', className: 'gap-1.5 self-start' })}>
        Усі профілі дзвінків
        <ArrowRight aria-hidden="true" className="size-4 rtl:rotate-180" />
      </Link>
    </section>
  );
}

/**
 * Головна — пошук розкладу, а не вітрина: великий миттєвий пошук, обране й нещодавні, «зараз»,
 * заміни дня, розклад останньої групи, усі групи за курсами, дзвінки й Telegram-бот.
 */
export default function HomePage() {
  const meta = usePublicMeta();

  return (
    <div className="grid items-start gap-8 lg:grid-cols-[minmax(0,1fr)_22.5rem] xl:gap-12">
      <div className="flex min-w-0 flex-col gap-8">
        <header className="flex flex-col gap-4">
          <div className="flex flex-col gap-1.5">
            <h1 className="text-headline font-semibold tracking-[-0.005em] text-primary">Розклад занять</h1>
            <p className="max-w-prose text-body text-secondary">
              {meta.data?.collegeName ?? 'Вінницький технічний фаховий коледж'}: пари груп, викладачів і аудиторій із
              сьогоднішніми замінами.
            </p>
          </div>
          <ScheduleFinder autoFocus />
        </header>

        <div className="flex flex-col gap-6 lg:hidden">
          <NowPanel />
          <TodayChanges />
        </div>

        <LastViewedDay />
        <GroupsDirectory />
        <TelegramCard />
      </div>

      <aside aria-label="Сьогодні в коледжі" className="flex min-w-0 flex-col gap-6">
        <div className="hidden flex-col gap-6 lg:flex">
          <NowPanel />
          <TodayChanges />
        </div>
        <BellsToday />
      </aside>
    </div>
  );
}
