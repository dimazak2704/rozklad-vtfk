import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderWithProviders } from '@/test/render';
import SettingsPage from './SettingsPage';

describe('сторінка «Налаштування»', () => {
  it('вступ — один абзац: крапка тримається посилання, а не стоїть окремим елементом рядка', () => {
    renderWithProviders(<SettingsPage />, { route: '/admin/settings' });
    const link = screen.getByRole('link', { name: 'у «Сповіщеннях»' });
    expect(link.parentElement).toHaveTextContent(/^у «Сповіщеннях»\.$/);
    expect(link.parentElement).toHaveClass('whitespace-nowrap');
    expect(link.parentElement?.parentElement).toHaveTextContent(/^Зміни діють одразу після збереження\./);
  });
});
