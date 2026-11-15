import { CalendarArrowDown, CalendarX, ExternalLink, House, Printer, Smartphone } from 'lucide-react';
import { Link } from 'react-router';
import { isApiError } from '@/api/errors';
import { useBells, usePublicMeta } from '@/api/meta';
import { useMe, useMyChanges, useMySchedule } from '@/api/me';
import { downloadIcs, icsUrls } from '@/api/public';
import { useMyBinding } from '@/api/telegram';
import { paths } from '@/app/routes';
import { useCurrentUser } from '@/auth/useCurrentUser';
import { SchedulePrint } from '@/components/public/print/SchedulePrint';
import { usePrintMode } from '@/components/public/print/usePrintMode';
import { studyWeek, useScheduleParams } from '@/components/public/schedule/schedule-params';
import { semesterWeekOf } from '@/components/public/schedule/schedule-utils';
import { useDocumentTitle } from '@/components/public/useDocumentTitle';
import { MyLessons } from '@/components/teacher/MyLessons';
import { MyNow } from '@/components/teacher/MyNow';
import { MyUpcomingChanges } from '@/components/teacher/MyUpcomingChanges';
import { SubstitutionRequests } from '@/components/teacher/SubstitutionRequests';
import { TelegramConnect } from '@/components/teacher/TelegramConnect';
import { useAssignedSchedule } from '@/components/teacher/useAssignedSchedule';
import {
  EmptyState,
  PageErrorBanner,
  PageErrorScope,
  PageHeader,
  Skeleton,
  buttonClass,
  notify,
  type MoreMenuItem,
} from '@/components/ui';
import { isWeekend } from '@/lib/date';

const ICS_FILE = 'rozklad-mii.ics';

/** Заголовок без переносу посеред назви: «Мій розклад» завжди одним рядком. */
const TITLE = <span className="whitespace-nowrap">Мій розклад</span>;

/**
 * Кабінет викладача. Угорі — «зараз» (відлік до дзвінка і що я веду), далі заміни, що чекають відповіді,
 * мій день чи тиждень з урахуванням замін, а внизу — мої майбутні заміни й Telegram.
 * Усе другорядне (календар, друк, публічна сторінка) — у меню «⋯»; одна помилка на сторінку.
 */
export default function MyDayPage() {
  const me = useMe();
  const { isAdmin } = useCurrentUser();
  const meta = usePublicMeta();
  const params = useScheduleParams();
  const teacher = me.data?.teacher ?? null;
  const linked = teacher !== null;
  const schedule = useMySchedule(params.week.from, params.week.to, { enabled: linked });
  // «зараз» — завжди про сьогодні, хоч би який тиждень відкрито нижче (той самий ключ кешу для поточного тижня)
  const todayWeek = studyWeek(params.today);
  const current = useMySchedule(todayWeek.from, todayWeek.to, { enabled: linked });
  const weekend = isWeekend(params.today);
  const bells = useBells(weekend ? null : params.today);
  const changes = useMyChanges();
  const binding = useMyBinding({ waiting: false });
  // друк — ті самі пари, що й у сітці: з підтвердженими, ще не опублікованими замінами
  const printable = useAssignedSchedule(schedule.isPlaceholderData ? undefined : schedule.data);
  const { mode, print } = usePrintMode();

  useDocumentTitle(teacher ? `${teacher.shortName} — мій розклад` : null);

  if (me.isPending) {
    return (
      <div className="flex flex-col gap-6" aria-busy="true">
        <Skeleton className="h-9 w-48" />
        <Skeleton className="h-52 w-full rounded-hero" />
        <Skeleton className="h-64 w-full rounded-panel" />
      </div>
    );
  }
  if (me.isError) {
    return (
      <div className="flex flex-col gap-6">
        <PageHeader title={TITLE} size="headline" />
        <PageErrorBanner error={me.error} title="Не вдалося відкрити кабінет" onRetry={() => void me.refetch()} retrying={me.isFetching} />
      </div>
    );
  }
  if (!teacher) {
    return (
      <div className="flex flex-col gap-6">
        <PageHeader title={TITLE} size="headline" />
        <EmptyState
          title="Обліковий запис ще не повʼязаний з викладачем."
          description="Кабінет показує пари й заміни конкретного викладача. Попросіть диспетчера привʼязати ваш обліковий запис до вашого прізвища."
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
      </div>
    );
  }

  const weekNumber = semesterWeekOf(params.date, meta.data?.semester);
  const ics = icsUrls('TEACHER', teacher.id);
  const downloadCalendar = async () => {
    try {
      // кабінет — лише для персоналу: файл з токеном, увесь семестр
      await downloadIcs('TEACHER', teacher.id, ICS_FILE, 'staff');
      notify.success(`Файл ${ICS_FILE} завантажено`, { description: 'Відкрийте його — календар запропонує додати пари.' });
    } catch (error) {
      notify.error(error, 'Не вдалося завантажити календар');
    }
  };
  const more: MoreMenuItem[] = [
    { label: 'Підписатися в календарі телефону', icon: <Smartphone aria-hidden="true" />, href: ics.webcal },
    { label: 'Завантажити календар (.ics)', icon: <CalendarArrowDown aria-hidden="true" />, onSelect: () => void downloadCalendar() },
    { label: 'Друкувати тиждень', icon: <Printer aria-hidden="true" />, onSelect: () => print('week') },
    { label: 'Моя публічна сторінка', icon: <ExternalLink aria-hidden="true" />, to: paths.teacher(teacher.id), separatorBefore: true },
    { label: 'Розклад коледжу', icon: <House aria-hidden="true" />, to: '/' },
  ];
  // 422 teacher-not-linked — не збій, а стан облікового запису: його пояснює сам блок Telegram
  const bindingFailed = binding.isError && !(isApiError(binding.error) && binding.error.code === 'teacher-not-linked');
  // у вихідний дзвінків немає, але «що далі» (перша пара в понеділок) людині все одно потрібне
  const nowSlots = weekend ? [] : bells.data?.slots;
  const nowReady = current.data && !current.isPlaceholderData && nowSlots;

  return (
    <div className="flex flex-col gap-8 lg:gap-12">
      <PageHeader
        title={TITLE}
        size="headline"
        controls={
          <Link to="/me/absences?new=1" className={buttonClass({ variant: 'secondary' })}>
            <CalendarX aria-hidden="true" className="size-4" />
            Повідомити про відсутність
          </Link>
        }
        more={more}
      />

      <PageErrorScope queries={[schedule, current, bells, changes, { ...binding, isError: bindingFailed }]} className="gap-8 lg:gap-12">
        {nowReady ? <MyNow teacherId={teacher.id} today={params.today} days={current.data.days} bells={nowSlots} /> : null}
        <SubstitutionRequests teacherId={teacher.id} />
        <MyLessons query={schedule} params={params} weekNumber={weekNumber} />
        <div className="grid gap-x-12 gap-y-8 border-t border-separator pt-8 md:grid-cols-2">
          <MyUpcomingChanges />
          <TelegramConnect teacherId={teacher.id} />
        </div>
      </PageErrorScope>

      <SchedulePrint
        mode={mode}
        schedule={printable}
        perspective="teacher"
        kindLabel="Розклад викладача"
        title={teacher.shortName}
        url={`${window.location.origin}${paths.teacher(teacher.id)}`}
        weekNumber={weekNumber}
      />
    </div>
  );
}
