import { screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useCommandStore } from '@/stores/commands';
import { renderWithProviders } from '@/test/render';
import { CommandPalette } from './CommandPalette';

// гість: сторінки панелі в палітрі не потрібні, а провайдер входу в тесті не піднімаємо
vi.mock('@/auth/useCurrentUser', () => ({ useCurrentUser: () => ({ hasRole: () => false }) }));

afterEach(() => useCommandStore.getState().setOpen(false));

describe('CommandPalette', () => {
  it('групує результати пошуку за типом: викладач — під «Викладачі», а не під загальним «Розклад»', async () => {
    useCommandStore.getState().setOpen(true, 'Кренц');
    renderWithProviders(<CommandPalette />);
    const teachers = await screen.findByRole('group', { name: 'Викладачі' }, { timeout: 3000 });
    expect(within(teachers).getByRole('option', { name: /Кренцін/ })).toBeInTheDocument();
    expect(screen.queryByRole('group', { name: 'Розклад' })).not.toBeInTheDocument();
  });

  it('повний код групи стоїть під «Групи»', async () => {
    useCommandStore.getState().setOpen(true, '4КІ1');
    renderWithProviders(<CommandPalette />);
    const groups = await screen.findByRole('group', { name: 'Групи' }, { timeout: 3000 });
    expect(within(groups).getAllByRole('option', { name: /4КІ1/ }).length).toBeGreaterThan(0);
  });
});
