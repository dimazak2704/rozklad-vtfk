import { useSearchParams } from 'react-router';
import { useMe } from '@/api/me';
import { AbsenceForm } from '@/components/teacher/AbsenceForm';
import { AbsenceList } from '@/components/teacher/AbsenceList';
import { EmptyState, ErrorState, PageHeader, Skeleton } from '@/components/ui';
import { todayInKyiv } from '@/lib/date';

/** Мої відсутності: список і форма нової (на сьогодні чи майбутні дати), видалення майбутніх. */
export default function MyAbsencesPage() {
  const me = useMe();
  const [params] = useSearchParams();
  const today = todayInKyiv();
  const teacher = me.data?.teacher;

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Мої відсутності"
        description="Повідомте наперед — диспетчер побачить це одразу й підбере заміни на ваші пари. Причину бачить лише диспетчер."
      />
      {me.isPending ? (
        <Skeleton className="h-64 w-full rounded-panel" />
      ) : me.isError ? (
        <ErrorState error={me.error} onRetry={() => void me.refetch()} retrying={me.isFetching} />
      ) : !teacher ? (
        <EmptyState
          align="start"
          title="Обліковий запис не повʼязаний з викладачем"
          description="Повідомляти про відсутність може лише викладач. Попросіть диспетчера привʼязати ваш обліковий запис."
        />
      ) : (
        <div className="grid items-start gap-8 lg:grid-cols-[minmax(0,1fr)_minmax(22rem,26rem)]">
          <AbsenceList today={today} />
          <aside className="order-first rounded-panel border border-default bg-surface p-4 lg:order-none">
            <AbsenceForm teacherId={teacher.id} today={today} autoFocus={params.get('new') === '1'} />
          </aside>
        </div>
      )}
    </div>
  );
}
