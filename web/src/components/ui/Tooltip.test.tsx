import { fireEvent, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { groupHintText } from '@/components/public/group-hint';
import { renderWithProviders } from '@/test/render';
import { TruncatedText } from './Tooltip';
import { isTruncated } from './Tooltip.helpers';

function sized(el: HTMLElement, { scroll, client }: { scroll: number; client: number }) {
  Object.defineProperty(el, 'scrollWidth', { configurable: true, value: scroll });
  Object.defineProperty(el, 'clientWidth', { configurable: true, value: client });
}

describe('TruncatedText', () => {
  it('показує повне значення, лише коли текст справді обрізано', async () => {
    renderWithProviders(<TruncatedText className="truncate">Надійність, діагностика та експлуатація</TruncatedText>);
    const text = screen.getByText('Надійність, діагностика та експлуатація');
    sized(text, { scroll: 400, client: 120 });
    fireEvent.focus(text);
    expect(await screen.findByRole('tooltip')).toHaveTextContent('Надійність, діагностика та експлуатація');
  });

  it('текст уміщається — підказки немає', () => {
    renderWithProviders(<TruncatedText className="truncate">КМ</TruncatedText>);
    const text = screen.getByText('КМ');
    sized(text, { scroll: 30, client: 120 });
    fireEvent.focus(text);
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument();
  });

  it('isTruncated: ширина або висота вмісту більша за видиму', () => {
    const el = document.createElement('span');
    sized(el, { scroll: 101, client: 100 });
    expect(isTruncated(el)).toBe(false);
    sized(el, { scroll: 140, client: 100 });
    expect(isTruncated(el)).toBe(true);
    expect(isTruncated(null)).toBe(false);
  });
});

describe('GroupHint', () => {
  it('називає групу повністю: спеціальність і курс', () => {
    expect(groupHintText({ course: 4, specialty: { letters: 'КІ', name: 'Компʼютерна інженерія' }, department: null })).toBe(
      'Компʼютерна інженерія · 4 курс',
    );
    // назва спеціальності збігається з літерами — беремо відділення, а якщо й його немає — лише курс
    expect(groupHintText({ course: 2, specialty: { letters: 'ЕА', name: 'ЕА' }, department: null })).toBe('2 курс');
  });
});
