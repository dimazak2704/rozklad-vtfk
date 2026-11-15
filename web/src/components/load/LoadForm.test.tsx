import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { CalendarSemester } from '@/api/calendar';
import { ACTIVE_SEMESTER } from '@/mocks/fixtures/calendar';
import { resetMockStores } from '@/mocks/store';
import { renderWithProviders } from '@/test/render';
import { LoadFormDialog } from './LoadForm';

const NB = ' ';

vi.setConfig({ testTimeout: 20_000 });
beforeEach(() => resetMockStores());

describe('форма навантаження', () => {
  it('у виборі викладача — «Прізвище І. Б.», як у таблиці навантаження', async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <LoadFormDialog semester={ACTIVE_SEMESTER as CalendarSemester} item={null} defaultGroupId={null} open onOpenChange={() => undefined} />,
      { route: '/admin/load' },
    );
    await user.click(await screen.findByRole('combobox', { name: 'Викладач' }));
    expect(await screen.findByRole('option', { name: `Стахова О.${NB}А.` })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: 'Стахова О.А.' })).not.toBeInTheDocument();
  });
});
