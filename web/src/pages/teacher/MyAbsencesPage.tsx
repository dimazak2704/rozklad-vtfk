import { useSearchParams } from 'react-router';
import { useMe, useMyAbsences } from '@/api/me';
import { usePublicMeta } from '@/api/meta';
import { AbsenceForm } from '@/components/teacher/AbsenceForm';
import { AbsenceList } from '@/components/teacher/AbsenceList';
import { EmptyState, PageErrorScope, PageHeader, Skeleton } from '@/components/ui';
import { cn } from '@/lib/cn';
import { todayInKyiv } from '@/lib/date';

/**
 * Мої відсутності: форма нової й те, що вже повідомлено (попереду й минулі). На телефоні спершу короткий
 * список, потім форма; перехід «Повідомити про відсутність» (`?new=1`) ставить форму першою й фокусує календар.
 */
export default function MyAbsencesPage() {
  const me = useMe();
  const absences = useMyAbsences();
  const [params] = useSearchParams();
  const meta = usePublicMeta();
  // «сьогодні» стенду (core meta), як на інших сторінках; до відповіді — годинник застосунку
  const today = meta.data?.today ?? todayInKyiv();
  const teacher = me.data?.teacher;
  const reporting = params.get('new') === '1';

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
            {/*
              на телефоні спершу короткий список повідомленого, на десктопі він — праворуч від форми;
              прийшли з «Повідомити про відсутність» — форма перша й на телефоні, без прокрутки посеред сторінки
            */}
            <div className={cn('lg:order-last', reporting && 'order-last')}>
              <AbsenceList today={today} />
            </div>
            <div className="lg:rounded-panel lg:border lg:border-default lg:bg-surface lg:p-8">
              <AbsenceForm teacherId={teacher.id} today={today} autoFocus={reporting} />
            </div>
          </div>
        )}
      </PageErrorScope>
    </div>
  );
}
