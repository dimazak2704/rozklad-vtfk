import { fireEvent, screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { apiPath } from '@/mocks/kit';
import { server } from '@/mocks/server';
import { renderWithProviders } from '@/test/render';
import { TeacherStats } from './TeacherStats';

const NB = ' ';

describe('облік годин: заміни викладачів', () => {
  it('викладача пише так само, як інші списки адмінки: «Прізвище І. Б.»', async () => {
    const row = {
      teacher: { id: 't-stats', shortName: 'Стахова О.А.', displayName: 'Стахова' },
      substitutionsCount: 2,
      substitutionsHours: 4,
      missedCount: 0,
    };
    server.use(http.get(apiPath('/admin/accounting/teachers'), () => HttpResponse.json([row])));
    renderWithProviders(<TeacherStats month="2026-11" currentMonth="2026-11" onMonthChange={() => undefined} />);
    const table = await screen.findByRole('table');
    expect(await within(table).findByRole('link', { name: `Стахова О.${NB}А.` })).toBeInTheDocument();
    expect(within(table).queryByText('Стахова О.А.')).not.toBeInTheDocument();
  });

  it('імʼя веде з «Обліку» на публічний розклад викладача — про це каже підказка', async () => {
    const row = {
      teacher: { id: 't-stats', shortName: 'Стахова О.А.', displayName: 'Стахова' },
      substitutionsCount: 2,
      substitutionsHours: 4,
      missedCount: 0,
    };
    server.use(http.get(apiPath('/admin/accounting/teachers'), () => HttpResponse.json([row])));
    renderWithProviders(<TeacherStats month="2026-11" currentMonth="2026-11" onMonthChange={() => undefined} />);
    const link = await within(await screen.findByRole('table')).findByRole('link', { name: `Стахова О.${NB}А.` });
    expect(link).toHaveAttribute('href', '/t/t-stats');
    fireEvent.focus(link);
    expect(await screen.findByRole('tooltip')).toHaveTextContent('Розклад викладача');
  });
});
