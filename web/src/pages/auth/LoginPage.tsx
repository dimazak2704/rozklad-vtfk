import { LogIn } from 'lucide-react';
import { useEffect, useEffectEvent, useRef, useState } from 'react';
import { Navigate, useSearchParams } from 'react-router';
import { isSilentRenewError, loginErrorText } from '@/auth/authErrors';
import { isSafeReturnPath, postLoginPath } from '@/auth/postLogin';
import { clearRejectedToken } from '@/auth/rejectedToken';
import { useCurrentUser } from '@/auth/useCurrentUser';
import { BrandMark } from '@/components/shell/Wordmark';
import { Button } from '@/components/ui/Button';
import { AuthTransition } from './AuthTransition';
import { useSlowTransition } from './useSlowTransition';

/**
 * Вхід для викладачів і диспетчерів (UI.md: «/login → сервіс входу»). Сторінка лише готує PKCE і одразу веде
 * на сторінку входу коледжу /auth/login, тож показує мить переходу: знак, рядок і кільце, без великої розмітки.
 * Якщо перехід затягнувся (понад 3 с) — пропонує спробувати ще раз; якщо сервер входу недоступний — пояснює.
 * Після повернення диспетчер потрапляє в /admin, викладач — у /me, або на сторінку, з якої прийшли.
 */
export default function LoginPage() {
  const { status, roles, signIn, error } = useCurrentUser();
  const [params] = useSearchParams();
  const returnTo = params.get('returnTo');
  const safeReturn = isSafeReturnPath(returnTo) ? returnTo : undefined;
  // сервер двічі поспіль не прийняв вхід: не відкриваємо сторінку входу по колу, а пояснюємо і чекаємо кнопки
  const rejected = params.get('rejected') === '1';
  const [failure, setFailure] = useState<string | null>(
    rejected ? 'Сервер не прийняв ваш вхід навіть після повторного входу. Можливо, змінилися налаштування входу коледжу.' : null,
  );
  const [attempt, setAttempt] = useState(0);
  const started = useRef(rejected ? 0 : -1);

  // поки сторінка входу не відкрилася, а помилки немає — чекаємо; затягнулося — пропонуємо ще раз
  const slow = useSlowTransition(!failure && status !== 'authenticated', attempt);

  const begin = useEffectEvent(() => {
    signIn(safeReturn).catch((cause: unknown) => setFailure(loginErrorText(cause)));
  });

  useEffect(() => {
    // один запуск на спробу (StrictMode у dev викликає ефект двічі)
    if (status !== 'anonymous' || started.current === attempt) return;
    started.current = attempt;
    begin();
  }, [status, attempt]);

  if (status === 'authenticated') return <Navigate to={postLoginPath(roles, safeReturn)} replace />;

  // збій фонового тихого оновлення — не причина показувати помилку на сторінці входу
  const problem = failure ?? (error && !isSilentRenewError(error) ? loginErrorText(error) : null);
  const retry = () => {
    clearRejectedToken();
    setFailure(null);
    setAttempt((value) => value + 1);
  };

  if (!problem) {
    return (
      <AuthTransition
        text="Відкриваємо сторінку входу…"
        slow={
          slow ? (
            <>
              <p className="text-caption text-muted">Сторінка входу відкривається довше, ніж зазвичай.</p>
              <Button variant="secondary" size="sm" iconStart={<LogIn aria-hidden="true" className="size-4" />} onClick={retry}>
                Спробувати ще раз
              </Button>
            </>
          ) : null
        }
      />
    );
  }

  return (
    <section className="mx-auto flex min-h-[min(60dvh,28rem)] w-full max-w-sm flex-col items-center justify-center gap-4 px-4 text-center">
      <BrandMark size={40} />
      <h1 className="text-title font-semibold text-primary">Сторінка входу не відкрилася</h1>
      <p role="alert" className="text-ui text-balance text-secondary">
        {problem} Перевірте, чи працює сервер входу, і спробуйте ще раз.
      </p>
      <Button variant="primary" iconStart={<LogIn aria-hidden="true" className="size-4" />} onClick={retry}>
        Спробувати ще раз
      </Button>
      <p className="text-caption text-muted">Розклад, заміни й дзвінки відкриті для всіх без входу.</p>
    </section>
  );
}
