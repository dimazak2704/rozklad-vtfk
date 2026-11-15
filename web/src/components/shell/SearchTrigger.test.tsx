import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderWithProviders } from '@/test/render';
import { SearchTrigger } from './SearchTrigger';

describe('SearchTrigger у шапці', () => {
  it('коли поле вузьке, підказка — «Пошук», а не «Група, виклада…»; повна — від ширини, де вміщається', () => {
    renderWithProviders(<SearchTrigger />);
    const button = screen.getByRole('button', {
      name: 'Пошук: Група, викладач, аудиторія',
    });
    // поле — контейнер: вибір підказки залежить від його ширини, а не від ширини вікна
    expect(button.className.split(/\s+/)).toContain('@container/search');
    const full = button.querySelector('[data-search-label="full"]')!;
    const short = button.querySelector('[data-search-label="short"]')!;
    expect(full).toHaveTextContent('Група, викладач, аудиторія');
    expect(full.className.split(/\s+/)).toEqual(expect.arrayContaining(['hidden', '@[16rem]/search:inline']));
    expect(short).toHaveTextContent('Пошук');
    expect(short.className.split(/\s+/)).toContain('@[16rem]/search:hidden');
  });
});
