import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router';
import type { Role } from '@/api/types';
import { paths } from '@/app/routes';
import { Skeleton } from '@/components/ui/Skeleton';
import { ForbiddenPage } from '@/pages/system/ForbiddenPage';
import { useCurrentUser } from './useCurrentUser';

interface RoleGateProps {
  /** Достатньо однієї з ролей. */
  roles: readonly Role[];
  children: ReactNode;
}

/** Захист розділу: гостя — на вхід із поверненням, без потрібної ролі — пояснення замість 403. */
export function RoleGate({ roles, children }: RoleGateProps) {
  const { status, hasRole } = useCurrentUser();
  const location = useLocation();

  if (status === 'loading') {
    return (
      <div role="status" aria-live="polite" className="flex min-h-dvh flex-col">
        <span className="sr-only">Перевіряємо вхід…</span>
        <div className="h-14 bg-shell" />
        <div className="mx-auto flex w-full max-w-6xl flex-col gap-3 p-6">
          <Skeleton className="h-7 w-64" />
          <Skeleton className="h-4 w-96 max-w-full" />
          <Skeleton className="mt-4 h-40 w-full" />
        </div>
      </div>
    );
  }
  if (status === 'anonymous') {
    return <Navigate to={paths.login(location.pathname + location.search)} replace />;
  }
  if (!hasRole(...roles)) return <ForbiddenPage />;
  return children;
}
