import { Hammer } from 'lucide-react';
import { Link, useParams } from 'react-router';
import { routeById } from '@/app/routes';
import { cn } from '@/lib/cn';
import { buttonClass } from './ui/styles';
import { PageHeader } from './ui/PageHeader';
import { Skeleton } from './ui/Skeleton';

interface PagePlaceholderProps {
  /** id маршруту з src/app/routes.ts — звідти беруться заголовок і опис. */
  routeId: string;
}

/**
 * Тимчасова сторінка: чесно каже, що тут буде, і показує силует майбутнього вмісту.
 * Агент сторінки замінює весь файл сторінки, а не цей компонент.
 */
export function PagePlaceholder({ routeId }: PagePlaceholderProps) {
  const route = routeById(routeId);
  const params = useParams();
  const paramEntries = Object.entries(params).filter(([key]) => key !== '*');
  const home = route?.layout === 'admin' ? '/admin' : route?.layout === 'teacher' ? '/me' : '/';

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title={route?.title ?? 'Сторінка'}
        description={route?.description}
        meta={
          paramEntries.length > 0 ? (
            <span className="flex flex-wrap gap-2 text-caption text-secondary">
              {paramEntries.map(([key, value]) => (
                <span key={key} className="rounded-plate border border-default bg-sunken px-2 py-0.5 font-semibold figures">
                  {key}: {value}
                </span>
              ))}
            </span>
          ) : undefined
        }
      />
      <section className="grid gap-4 rounded-panel border border-dashed border-strong bg-surface p-5 md:grid-cols-[minmax(0,1fr)_16rem]">
        <div className="flex flex-col gap-2">
          <p className="flex items-center gap-2 text-heading font-semibold text-primary">
            <Hammer aria-hidden="true" className="size-4 text-muted" />
            Сторінку ще будують
          </p>
          <p className="max-w-prose text-ui text-secondary">
            Маршрут і оболонка вже працюють: навігація, тема, пошук, клавіатурні скорочення. Вміст цієї сторінки
            зʼявиться в наступному кроці роботи над системою.
          </p>
          <div className="mt-2 flex flex-wrap gap-2">
            <Link to={home} className={buttonClass({ variant: 'secondary' })}>
              До головної цього розділу
            </Link>
          </div>
        </div>
        <div aria-hidden="true" className={cn('hidden flex-col gap-2 md:flex')}>
          <Skeleton className="h-4 w-2/3 motion-safe:animate-none" />
          <Skeleton className="h-16 w-full motion-safe:animate-none" />
          <Skeleton className="h-16 w-full motion-safe:animate-none" />
          <Skeleton className="h-4 w-1/2 motion-safe:animate-none" />
        </div>
      </section>
    </div>
  );
}
