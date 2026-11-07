import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, type RenderOptions } from '@testing-library/react';
import type { ReactElement, ReactNode } from 'react';
import { MemoryRouter } from 'react-router';
import { TooltipProvider } from '@/components/ui/Tooltip';

interface Options extends Omit<RenderOptions, 'wrapper'> {
  /** Початкова адреса для MemoryRouter. */
  route?: string;
}

/** Рендер із провайдерами застосунку: роутер у памʼяті, свіжий QueryClient без повторів, підказки. */
export function renderWithProviders(ui: ReactElement, { route = '/', ...options }: Options = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>
        <TooltipProvider>
          <MemoryRouter initialEntries={[route]}>{children}</MemoryRouter>
        </TooltipProvider>
      </QueryClientProvider>
    );
  }
  return { queryClient, ...render(ui, { wrapper: Wrapper, ...options }) };
}
