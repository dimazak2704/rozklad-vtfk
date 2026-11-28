import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { OrderDto } from '@/api/types';
import { renderWithProviders } from '@/test/render';
import { OrderRows } from './OrderRows';

const order = {
  items: [
    { id: 'i1', groupCode: '4КІ1', pairLabel: '2', rowType: 'REPLACEMENT', fromDiscipline: 'СТП', fromTeacher: 'Кренцін', reasonText: 'Зв.зам.', toDiscipline: 'НДКС', toTeacher: 'Непийвода', roomText: '6', changeId: null },
    { id: 'i2', groupCode: '1ТК1', pairLabel: '1', rowType: 'CANCELLATION', fromDiscipline: 'Ін. мова', fromTeacher: 'Крисак Л. (п.)', reasonText: 'Відп.', toDiscipline: null, toTeacher: null, roomText: null, changeId: null },
    { id: 'i3', groupCode: '1ОП2', pairLabel: '4', rowType: 'ADDITIONAL', fromDiscipline: null, fromTeacher: null, reasonText: null, toDiscipline: 'БЖ', toTeacher: 'Погосян', roomText: '403', changeId: null },
  ],
  roomChanges: [{ id: 'r1', groupCode: '2КІ1', pairLabel: '3', rowType: 'ROOM_CHANGE', fromDiscipline: null, fromTeacher: null, reasonText: null, toDiscipline: null, toTeacher: null, roomText: '304/309', changeId: null }],
  notes: ['Групи 4КІ навчаються дистанційно.'],
} as unknown as OrderDto;

describe('рядки розпорядження на телефоні', () => {
  it('кожен рядок — читабельним реченням: що було (закреслено), що стало, причина й аудиторія', () => {
    renderWithProviders(<OrderRows order={order} />);
    const rows = within(screen.getByRole('list', { name: 'Рядки розпорядження' })).getAllByRole('listitem');
    expect(rows).toHaveLength(3);
    expect(rows[0]).toHaveTextContent('4КІ1, 2 пара');
    expect(within(rows[0] as HTMLElement).getByText('СТП · Кренцін').tagName).toBe('S');
    expect(rows[0]).toHaveTextContent('НДКС · Непийвода');
    expect(rows[0]).toHaveTextContent('Зв.зам. · ауд. 6');
    expect(rows[1]).toHaveTextContent('пари не буде');
    expect(rows[2]).toHaveTextContent('Додатково: БЖ · Погосян');
    expect(screen.getByRole('region', { name: 'Заміна аудиторій' })).toHaveTextContent('2КІ1, 3 пара — ауд. 304/309');
    expect(screen.getByText('Групи 4КІ навчаються дистанційно.')).toBeInTheDocument();
  });

  it('заголовок «Заміна аудиторій» — другого рівня: на сторінці над ним лише h1', () => {
    renderWithProviders(<OrderRows order={order} />);
    expect(screen.getByRole('heading', { name: 'Заміна аудиторій' })).toHaveProperty('tagName', 'H2');
  });
});
