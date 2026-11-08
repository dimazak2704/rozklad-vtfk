import { Link } from 'react-router';
import { routeById } from '@/app/routes';
import { PageHeader } from './ui/PageHeader';

interface PagePlaceholderProps {
  /** id маршруту з src/app/routes.ts — звідти береться заголовок. */
  routeId: string;
}

/**
 * Тимчасова сторінка: заголовок, один чесний рядок і шлях назад. Без «будівельних» рамок і силуетів.
 * Агент сторінки замінює весь файл сторінки, а не цей компонент.
 */
export function PagePlaceholder({ routeId }: PagePlaceholderProps) {
  const route = routeById(routeId);
  const isAdmin = route?.layout === 'admin';
  const home = isAdmin ? '/admin' : route?.layout === 'teacher' ? '/me' : '/';

  return (
    <div className="flex flex-col gap-3">
      <PageHeader title={route?.title ?? 'Сторінка'} size={isAdmin ? 'default' : 'headline'} />
      <p className="text-secondary">
        Розділ ще в роботі.{' '}
        <Link
          to={home}
          className="font-medium text-accent underline underline-offset-4 hover:decoration-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus"
        >
          {isAdmin ? 'До панелі' : route?.layout === 'teacher' ? 'До мого розкладу' : 'На головну'}
        </Link>
      </p>
    </div>
  );
}
