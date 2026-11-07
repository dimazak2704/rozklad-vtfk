import { KeyRound, LogIn } from 'lucide-react';
import { useEffect, useEffectEvent, useRef, useState } from 'react';
import { Navigate, useSearchParams } from 'react-router';
import { errorMessage } from '@/api/errors';
import { isSafeReturnPath, postLoginPath } from '@/auth/postLogin';
import { useCurrentUser } from '@/auth/useCurrentUser';
import { Button } from '@/components/ui/Button';

/**
 * Вхід для викладачів і диспетчерів (UI.md: «/login → Keycloak»). Сторінка одразу веде на вхід
 * коледжу (PKCE); якщо сервер входу недоступний — пояснює і дає спробувати ще раз.
 * Після повернення диспетчер потрапляє в /admin, викладач — у /me, або на сторінку, з якої прийшли.
 */
export default function LoginPage() {
  const { status, roles, signIn, error } = useCurrentUser();
  const [params] = useSearchParams();
  const returnTo = params.get('returnTo');
  const safeReturn = isSafeReturnPath(returnTo) ? returnTo : undefined;
  const [failure, setFailure] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);
  const started = useRef(-1);

  const begin = useEffectEvent(() => {
    signIn(safeReturn).catch((cause: unknown) => setFailure(errorMessage(cause)));
  });

  useEffect(() => {
    // один запуск на спробу (StrictMode у dev викликає ефект двічі)
    if (status !== 'anonymous' || started.current === attempt) return;
    started.current = attempt;
    begin();
  }, [status, attempt]);

  if (status === 'authenticated') return <Navigate to={postLoginPath(roles, safeReturn)} replace />;

  const problem = failure ?? (error ? error.message : null);
  const waiting = !problem;

  return (
    <section className="mx-auto grid max-w-4xl gap-8 py-6 md:grid-cols-[minmax(0,1fr)_minmax(0,22rem)] md:py-14">
      <div className="flex flex-col gap-3">
        <h1 className="text-headline font-semibold tracking-[-0.01em] text-primary">Вхід для викладачів і диспетчерів</h1>
        <p className="max-w-prose text-body text-secondary">
          Розклад, заміни й дзвінки відкриті для всіх без входу. Обліковий запис потрібен, щоб підтверджувати заміни,
          повідомляти про відсутність і керувати розкладом.
        </p>
        <p className="max-w-prose text-ui text-muted">
          Немає облікового запису? Викладач отримує запрошення від диспетчера й сам задає логін і пароль.
        </p>
      </div>
      <div className="flex flex-col gap-4 rounded-panel border border-default bg-surface p-5">
        <p className="flex items-center gap-2 text-heading font-semibold text-primary">
          <KeyRound aria-hidden="true" className="size-[1.125rem] text-accent" />
          Обліковий запис коледжу
        </p>
        <p className="text-ui text-secondary" aria-live="polite">
          {waiting ? 'Відкриваємо сторінку входу коледжу…' : 'Сторінка входу не відкрилася.'}
        </p>
        {problem ? (
          <p role="alert" className="rounded-control border border-cancel bg-danger-subtle px-3 py-2 text-caption text-danger">
            {problem} Перевірте, чи працює сервер входу, і спробуйте ще раз.
          </p>
        ) : null}
        <Button
          variant="primary"
          size="lg"
          loading={waiting}
          loadingLabel="Відкриваємо сторінку входу"
          iconStart={<LogIn aria-hidden="true" className="size-4" />}
          onClick={() => {
            setFailure(null);
            setAttempt((value) => value + 1);
          }}
        >
          {waiting ? 'Відкриваємо…' : 'Спробувати ще раз'}
        </Button>
      </div>
    </section>
  );
}
