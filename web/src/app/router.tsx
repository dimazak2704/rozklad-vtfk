import { createBrowserRouter, Navigate, Outlet, type RouteObject } from 'react-router';
import { RoleGate } from '@/auth/RoleGate';
import { PublicLayout } from '@/layouts/PublicLayout';
import { RootLayout } from '@/layouts/RootLayout';
import { RouteErrorPage } from '@/pages/system/RouteErrorPage';
import { routesFor, type AppRoute } from './routes';

/** Запис реєстру → маршрут React Router з лінивим завантаженням сторінки. */
function toRouteObject(route: AppRoute): RouteObject {
  const lazy = async () => {
    const module = await route.page();
    return { Component: module.default };
  };
  const handle = { title: route.title, id: route.id };
  if (route.path === '/') return { index: true, handle, lazy };
  return { path: route.path, handle, lazy };
}

/**
 * «Такої сторінки немає» у своєму макеті: невідома адреса кабінету чи панелі лишає людину в її розділі
 * (бічна панель, пошук), а не викидає в публічну шапку з вкладками студента.
 */
function notFound(path: string): RouteObject {
  return {
    path,
    handle: { title: 'Сторінку не знайдено' },
    lazy: async () => ({ Component: (await import('@/pages/system/NotFoundPage')).default }),
  };
}

/**
 * Межа помилок сторінок усередині макета: помилка сторінки (зокрема частина коду, що не завантажилась
 * без мережі) показується на місці сторінки, а шапка й навігація макета лишаються. errorElement самого
 * макета ловить лише помилки макета.
 */
function withinLayout(children: RouteObject[]): RouteObject {
  return { errorElement: <RouteErrorPage />, children };
}

/**
 * Дерево маршрутів. Публічний макет вантажиться одразу (студент має побачити розклад якнайшвидше),
 * кабінет і панель диспетчера — окремими частинами коду лише після входу.
 */
export const routeTree: RouteObject[] = [
  {
    path: '/',
    element: <RootLayout />,
    errorElement: <RouteErrorPage />,
    hydrateFallbackElement: <div className="min-h-dvh bg-canvas" aria-busy="true" />,
    children: [
      {
        element: <PublicLayout />,
        errorElement: <RouteErrorPage />,
        children: [withinLayout([...routesFor('public').map(toRouteObject), notFound('*')])],
      },
      {
        element: (
          <RoleGate roles={['TEACHER', 'ADMIN']}>
            <Outlet />
          </RoleGate>
        ),
        children: [
          {
            lazy: async () => ({ Component: (await import('@/layouts/TeacherLayout')).TeacherLayout }),
            errorElement: <RouteErrorPage />,
            children: [withinLayout([...routesFor('teacher').map(toRouteObject), notFound('/me/*')])],
          },
        ],
      },
      {
        element: (
          <RoleGate roles={['ADMIN']}>
            <Outlet />
          </RoleGate>
        ),
        children: [
          {
            lazy: async () => ({ Component: (await import('@/layouts/AdminLayout')).AdminLayout }),
            errorElement: <RouteErrorPage />,
            children: [
              withinLayout([
                ...routesFor('admin').map(toRouteObject),
                { path: '/admin/catalog', element: <Navigate to="/admin/catalog/groups" replace /> },
                notFound('/admin/*'),
              ]),
            ],
          },
        ],
      },
      ...routesFor('bare').map(toRouteObject),
    ],
  },
];

export const router = createBrowserRouter(routeTree);

/**
 * Чекає, доки роутер завантажить код першої сторінки (ліниві маршрути) і буде готовий її показати. До того
 * на екрані лишається статична оболонка з index.html: якби React почав рендер раніше, він замінив би її
 * порожнім силуетом (hydrateFallbackElement), і людина бачила б мигання «шапка — порожньо — сторінка».
 */
export function whenRouterReady(target: Pick<typeof router, 'state' | 'subscribe'> = router): Promise<void> {
  if (target.state.initialized) return Promise.resolve();
  return new Promise((resolve) => {
    const unsubscribe = target.subscribe((state) => {
      if (!state.initialized) return;
      unsubscribe();
      resolve();
    });
  });
}
