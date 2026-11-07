import { QueryClientProvider } from '@tanstack/react-query';
import type { User } from 'oidc-client-ts';
import { lazy, Suspense } from 'react';
import { AuthProvider } from 'react-oidc-context';
import { RouterProvider } from 'react-router';
import { queryClient } from '@/api/queryClient';
import { postLoginPath, returnToFromState } from '@/auth/postLogin';
import { rolesFromAccessToken } from '@/auth/roles';
import { userManager } from '@/auth/userManager';
import { TooltipProvider } from '@/components/ui/Tooltip';
import { router } from './router';

// Панель TanStack Query — лише в dev і лише за бажанням (VITE_QUERY_DEVTOOLS=1), щоб не заважала на скриншотах
const QueryDevtools = import.meta.env.DEV && import.meta.env.VITE_QUERY_DEVTOOLS === '1'
  ? lazy(() => import('@tanstack/react-query-devtools').then((m) => ({ default: m.ReactQueryDevtools })))
  : () => null;

/** Після повернення з Keycloak: прибираємо code/state з адреси і ведемо за роллю. */
function handleSigninCallback(user: User | undefined): void {
  const roles = rolesFromAccessToken(user?.access_token);
  const target = postLoginPath(roles, returnToFromState(user?.state));
  void router.navigate(target, { replace: true });
}

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider userManager={userManager} onSigninCallback={handleSigninCallback}>
        <TooltipProvider>
          <RouterProvider router={router} />
        </TooltipProvider>
      </AuthProvider>
      <Suspense fallback={null}>
        <QueryDevtools buttonPosition="bottom-left" />
      </Suspense>
    </QueryClientProvider>
  );
}
