import { BellRing, CalendarX, CircleCheck, FileCheck, Send, Undo2, UserX } from 'lucide-react';
import { useState, type ReactNode } from 'react';
import { Link } from 'react-router';
import { Button } from '@/components/ui';
import { cn } from '@/lib/cn';
import { formatInstantTime } from '@/lib/date';
import { plural } from '@/lib/labels';
import type { FeedItem, FeedKind } from '../model';

const ICON: Record<FeedKind, { icon: ReactNode; tone: string }> = {
  absence: { icon: <CalendarX aria-hidden="true" />, tone: 'text-secondary' },
  request: { icon: <Send aria-hidden="true" />, tone: 'text-change' },
  reminder: { icon: <BellRing aria-hidden="true" />, tone: 'text-change' },
  confirmed: { icon: <CircleCheck aria-hidden="true" />, tone: 'text-add' },
  declined: { icon: <UserX aria-hidden="true" />, tone: 'text-danger' },
  cancelled: { icon: <Undo2 aria-hidden="true" />, tone: 'text-muted' },
  applied: { icon: <FileCheck aria-hidden="true" />, tone: 'text-accent' },
  order: { icon: <FileCheck aria-hidden="true" />, tone: 'text-accent' },
};

const VISIBLE = 5;
const EVENT_FORMS = ['подія', 'події', 'подій'] as const;

interface ActivityFeedProps {
  items: readonly FeedItem[];
  /** Джерела (зміни, відсутності, розпорядження), що оновилися наживо після відкриття сторінки. */
  fresh: ReadonlySet<string>;
}

/** Стрічка дня: хто що зробив і коли — найновіше згори, нові події наживо «наливаються» кольором. */
export function ActivityFeed({ items, fresh }: ActivityFeedProps) {
  const [all, setAll] = useState(false);
  const shown = all ? items : items.slice(0, VISIBLE);
  // підсвічуємо лише найсвіжішу подію джерела: у зміни їх кілька (запит, відповідь, публікація)
  const latestOfSource = new Set<string>();
  const seenSources = new Set<string>();
  for (const item of items) {
    if (seenSources.has(item.sourceId)) continue;
    seenSources.add(item.sourceId);
    latestOfSource.add(item.id);
  }
  return (
    <div className="flex flex-col">
      <ol aria-label="Останні дії" aria-live="polite" aria-relevant="additions" className="flex flex-col">
        {shown.map((item) => {
          const isFresh = fresh.has(item.sourceId) && latestOfSource.has(item.id);
          const meta = ICON[item.kind];
          const body = (
            <>
              <time dateTime={item.at} className="w-11 shrink-0 pt-px text-meta font-semibold text-muted figures">
                {formatInstantTime(item.at)}
              </time>
              <span className={cn('mt-0.5 shrink-0 [&_svg]:size-4', meta.tone)}>{meta.icon}</span>
              <span className="min-w-0 text-ui text-primary">{item.text}</span>
            </>
          );
          const rowClass = cn(
            'flex items-start gap-3 rounded-popover px-3 py-2',
            isFresh && 'motion-safe:animate-[live-flash_2.4s_var(--ease-out)] bg-selected motion-safe:bg-transparent',
          );
          return (
            <li key={item.id}>
              {item.href ? (
                <Link
                  to={item.href}
                  className={cn(
                    rowClass,
                    'transition-[background-color] duration-100 ease-out hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus',
                  )}
                >
                  {body}
                </Link>
              ) : (
                <div className={rowClass}>{body}</div>
              )}
            </li>
          );
        })}
      </ol>
      {items.length > VISIBLE ? (
        <Button variant="ghost" size="sm" className="mt-1 self-start" aria-expanded={all} onClick={() => setAll((value) => !value)}>
          {all ? 'Згорнути' : `Ще ${items.length - VISIBLE} ${plural(items.length - VISIBLE, EVENT_FORMS)}`}
        </Button>
      ) : null}
    </div>
  );
}
