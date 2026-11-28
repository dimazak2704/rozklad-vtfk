import { describe, expect, it } from 'vitest';
import type { ChangeView } from '@/api/types';
import { changeRowText, movedPairHints, ROOM_CHANGE_TITLE, sortByPair } from './change-rows';

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
    expect(changeRowText(row({}))).toEqual({ title: 'ФіА, Грабенко', old: 'ФК, Феліксов', oldRoom: null });
  });

  it('додаткова пара: позначку «ДОДАТКОВО» з розпорядження не показуємо як «було»', () => {
    const additional = row({ rowType: 'ADDITIONAL', from: { discipline: 'ДОДАТКОВО', teacher: null }, to: { discipline: 'БіЕ', teacher: 'Кучер' } });
    expect(changeRowText(additional)).toEqual({ title: 'БіЕ, Кучер', old: null, oldRoom: null });
    // та сама позначка в рядку іншого типу (старий імпорт) теж не «було»
    expect(changeRowText({ ...additional, rowType: 'REPLACEMENT' }).old).toBeNull();
  });

  it('зміна аудиторії без знімка заняття — «Пара за розкладом», а не «—»; зі знімком — саме заняття', () => {
    const bare = row({ rowType: 'ROOM_CHANGE', from: { discipline: null, teacher: null }, to: { discipline: null, teacher: null }, room: '404' });
    expect(changeRowText(bare)).toEqual({ title: ROOM_CHANGE_TITLE, old: null, oldRoom: null });
    expect(changeRowText({ ...bare, from: { discipline: 'БіЕ', teacher: 'Кучер' } })).toEqual({ title: 'БіЕ, Кучер', old: null, oldRoom: null });
    // core називає заняття й стару аудиторію: рядок читається як на сторінці групи
    expect(changeRowText({ ...bare, from: { discipline: 'БіЕ', teacher: 'Кучер' }, previousRoom: '406' })).toEqual({ title: 'БіЕ, Кучер', old: null, oldRoom: '406' });
    expect(changeRowText({ ...bare, previousRoom: '404' }).oldRoom).toBeNull();
  });

  it('скасування: головним — те, що було', () => {
    const cancel = row({ rowType: 'CANCELLATION', from: { discipline: 'ФіА', teacher: 'Грабенко' }, to: { discipline: 'Пари не буде', teacher: null } });
    expect(changeRowText(cancel)).toEqual({ title: 'ФіА, Грабенко', old: null, oldRoom: null });
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

  it('перенесена пара: скасування й заміна того самого заняття підказують одна на одну', () => {
    const placed = row({ pairLabel: '1' });
    const vacated = row({ pairLabel: '2', rowType: 'CANCELLATION', from: { discipline: 'ФіА', teacher: 'Грабенко' }, to: { discipline: 'Пари не буде', teacher: null } });
    const other = row({ pairLabel: '4', rowType: 'CANCELLATION', from: { discipline: 'ЕУІТ', teacher: 'Щегоцька' } });
    const hints = movedPairHints([placed, vacated, other]);
    expect(hints.get(placed)).toBe('перенесено з 2-ї пари');
    expect(hints.get(vacated)).toBe('перенесено на 1-шу пару');
    expect(hints.has(other)).toBe(false);
  });

  it('перенесення не вгадуємо: дві заміни того самого заняття чи інша підгрупа — без підказки', () => {
    const vacated = row({ pairLabel: '3', rowType: 'CANCELLATION', from: { discipline: 'ФіА', teacher: 'Грабенко' } });
    expect(movedPairHints([row({ pairLabel: '1' }), row({ pairLabel: '2' }), vacated]).size).toBe(0);
    expect(movedPairHints([row({ pairLabel: '1', subgroupNote: '1 підгрупа' }), vacated]).size).toBe(0);
    expect(movedPairHints([row({ pairLabel: '1-2' }), vacated]).size).toBe(0);
  });
});
