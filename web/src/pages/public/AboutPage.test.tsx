import { screen, within } from '@testing-library/react';
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

describe('AboutPage: як читати розклад', () => {
  it('приклад зміни аудиторії називає стан один раз — словом у самій клітинці', () => {
    renderWithProviders(<AboutPage />);
    const legend = screen.getByRole('region', { name: 'Як читати розклад' });
    const sample = within(legend).getAllByRole('listitem').find((item) => item.textContent?.includes('стара → нова'))!;
    const cell = within(sample).getByRole('button');
    // слово стану — у клітинці; поза клітинкою його повторює лише пояснення праворуч
    expect(within(cell).getAllByText('Інша аудиторія')).toHaveLength(1);
    expect(within(sample).getAllByText(/Інша аудиторія/)).toHaveLength(2);
  });

  it('пояснює всі стани, які бачить студент: і перенесену пару, і рядки «Вичитано» та «Обʼєднання підгруп»', () => {
    renderWithProviders(<AboutPage />);
    const legend = screen.getByRole('region', { name: 'Як читати розклад' });
    const moved = within(legend).getAllByRole('listitem').find((item) => item.textContent?.includes('на інший час'))!;
    expect(within(moved).getAllByText(/Перенесено/).length).toBeGreaterThan(0);
    expect(legend).toHaveTextContent('Вичитано — години дисципліни закінчилися');
    expect(legend).toHaveTextContent('Обʼєднання підгруп — викладач однієї підгрупи');
  });
});
