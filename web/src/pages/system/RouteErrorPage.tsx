import { RotateCcw } from 'lucide-react';
import { isRouteErrorResponse, Link, useRouteError } from 'react-router';
import { isApiError } from '@/api/errors';
import { Button } from '@/components/ui/Button';
import { buttonClass } from '@/components/ui/styles';

/** Після оновлення застосунку старі частини коду зникають з сервера — допомагає перезавантаження. */
function isChunkLoadError(error: unknown): boolean {
  return (
    error instanceof Error &&
    /Failed to fetch dynamically imported module|Importing a module script failed|error loading dynamically imported module/i.test(
      error.message,
    )
  );
}

/** Межа помилок маршруту: показується всередині макета, шапка й навігація лишаються робочими. */
export function RouteErrorPage() {
  const error = useRouteError();

  let title = 'На сторінці сталася помилка';
  let description = 'Спробуйте оновити сторінку. Якщо помилка повторюється, напишіть диспетчеру, що саме ви робили.';
  if (isChunkLoadError(error)) {
    title = 'Вийшла нова версія сайту';
    description = 'Оновіть сторінку, щоб завантажити її.';
  } else if (isRouteErrorResponse(error)) {
    title = error.status === 404 ? 'Не знайдено' : `Помилка ${error.status}`;
    description = typeof error.data === 'string' && error.data ? error.data : description;
  } else if (isApiError(error)) {
    title = error.title;
    description = error.message;
  }

  if (import.meta.env.DEV && error instanceof Error) console.error(error);

  return (
    <section role="alert" className="mx-auto flex max-w-xl flex-col gap-4 py-10 sm:py-16">
      <h1 className="text-title font-semibold text-primary">{title}</h1>
      <p className="text-body text-secondary">{description}</p>
      <div className="flex flex-wrap gap-2">
        <Button variant="primary" iconStart={<RotateCcw aria-hidden="true" className="size-4" />} onClick={() => window.location.reload()}>
          Оновити сторінку
        </Button>
        <Link to="/" className={buttonClass({ variant: 'secondary' })}>
          На головну
        </Link>
      </div>
      {import.meta.env.DEV && error instanceof Error ? (
        <pre className="mt-4 overflow-x-auto rounded-control border border-default bg-sunken p-3 font-mono text-caption whitespace-pre-wrap text-secondary">
          {error.stack ?? error.message}
        </pre>
      ) : null}
    </section>
  );
}

export default RouteErrorPage;
