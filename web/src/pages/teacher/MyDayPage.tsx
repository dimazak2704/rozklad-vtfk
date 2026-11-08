import { CalendarCog, CalendarX, ExternalLink, Printer } from 'lucide-react';
import { Link } from 'react-router';
import { usePublicMeta } from '@/api/meta';
import { useMe, useMySchedule } from '@/api/me';
import { paths } from '@/app/routes';
import { useCurrentUser } from '@/auth/useCurrentUser';
import { SchedulePrint } from '@/components/public/print/SchedulePrint';
import { usePrintMode } from '@/components/public/print/usePrintMode';
import { CalendarMenuButton } from '@/components/public/schedule/ScheduleActions';
import { ScheduleExplorer } from '@/components/public/schedule/ScheduleExplorer';
import { useScheduleParams } from '@/components/public/schedule/schedule-params';
import { semesterWeekOf } from '@/components/public/schedule/schedule-utils';
import { useDocumentTitle } from '@/components/public/useDocumentTitle';
import { MyUpcomingChanges } from '@/components/teacher/MyUpcomingChanges';
import { SubstitutionRequests } from '@/components/teacher/SubstitutionRequests';
import { TelegramConnect } from '@/components/teacher/TelegramConnect';
import { EmptyState, ErrorState, IconButton, PageHeader, ParityBadge, Skeleton, buttonClass } from '@/components/ui';

const LINK = 'flex items-center gap-2.5 rounded-control px-2 py-2 text-ui font-semibold text-primary hover:bg-hover focus-visible:outline-2 focus-visible:outline-focus';

/** Кабінет викладача: запити на заміну, мій день і тиждень (наживо), мої заміни, Telegram. */
export default function MyDayPage() {
  const me = useMe();
  const { isAdmin } = useCurrentUser();
  const meta = usePublicMeta();
  const params = useScheduleParams();
  const teacher = me.data?.teacher ?? null;
  const schedule = useMySchedule(params.week.from, params.week.to, { enabled: teacher !== null });
  const { mode, print } = usePrintMode();

  useDocumentTitle(teacher ? `${teacher.shortName} — мій розклад` : null);

  if (me.isPending) {
    return (
      <div className="flex flex-col gap-4" aria-busy="true">
        <Skeleton className="h-8 w-56" />
        <Skeleton className="h-4 w-80 max-w-full" />
        <Skeleton className="h-48 w-full rounded-panel" />
      </div>
    );
  }
  if (me.isError) {
    return <ErrorState error={me.error} title="Не вдалося відкрити кабінет" onRetry={() => void me.refetch()} retrying={me.isFetching} />;
  }
  if (!teacher) {
    return (
      <EmptyState
        align="start"
        title="Обліковий запис не повʼязаний з викладачем"
        description="Кабінет показує розклад і заміни конкретного викладача. Попросіть диспетчера надіслати вам запрошення або привʼязати обліковий запис до вашого прізвища."
        action={
          isAdmin ? (
            <Link to="/admin" className={buttonClass({ variant: 'primary' })}>
              До панелі диспетчера
            </Link>
          ) : (
            <Link to="/" className={buttonClass({ variant: 'secondary' })}>
              Розклад коледжу
            </Link>
          )
        }
      />
    );
  }

  const weekNumber = semesterWeekOf(params.date, meta.data?.semester);
  const parity = schedule.isPlaceholderData ? null : schedule.data?.days[0]?.parity;

  return (
    <div className="flex flex-col gap-8">
      <PageHeader
        title="Мій розклад"
        description={`${teacher.shortName} · запити на заміну, ваші пари з урахуванням замін і привʼязка Telegram.`}
        meta={parity ? <ParityBadge parity={parity} weekNumber={weekNumber ?? undefined} size="sm" /> : undefined}
        actions={
          <>
            <Link to="/me/absences?new=1" className={buttonClass({ variant: 'primary' })}>
              <CalendarX aria-hidden="true" className="size-4" />
              Повідомити про відсутність
            </Link>
            <CalendarMenuButton ics={{ owner: 'TEACHER', id: teacher.id, fileName: 'rozklad-mii.ics' }} />
            <IconButton
              variant="secondary"
              label="Друкувати тиждень (A4)"
              shortcut="mod+p"
              icon={<Printer aria-hidden="true" className="size-4" />}
              onClick={() => print('week')}
            />
          </>
        }
      />

      <div className="grid items-start gap-8 lg:grid-cols-[minmax(0,1fr)_22rem]">
        <div className="flex min-w-0 flex-col gap-10">
          <SubstitutionRequests teacherId={teacher.id} />
          <section aria-labelledby="my-lessons-heading" className="flex flex-col gap-3">
            <h2 id="my-lessons-heading" className="text-title font-semibold text-primary">
              Мої пари
            </h2>
            <ScheduleExplorer query={schedule} params={params} perspective="teacher" ownerLabel={teacher.shortName} />
          </section>
        </div>

        <aside aria-label="Заміни, Telegram і налаштування" className="flex min-w-0 flex-col gap-6">
          <MyUpcomingChanges />
          <TelegramConnect teacherId={teacher.id} />
          <nav aria-label="Кабінет" className="flex flex-col rounded-panel border border-default bg-surface p-2">
            <Link to="/me/absences" className={LINK}>
              <CalendarX aria-hidden="true" className="size-4 text-muted" />
              Мої відсутності
            </Link>
            <Link to="/me/preferences" className={LINK}>
              <CalendarCog aria-hidden="true" className="size-4 text-muted" />
              Побажання до розкладу
            </Link>
            <Link to={paths.teacher(teacher.id)} className={LINK}>
              <ExternalLink aria-hidden="true" className="size-4 text-muted" />
              Мій публічний розклад
            </Link>
          </nav>
        </aside>
      </div>

      <SchedulePrint
        mode={mode}
        schedule={schedule.isPlaceholderData ? undefined : schedule.data}
        perspective="teacher"
        kindLabel="Розклад викладача"
        title={teacher.shortName}
        url={`${window.location.origin}${paths.teacher(teacher.id)}`}
        weekNumber={weekNumber}
      />
    </div>
  );
}
