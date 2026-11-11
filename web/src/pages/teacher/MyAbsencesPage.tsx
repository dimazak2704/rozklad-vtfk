import { useSearchParams } from 'react-router';
import { useMe, useMyAbsences } from '@/api/me';
import { AbsenceForm } from '@/components/teacher/AbsenceForm';
import { AbsenceList } from '@/components/teacher/AbsenceList';
import { EmptyState, PageErrorScope, PageHeader, Skeleton } from '@/components/ui';
import { todayInKyiv } from '@/lib/date';

/**
 * Мої відсутності: форма нової й те, що вже повідомлено (попереду й минулі). На телефоні спершу короткий
 * список, потім форма; перехід «Повідомити про відсутність» (`?new=1`) одразу прокручує до форми.
 */
export default function MyAbsencesPage() {
  const me = useMe();
  const absences = useMyAbsences();
  const [params] = useSearchParams();
  const today = todayInKyiv();
  const teacher = me.data?.teacher;

  return (
    <div className="flex flex-col gap-8">
      <PageHeader title={<span className="whitespace-nowrap">Мої відсутності</span>} size="headline" />
      <PageErrorScope queries={[me, absences]}>
        {me.isPending ? (
          <Skeleton className="h-64 w-full rounded-panel" />
        ) : me.isError ? null : !teacher ? (
          <EmptyState
            title="Обліковий запис ще не повʼязаний з викладачем."
            description="Повідомляти про відсутність може лише викладач. Попросіть диспетчера привʼязати ваш обліковий запис."
          />
        ) : (
          <div className="grid items-start gap-x-12 gap-y-10 lg:grid-cols-[minmax(0,1fr)_20rem]">
            {/* на телефоні спершу короткий список повідомленого, на десктопі він — праворуч від форми */}
            <div className="lg:order-last">
              <AbsenceList today={today} />
            </div>
            <div className="lg:rounded-panel lg:border lg:border-default lg:bg-surface lg:p-8">
              <AbsenceForm teacherId={teacher.id} today={today} autoFocus={params.get('new') === '1'} />
            </div>
          </div>
        )}
      </PageErrorScope>
    </div>
  );
}
