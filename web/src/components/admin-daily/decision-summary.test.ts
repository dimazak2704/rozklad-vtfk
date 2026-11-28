import { describe, expect, it } from 'vitest';
import type { ChangeDto } from '@/api/types';
import { decidedMessage } from './changes/useDecisionActions';
import { decisionSummary, NBSP } from './format';

const room = (name: string) => ({ id: name, name, type: 'CLASSROOM' });
const change = (patch: Partial<ChangeDto>): ChangeDto =>
  ({
    id: 'c1',
    kind: 'SWAP',
    status: 'CONFIRMED',
    approvalMode: 'AUTO',
    date: '2026-11-19',
    pairFrom: 1,
    pairTo: 1,
    swapFromPair: 2,
    group: { id: 'g', code: '1ВЕ' },
    original: { discipline: { shortName: 'ФК' }, teacher: null, room: room('406') },
    new: { discipline: { shortName: 'ФіА' }, teacher: null, room: room('406') },
    history: [],
    ...patch,
  }) as unknown as ChangeDto;

describe('слова рішення — ті самі, що на сторінці групи й на «Замінах»', () => {
  it('перенесена пара — «Перенесено з 2-ї пари», а не «Переставлено»', () => {
    expect(decisionSummary(change({}))).toBe(`Перенесено з 2-ї${NBSP}пари: ФіА`);
    expect(decisionSummary(change({ swapFromPair: null }))).toBe('Перенесено: ФіА');
    expect(decidedMessage(change({}), '1ВЕ, 1 пара', () => '')).toBe('Перенесено пару — 1ВЕ, 1 пара');
  });

  it('зміна аудиторії — «Інша аудиторія: 406 → 404»', () => {
    const roomChange = change({ kind: 'ROOM_CHANGE', swapFromPair: null, new: { discipline: null, teacher: null, room: room('404') } as ChangeDto['new'] });
    expect(decisionSummary(roomChange)).toBe('Інша аудиторія: 406 → 404');
    expect(decisionSummary({ ...roomChange, original: { ...roomChange.original, room: null } })).toBe('Інша аудиторія: 404');
    expect(decisionSummary({ ...roomChange, new: { ...roomChange.new, room: null } })).toBe('Інша аудиторія');
  });
});
