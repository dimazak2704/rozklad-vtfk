import { useAuth } from 'react-oidc-context';
import { Navigate } from 'react-router';
import { postLoginPath, returnToFromState } from '@/auth/postLogin';
import { useCurrentUser } from '@/auth/useCurrentUser';
import { Button } from '@/components/ui/Button';
import { Spinner } from '@/components/ui/Spinner';

/**
 * Повернення з Keycloak. Обмін коду на токени робить AuthProvider, а він же веде далі
 * (див. src/app/App.tsx). Тут — лише стан очікування і зрозуміла помилка.
 */
export default function AuthCallbackPage() {
  const { status, roles, error, signIn } = useCurrentUser();
  // сторінка, з якої почали вхід: AuthProvider веде туди сам, і ця сторінка не має перебити його своїм маршрутом
  const returnTo = returnToFromState(useAuth().user?.state);

  if (error) {
    return (
      <section role="alert" className="mx-auto flex min-h-dvh max-w-md flex-col justify-center gap-4 px-4">
        <h1 className="text-title font-semibold text-primary">Вхід не завершився</h1>
        <p className="text-body text-secondary">
          Сервер входу повернув помилку: {error.message}. Почніть вхід ще раз — зазвичай це допомагає.
        </p>
        <div>
          <Button variant="primary" onClick={() => void signIn()}>
            Увійти ще раз
          </Button>
        </div>
      </section>
    );
  }

  // якщо AuthProvider уже обробив повернення, а навігація ще не відбулася (сторінка призначення ще вантажиться)
  if (status === 'authenticated') return <Navigate to={postLoginPath(roles, returnTo)} replace />;

  return (
    <section role="status" aria-live="polite" className="flex min-h-dvh items-center justify-center gap-3 text-ui text-secondary">
      <Spinner />
      Завершуємо вхід…
    </section>
  );
}
