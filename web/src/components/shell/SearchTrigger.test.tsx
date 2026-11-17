import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderWithProviders } from '@/test/render';
import { SearchTrigger } from './SearchTrigger';

const classes = (element: Element | null) => element!.className.split(/\s+/);

describe('SearchTrigger у шапці', () => {
  it('коли поле вузьке, підказка — «Пошук», а не «Група, виклада…»; повна — від ширини, де вміщається', () => {
    renderWithProviders(<SearchTrigger />);
    const button = screen.getByRole('button', {
      name: 'Пошук: Група, викладач, аудиторія',
    });
    // поле — контейнер: вибір підказки залежить від його ширини, а не від ширини вікна
    expect(classes(button)).toContain('@container/search');
    const full = button.querySelector('[data-search-label="full"]');
    const short = button.querySelector('[data-search-label="short"]');
    expect(full).toHaveTextContent('Група, викладач, аудиторія');
    expect(classes(full)).toEqual(expect.arrayContaining(['hidden', '@[13rem]/search:inline']));
    expect(short).toHaveTextContent('Пошук');
    expect(classes(short)).toContain('@[13rem]/search:hidden');
  });

  it('Ctrl K ніколи не накриває підказку: є лише там, де вміщається поруч із нею', () => {
    renderWithProviders(<SearchTrigger />);
    const chip = screen.getByRole('button').querySelector('[aria-label]');
    // «Пошук» + Ctrl K від 7,5 rem; на 13–16,5 rem повна підказка без клавіші; ширше — обидві
    expect(classes(chip)).toEqual(
      expect.arrayContaining(['hidden', '@[7.5rem]/search:inline-flex', '@[13rem]/search:hidden', '@[16.5rem]/search:inline-flex']),
    );
  });

  it('без showShortcut клавіші немає', () => {
    renderWithProviders(<SearchTrigger showShortcut={false} />);
    expect(screen.getByRole('button').querySelector('kbd')).toBeNull();
  });
});
