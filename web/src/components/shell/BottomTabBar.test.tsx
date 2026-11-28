import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderWithProviders } from '@/test/render';
import { BottomTabBar } from './BottomTabBar';

describe('нижні вкладки', () => {
  it('рівно одна вкладка позначена як поточна', () => {
    renderWithProviders(<BottomTabBar layout="public" />, { route: '/' });
    const links = screen.getAllByRole('link');
    expect(links.filter((link) => link.getAttribute('aria-current') === 'page')).toHaveLength(1);
  });

  it('кільце фокуса з клавіатури має суцільний стиль: outline-none у Tailwind v4 інакше ховає його', () => {
    renderWithProviders(<BottomTabBar layout="public" />, { route: '/' });
    for (const link of screen.getAllByRole('link')) {
      expect(link).toHaveClass('outline-none', 'focus-visible:outline-2', 'focus-visible:outline-solid');
    }
  });
});
