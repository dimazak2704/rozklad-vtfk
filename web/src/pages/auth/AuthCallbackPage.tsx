import { useEffect, useState } from 'react';
import { useAuth } from 'react-oidc-context';
import { Navigate } from 'react-router';
import { openAfterLogin, postLoginPath, returnToFromState } from '@/auth/postLogin';
import { rolesFromAccessToken } from '@/auth/roles';
import { useCurrentUser } from '@/auth/useCurrentUser';
import { userManager } from '@/auth/userManager';
import { Button } from '@/components/ui/Button';
import { Spinner } from '@/components/ui/Spinner';

/**
 * Пояснення українською замість тексту бібліотеки (`No matching state found in storage` тощо).
 * Технічну причину не показуємо: людині вона нічого не каже.
 */
function callbackErrorText(error: Error): string {
  const message = error.message.toLowerCase();
  if (message.includes('state') || message.includes('code') || message.includes('expired')) {
    return 'Посилання входу застаріло або вже використане (наприклад, після кнопки «Назад» у браузері).';
  }
  if (message.includes('access_denied') || message.includes('login_required')) {
    return 'Сервер входу не підтвердив вхід.';
  }
  return 'Сервер входу не відповів або відхилив запит.';
}

/**
 * Повернення з Keycloak. Обмін коду на токени робить AuthProvider, а він же веде далі
 * (див. src/app/App.tsx). Тут — стан очікування і зрозуміла помилка. Стару адресу повернення
 * (кнопка «Назад» після входу) AuthProvider не може обробити вдруге й лишає користувача
 * «невідомим», хоча сесія в цій вкладці є: тоді відкриваємо сторінку за роллю, а не помилку.
 */
export default function AuthCallbackPage() {
  const { status, roles, error, signIn } = useCurrentUser();
  // сторінка, з якої почали вхід: AuthProvider веде туди сам, і ця сторінка не має перебити його своїм маршрутом
  const returnTo = returnToFromState(useAuth().user?.state);
  const [storedChecked, setStoredChecked] = useState(false);

  useEffect(() => {
    if (!error || status === 'authenticated') return;
    let active = true;
    userManager
      .getUser()
      .then((user) => {
        if (!active) return;
        if (user && !user.expired) openAfterLogin(postLoginPath(rolesFromAccessToken(user.access_token)));
        else setStoredChecked(true);
      })
      .catch(() => active && setStoredChecked(true));
    return () => {
      active = false;
    };
  }, [error, status]);

  if (status === 'authenticated') return <Navigate to={postLoginPath(roles, returnTo)} replace />;

  if (error && storedChecked) {
    return (
      <section role="alert" className="mx-auto flex min-h-dvh max-w-md flex-col justify-center gap-4 px-4">
        <h1 className="text-title font-semibold text-primary">Вхід не завершився</h1>
        <p className="text-body text-secondary">{callbackErrorText(error)} Почніть вхід ще раз — зазвичай це допомагає.</p>
        <div>
          <Button variant="primary" onClick={() => void signIn()}>
            Увійти ще раз
          </Button>
        </div>
      </section>
    );
  }

  return (
    <section role="status" aria-live="polite" className="flex min-h-dvh items-center justify-center gap-3 text-ui text-secondary">
      <Spinner />
      Завершуємо вхід…
    </section>
  );
}
