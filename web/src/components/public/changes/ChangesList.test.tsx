import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { ChangeView } from '@/api/types';
import { renderWithProviders } from '@/test/render';
import { ChangesList } from './ChangesList';
import type { ChangeGroup } from './changes-filter';

const item = (patch: Partial<ChangeView>): ChangeView => ({
  pairLabel: '1',
  rowType: 'REPLACEMENT',
  from: { discipline: 'ФК', teacher: 'Феліксов' },
  to: { discipline: 'ФіА', teacher: 'Грабенко' },
  reasonText: null,
  room: '408',
  subgroupNote: null,
  orderNumber: 63,
  ...patch,
});

// 1ВЕ, четвер 19.11 на стенді: розділ змін аудиторій іде після замін, тож API віддає пари 1, 2, 5, 4
const g1ve = {
  group: { id: 'g', code: '1ВЕ', course: 1 },
  items: [
    item({}),
    item({
      pairLabel: '2',
      rowType: 'CANCELLATION',
      from: { discipline: 'ФіА', teacher: 'Грабенко' },
      to: { discipline: 'Пари не буде', teacher: null },
      room: null,
    }),
    item({ pairLabel: '5', rowType: 'ADDITIONAL', from: { discipline: 'ДОДАТКОВО', teacher: null }, to: { discipline: 'БіЕ', teacher: 'Кучер' }, room: '412' }),
    item({ pairLabel: '4', rowType: 'ROOM_CHANGE', from: { discipline: null, teacher: null }, to: { discipline: null, teacher: null }, room: '404' }),
  ],
} as unknown as ChangeGroup;

describe('ChangesList', () => {
  it('рядки групи — за парами; додаткова пара без «Замість», зміна аудиторії — «Пара за розкладом»', () => {
    const { container } = renderWithProviders(<ChangesList groups={[g1ve]} date="2026-11-19" myGroupIds={new Set()} mainOrderNumber={63} />);
    const rows = [...container.querySelectorAll('ol > li')] as HTMLElement[];
    expect(rows.map((row) => /(\d) пара/.exec(row.textContent ?? '')?.[1])).toEqual(['1', '2', '4', '5']);
    const [replacement, , room, additional] = rows as [HTMLElement, HTMLElement, HTMLElement, HTMLElement];
    expect(room).toHaveTextContent('Інша аудиторія');
    expect(room).toHaveTextContent('Пара за розкладом');
    expect(room).not.toHaveTextContent('—');
    expect(additional).toHaveTextContent('БіЕ, Кучер');
    expect(additional).not.toHaveTextContent(/Замість|ДОДАТКОВО/);
    // звичайна заміна старе заняття показує
    expect(replacement).toHaveTextContent('Замість: ФК, Феліксов');
    expect(screen.getByRole('link', { name: /1ВЕ/ })).toBeInTheDocument();
  });
});
