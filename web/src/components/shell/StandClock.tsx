import { Clock3 } from 'lucide-react';
import { Fragment, type ReactNode } from 'react';
import { usePublicMeta } from '@/api/meta';
import { useNow } from '@/hooks/useNow';
import { useClockState } from '@/lib/clock';
import { cn } from '@/lib/cn';
import { formatDayMonth, isoWeekday, minutesToTime } from '@/lib/date';
import { weekdayShort } from '@/lib/labels';
import { Badge } from '../ui/Badge';

/**
 * Тримає годинник застосунку звіреним із сервером: meta запитується на кожній сторінці (кеш спільний,
 * тож там, де її й так читають, зайвого запиту немає) і раз на 5 хвилин оновлюється.
 */
export function StandClockSync() {
  usePublicMeta();
  return null;
}

/**
 * Коли час стенда стрибнув (увімкнули чи вимкнули переведений час), сторінка будується з нуля: інакше
 * частина екрана рахувала б «сьогодні» за старим годинником. Без стрибків нічого не перемальовує.
 */
export function ClockEpoch({ children }: { children: ReactNode }) {
  const { epoch } = useClockState();
  return <Fragment key={epoch}>{children}</Fragment>;
}

/**
 * Тиха позначка в шапці, лише на тестовому стенді з переведеним часом: щоб ніхто не плутав «пʼятницю 10:00»
 * на екрані зі справжнім часом. У звичайному режимі її немає.
 */
export function StandTimeBadge({ className }: { className?: string }) {
  const { standTime } = useClockState();
  const { clock } = useNow();
  if (!standTime) return null;
  const weekday = weekdayShort(isoWeekday(clock.date)).toLocaleLowerCase('uk');
  const time = minutesToTime(clock.minutes);
  const full = `Час стенда: ${weekday}, ${formatDayMonth(clock.date)}, ${time} (тест)`;
  return (
    <Badge
      tone="neutral"
      data-stand-time=""
      icon={<Clock3 aria-hidden="true" className="size-3.5 shrink-0" />}
      title="Тестовий стенд: годинник переведено, сайт і сервер живуть у цьому дні й часі"
      className={cn('shrink-0', className)}
    >
      <span className="sr-only">{full}</span>
      <span aria-hidden="true" className="figures xl:hidden">
        {weekday} {time} · тест
      </span>
      <span aria-hidden="true" className="figures hidden xl:inline">
        {full}
      </span>
    </Badge>
  );
}
