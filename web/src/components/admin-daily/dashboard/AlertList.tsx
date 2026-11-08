import { CalendarX, ChevronRight, CircleExclamationPoint, FileText, Hourglass, Info, TriangleExclamationPoint, UserX } from 'lucide-react';
import type { ReactNode } from 'react';
import { Link } from 'react-router';
import type { DashboardAlert, DashboardAlertKind } from '@/api/types';
import { cn } from '@/lib/cn';

/** Іконка за видом сповіщення (рівно ті види, що надсилає core); невідомі нові види — за серйозністю. */
const KIND_ICON: Record<DashboardAlertKind, ReactNode> = {
  NO_SEMESTER: <CalendarX aria-hidden="true" />,
  UNCOVERED_LESSONS: <CircleExclamationPoint aria-hidden="true" />,
  DECLINED: <UserX aria-hidden="true" />,
  PENDING_OVERDUE: <Hourglass aria-hidden="true" />,
  ORDER_NOT_PUBLISHED: <FileText aria-hidden="true" />,
};

function kindIcon(kind: string): ReactNode | undefined {
  return Object.hasOwn(KIND_ICON, kind) ? KIND_ICON[kind as DashboardAlertKind] : undefined;
}

const SEVERITY: Record<DashboardAlert['severity'], { icon: ReactNode; tone: string; label: string }> = {
  ERROR: { icon: <CircleExclamationPoint aria-hidden="true" />, tone: 'text-danger', label: 'Терміново' },
  WARN: { icon: <TriangleExclamationPoint aria-hidden="true" />, tone: 'text-change', label: 'Увага' },
  INFO: { icon: <Info aria-hidden="true" />, tone: 'text-accent', label: 'До відома' },
};

/** Що вимагає дії зараз: від термінового до довідкового; кожен пункт веде туди, де це виправляють. */
export function AlertList({ alerts }: { alerts: readonly DashboardAlert[] }) {
  return (
    <ul className="flex flex-col divide-y divide-[var(--border-color-separator)]">
      {alerts.map((alert, index) => {
        const severity = SEVERITY[alert.severity];
        const content = (
          <>
            <span className={cn('mt-0.5 shrink-0 [&_svg]:size-4', severity.tone)}>{kindIcon(alert.kind) ?? severity.icon}</span>
            <span className="flex min-w-0 flex-1 flex-col">
              <span className={cn('text-meta font-semibold', severity.tone)}>{severity.label}</span>
              <span className="text-ui text-primary">{alert.message}</span>
            </span>
            {alert.link ? <ChevronRight aria-hidden="true" className="mt-1 size-4 shrink-0 text-muted rtl:rotate-180" /> : null}
          </>
        );
        return (
          <li key={`${alert.kind}-${index}`}>
            {alert.link ? (
              <Link
                to={alert.link}
                className="flex items-start gap-3 px-4 py-3 transition-[background-color] duration-100 ease-out hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus"
              >
                {content}
              </Link>
            ) : (
              <div className="flex items-start gap-3 px-4 py-3">{content}</div>
            )}
          </li>
        );
      })}
    </ul>
  );
}
