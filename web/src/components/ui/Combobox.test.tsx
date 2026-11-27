import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { Combobox, type ComboboxOption } from './Combobox';

const GROUPS: ComboboxOption[] = ['1КІ1', '1КІ2', '2КІ1', '3КІ1', '4КІ1', '4КІ2', '1ТК1'].map((code, i) => ({
  value: `g${i}`,
  label: code,
  description: `${code.charAt(0)} курс`,
}));

function Harness({ options = GROUPS, onSearchChange }: { options?: ComboboxOption[]; onSearchChange?: (q: string) => void }) {
  const [value, setValue] = useState<string | null>(null);
  return (
    <>
      <Combobox
        aria-label="Група"
        value={value}
        onValueChange={setValue}
        options={options}
        searchPlaceholder="Код групи"
        onSearchChange={onSearchChange}
      />
      <output data-testid="value">{value ?? ''}</output>
    </>
  );
}

async function openAndType(text: string) {
  const user = userEvent.setup();
  await user.click(screen.getByRole('combobox', { name: 'Група' }));
  await user.type(screen.getByPlaceholderText('Код групи'), text);
  return user;
}

function visibleLabels(): string[] {
  return within(screen.getByRole('listbox'))
    .queryAllByRole('option')
    .map((option) => option.querySelector('span > span')?.textContent ?? '');
}

describe('Combobox', () => {
  it('narrows the list to the typed group and Enter picks it, not the first group', async () => {
    render(<Harness />);
    const user = await openAndType('4КІ1');

    // регресія: раніше 1КІ1, 2КІ1, 3КІ1 лишалися як «одруки» в одну цифру, і Enter брав 1КІ1
    expect(visibleLabels()).toEqual(['4КІ1']);
    await user.keyboard('{Enter}');
    expect(screen.getByTestId('value')).toHaveTextContent('g4');
  });

  it('accepts Latin lookalikes and lower case', async () => {
    render(<Harness />);
    const user = await openAndType('4ki1');

    expect(visibleLabels()).toEqual(['4КІ1']);
    await user.keyboard('{Enter}');
    expect(screen.getByTestId('value')).toHaveTextContent('g4');
  });

  it('puts the exact match first and highlights it', async () => {
    const options = ['1КІ12', '1КІ1'].map((label, i) => ({ value: `o${i}`, label }));
    render(<Harness options={options} />);
    const user = await openAndType('1кі1');

    expect(visibleLabels()).toEqual(['1КІ1', '1КІ12']);
    await user.keyboard('{Enter}');
    expect(screen.getByTestId('value')).toHaveTextContent('o1');
  });

  it('shows the empty state and ignores Enter when nothing matches', async () => {
    render(<Harness />);
    const user = await openAndType('9ЮЮ');

    expect(visibleLabels()).toEqual([]);
    expect(screen.getByText(/Нічого не знайдено/)).toBeInTheDocument();
    await user.keyboard('{Enter}');
    expect(screen.getByTestId('value')).toBeEmptyDOMElement();
  });

  it('keeps server order and does not filter locally in remote mode', async () => {
    const onSearchChange = vi.fn();
    render(<Harness onSearchChange={onSearchChange} />);
    await openAndType('4КІ1');

    expect(onSearchChange).toHaveBeenLastCalledWith('4КІ1');
    expect(visibleLabels()).toEqual(GROUPS.map((g) => g.label));
  });
});
