import { describe, expect, it } from 'vitest';
import type { DataTableColumn } from '@/components/ui';
import { informativeColumns } from './columns';

interface Row {
  name: string;
  kind: string | null;
  size: number | null;
}

const columns: Array<DataTableColumn<Row>> = [
  { id: 'name', header: 'Назва', cell: (row) => row.name },
  { id: 'kind', header: 'Тип', cell: (row) => row.kind },
  { id: 'size', header: 'Розмір', cell: (row) => row.size },
];

describe('informativeColumns', () => {
  it('ховає колонки з однаковим або порожнім значенням у всіх рядках', () => {
    const rows: Row[] = [
      { name: '1', kind: 'Аудиторія', size: null },
      { name: '2', kind: 'Аудиторія', size: null },
    ];
    const ids = informativeColumns(columns, rows, { kind: (r) => r.kind, size: (r) => r.size }).map((c) => c.id);
    expect(ids).toEqual(['name']);
  });

  it('лишає колонку, щойно значення різняться, і не чіпає колонки без правила', () => {
    const rows: Row[] = [
      { name: '1', kind: 'Аудиторія', size: 30 },
      { name: '2', kind: 'Лабораторія', size: null },
    ];
    expect(informativeColumns(columns, rows, { kind: (r) => r.kind, size: (r) => r.size }).map((c) => c.id)).toEqual(['name', 'kind', 'size']);
  });

  it('на одному рядку чи без даних нічого не ховає', () => {
    expect(informativeColumns(columns, undefined, { kind: (r) => r.kind })).toHaveLength(3);
    expect(informativeColumns(columns, [{ name: '1', kind: null, size: null }], { kind: (r) => r.kind })).toHaveLength(3);
  });
});
