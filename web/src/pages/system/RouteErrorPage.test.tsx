import { render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { RouteErrorPage } from './RouteErrorPage';

/** Сторінка, частина коду якої не завантажилась: так браузер повідомляє про невдалий динамічний import. */
function renderChunkFailure() {
  const router = createMemoryRouter(
    [
      {
        path: '/changes',
        errorElement: <RouteErrorPage />,
        lazy: () => Promise.reject(new TypeError('Failed to fetch dynamically imported module: http://localhost/assets/ChangesPage.js')),
      },
    ],
    { initialEntries: ['/changes'] },
  );
  return render(<RouterProvider router={router} />);
}

describe('RouteErrorPage', () => {
  it('без мережі пояснює, що сторінку ще не збережено, а не про нову версію', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false);
    renderChunkFailure();
    expect(await screen.findByRole('heading', { level: 1, name: 'Немає інтернету' })).toBeInTheDocument();
    expect(screen.getByText(/Ця сторінка ще не збережена на пристрої/)).toBeInTheDocument();
  });

  it('з мережею — частина коду зникла після оновлення сайту', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(true);
    renderChunkFailure();
    expect(await screen.findByRole('heading', { level: 1, name: 'Вийшла нова версія сайту' })).toBeInTheDocument();
  });
});
