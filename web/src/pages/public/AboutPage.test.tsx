import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderWithProviders } from '@/test/render';
import AboutPage from './AboutPage';

describe('AboutPage: Telegram-бот', () => {
  it('не обіцяє кнопок одного режиму: у «Призначити й повідомити» бот дає «Добре», а не «Підтверджую»', () => {
    renderWithProviders(<AboutPage />);
    const bot = screen.getByRole('region', { name: /Telegram-бот/ });
    expect(bot).toHaveTextContent('Викладачам бот повідомляє про заміни, а відповісти можна однією кнопкою.');
    expect(bot).not.toHaveTextContent('Підтверджую');
  });
});
