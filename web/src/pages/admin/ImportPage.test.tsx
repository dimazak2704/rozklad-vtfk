import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { IMPORT_KIND_HINT, IMPORT_KIND_LABEL } from '@/components/import/labels';
import { renderWithProviders } from '@/test/render';
import ImportPage from './ImportPage';

describe('ImportPage: довідка «Що можна імпортувати?»', () => {
  it('називає всі види, які приймає вибір файлу, разом із графіком освітнього процесу', async () => {
    renderWithProviders(<ImportPage />, { route: '/admin/import' });
    const help = await screen.findByRole('complementary', { name: 'Довідка про імпорт' });
    const terms = within(help)
      .getAllByRole('term')
      .map((term) => term.textContent);
    expect(terms).toEqual(['Розклад (Excel)', 'Графік освітнього процесу', 'Програма дисципліни', 'Навчальний план', 'Розпорядження']);
    expect(within(help).getByText(IMPORT_KIND_HINT.ACADEMIC_CALENDAR_XLSX)).toBeInTheDocument();
    expect(within(help).getByText(IMPORT_KIND_LABEL.ACADEMIC_CALENDAR_XLSX).tagName).toBe('DT');
  });
});
