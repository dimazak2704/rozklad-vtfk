import { describe, expect, it } from 'vitest';
import type { ChangeView } from '@/api/types';
import { changeRowText, ROOM_CHANGE_TITLE, sortByPair } from './change-rows';

const row = (patch: Partial<ChangeView>): ChangeView => ({
  pairLabel: '1',
  rowType: 'REPLACEMENT',
  from: { discipline: 'ФК', teacher: 'Феліксов' },
  to: { discipline: 'ФіА', teacher: 'Грабенко' },
  reasonText: null,
  room: '408',
  subgroupNote: null,
  ...patch,
});

describe('рядок публічних змін', () => {
  it('заміна: нове заняття головним, старе — закресленим', () => {
    expect(changeRowText(row({}))).toEqual({ title: 'ФіА, Грабенко', old: 'ФК, Феліксов' });
  });

  it('додаткова пара: позначку «ДОДАТКОВО» з розпорядження не показуємо як «було»', () => {
    const additional = row({ rowType: 'ADDITIONAL', from: { discipline: 'ДОДАТКОВО', teacher: null }, to: { discipline: 'БіЕ', teacher: 'Кучер' } });
    expect(changeRowText(additional)).toEqual({ title: 'БіЕ, Кучер', old: null });
    // та сама позначка в рядку іншого типу (старий імпорт) теж не «було»
    expect(changeRowText({ ...additional, rowType: 'REPLACEMENT' }).old).toBeNull();
  });

  it('зміна аудиторії без знімка заняття — «Пара за розкладом», а не «—»; зі знімком — саме заняття', () => {
    const bare = row({ rowType: 'ROOM_CHANGE', from: { discipline: null, teacher: null }, to: { discipline: null, teacher: null }, room: '404' });
    expect(changeRowText(bare)).toEqual({ title: ROOM_CHANGE_TITLE, old: null });
    expect(changeRowText({ ...bare, from: { discipline: 'БіЕ', teacher: 'Кучер' } })).toEqual({ title: 'БіЕ, Кучер', old: null });
  });

  it('скасування: головним — те, що було', () => {
    const cancel = row({ rowType: 'CANCELLATION', from: { discipline: 'ФіА', teacher: 'Грабенко' }, to: { discipline: 'Пари не буде', teacher: null } });
    expect(changeRowText(cancel)).toEqual({ title: 'ФіА, Грабенко', old: null });
  });

  it('рядки групи — за порядком пар, підгрупи після всієї групи, однакові — як у документі', () => {
    const items = [
      row({ pairLabel: '1' }),
      row({ pairLabel: '2', rowType: 'CANCELLATION' }),
      row({ pairLabel: '5', rowType: 'ADDITIONAL' }),
      row({ pairLabel: '4', rowType: 'ROOM_CHANGE', room: '404' }),
      row({ pairLabel: '3', subgroupNote: '2 підгрупа' }),
      row({ pairLabel: '3', subgroupNote: '1 підгрупа' }),
      row({ pairLabel: '1-4', rowType: 'BLOCK', room: 'a' }),
      row({ pairLabel: '1-4', rowType: 'BLOCK', room: 'b' }),
    ];
    const label = (i: ChangeView) => `${i.pairLabel}${i.subgroupNote ? `/${i.subgroupNote.charAt(0)}` : ''}${i.rowType === 'BLOCK' ? (i.room ?? '') : ''}`;
    expect(sortByPair(items).map(label)).toEqual(['1', '1-4a', '1-4b', '2', '3/1', '3/2', '4', '5']);
  });
});
