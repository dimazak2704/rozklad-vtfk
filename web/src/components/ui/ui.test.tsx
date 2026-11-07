import { act, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { confirm } from '@/stores/confirm';
import { renderWithProviders } from '@/test/render';
import { ConfirmDialogHost } from './ConfirmDialog';
import { DataTable, type DataTableColumn } from './DataTable';
import { Field } from './Field';
import { IconButton } from './IconButton';
import { Input } from './Input';
import { NumberInput } from './NumberInput';

describe('IconButton', () => {
  it('має доступну назву і показує підказку при фокусі', async () => {
    const user = userEvent.setup();
    renderWithProviders(<IconButton label="Попередній день" icon={<span>‹</span>} shortcut="arrowleft" />);
    const button = screen.getByRole('button', { name: 'Попередній день' });
    await user.tab();
    expect(button).toHaveFocus();
    expect(await screen.findByRole('tooltip')).toHaveTextContent('Попередній день');
  });
});

describe('Field', () => {
  it('звʼязує підпис, підказку й помилку з полем', () => {
    renderWithProviders(
      <Field label="Логін" hint="Латиницею, без пробілів" error="Логін уже зайнятий" required>
        <Input />
      </Field>,
    );
    const input = screen.getByRole('textbox', { name: /Логін/ });
    expect(input).toBeRequired();
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(input).toHaveAccessibleDescription('Латиницею, без пробілів Логін уже зайнятий');
  });
});

describe('NumberInput', () => {
  function Harness() {
    const [value, setValue] = useState<number | null>(4);
    return (
      <Field label="Годин">
        <NumberInput value={value} onValueChange={setValue} min={0} max={8} />
      </Field>
    );
  }

  it('змінює значення стрілками і тримає межі', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);
    const input = screen.getByRole('spinbutton', { name: 'Годин' });
    await user.click(input);
    await user.keyboard('{ArrowUp}{ArrowUp}');
    expect(input).toHaveValue('6');
    await user.keyboard('{Shift>}{ArrowUp}{/Shift}');
    expect(input).toHaveValue('8');
    await user.clear(input);
    await user.type(input, '-3');
    await user.tab();
    expect(input).toHaveValue('0');
  });
});

interface Row {
  id: string;
  name: string;
  count: number;
}

const ROWS: Row[] = [
  { id: '1', name: 'Кренцін', count: 2 },
  { id: '2', name: 'Алексєєнко', count: 5 },
  { id: '3', name: 'Непийвода', count: 1 },
];
const COLUMNS: Array<DataTableColumn<Row>> = [
  { id: 'name', header: 'Викладач', cell: (r) => r.name, sortValue: (r) => r.name, rowHeader: true },
  { id: 'count', header: 'Замін', cell: (r) => r.count, sortValue: (r) => r.count, numeric: true },
];

describe('DataTable', () => {
  it('сортує за кліком у заголовку й позначає aria-sort', async () => {
    const user = userEvent.setup();
    renderWithProviders(<DataTable caption="Викладачі" columns={COLUMNS} rows={ROWS} getRowId={(r) => r.id} />);
    const names = () => screen.getAllByRole('rowheader').map((cell) => cell.textContent);
    expect(names()).toEqual(['Кренцін', 'Алексєєнко', 'Непийвода']);

    await user.click(screen.getByRole('button', { name: /Викладач/ }));
    expect(names()).toEqual(['Алексєєнко', 'Кренцін', 'Непийвода']);
    expect(screen.getByRole('columnheader', { name: /Викладач/ })).toHaveAttribute('aria-sort', 'ascending');

    await user.click(screen.getByRole('button', { name: /Замін/ }));
    await user.click(screen.getByRole('button', { name: /Замін/ }));
    expect(names()).toEqual(['Алексєєнко', 'Кренцін', 'Непийвода']);
    expect(screen.getByRole('columnheader', { name: /Замін/ })).toHaveAttribute('aria-sort', 'descending');
  });

  it('показує порожній стан і помилку з повтором', async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    const { rerender } = renderWithProviders(
      <DataTable caption="Викладачі" columns={COLUMNS} rows={[]} getRowId={(r) => r.id} />,
    );
    expect(screen.getByText('Записів немає')).toBeInTheDocument();

    rerender(
      <DataTable caption="Викладачі" columns={COLUMNS} rows={undefined} getRowId={(r) => r.id} error={new Error('boom')} onRetry={onRetry} />,
    );
    const alert = screen.getByRole('alert');
    await user.click(within(alert).getByRole('button', { name: 'Спробувати ще раз' }));
    expect(onRetry).toHaveBeenCalledOnce();
  });
});

describe('confirm()', () => {
  it('повертає true після підтвердження й показує назву обʼєкта', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ConfirmDialogHost />);
    let result: Promise<boolean> = Promise.resolve(false);
    act(() => {
      result = confirm({ title: 'Видалити викладача?', objectName: 'Кренцін М.Д.', tone: 'danger', confirmLabel: 'Видалити' });
    });
    const dialog = await screen.findByRole('alertdialog', { name: 'Видалити викладача?' });
    expect(within(dialog).getByText('Кренцін М.Д.')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Видалити' }));
    await expect(result).resolves.toBe(true);
  });

  it('повертає false, коли передумали', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ConfirmDialogHost />);
    let result: Promise<boolean> = Promise.resolve(true);
    act(() => {
      result = confirm({ title: 'Скасувати заміну?' });
    });
    const dialog = await screen.findByRole('alertdialog');
    await user.click(within(dialog).getByRole('button', { name: 'Скасувати' }));
    await expect(result).resolves.toBe(false);
  });
});
