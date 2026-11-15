import { describe, expect, it } from 'vitest';
import type { ChangeOption } from '@/api/admin-daily';
import type { ChangeDto } from '@/api/types';
import { decisionSummary } from './format';
import { answerToast, buildFeed, optionNotes, optionTitle, otherDecliners, planSummary, rowChips } from './model';
import { gridTeacherName, withTeacherLabel, withTeacherLabels } from './teacher-label';

const NB = ' ';
const KOLESNYK = {
  id: 't-k',
  shortName: 'Колесник А.',
  displayName: 'Колесник',
};
const NEPYIVODA = {
  id: 't-n',
  shortName: 'Непийвода',
  displayName: 'Непийвода',
};
// як у панелі: імена з каталогу
const directory = new Map([
  ['t-k', 'Колесник А. І.'],
  ['t-n', 'Непийвода М. В.'],
]);
const label = (t: { id?: string; shortName: string }) => (t.id ? (directory.get(t.id) ?? gridTeacherName(t)) : gridTeacherName(t));

describe('одне написання викладача в панелі диспетчера', () => {
  it('без каталогу — ініціали з короткої форми з пробілами, без ініціалів — прізвище', () => {
    expect(gridTeacherName({ shortName: 'Стахова О.А.' })).toBe(`Стахова О.${NB}А.`);
    expect(gridTeacherName({ shortName: 'Непийвода' })).toBe('Непийвода');
  });

  it('назва варіанта від сервера («КМ — Колесник А.») — тим самим підписом', () => {
    const option = {
      kind: 'REPLACE_DISCIPLINE',
      title: 'КМ — Колесник А.',
      teacher: KOLESNYK,
      swapLesson: null,
    } as unknown as ChangeOption;
    expect(optionTitle(option, label)).toBe('КМ — Колесник А. І.');
    const merge = {
      kind: 'MERGE_SUBGROUPS',
      title: 'x',
      teacher: NEPYIVODA,
      swapLesson: null,
    } as unknown as ChangeOption;
    expect(optionTitle(merge, label)).toBe('Обʼєднати підгрупи — Непийвода М. В. веде обидві');
  });

  it('причини й застереження сервера — тим самим підписом, що й назва варіанта', () => {
    const kharchenko = { id: 't-h', shortName: 'Харченко О.А.' };
    const merge = {
      kind: 'MERGE_SUBGROUPS',
      title: 'x',
      teacher: kharchenko,
      swapLesson: { teacher: KOLESNYK },
      reasons: ['Харченко О.А. уже веде Ін.мова у 2-й підгрупі'],
      warnings: ['Колесник А. звільняється, Харченко О.А. веде дві підгрупи'],
    } as unknown as ChangeOption;
    expect(optionTitle(merge, label)).toBe(`Обʼєднати підгрупи — Харченко О.${NB}А. веде обидві`);
    expect(optionNotes(merge, label)).toEqual({
      reasons: [`Харченко О.${NB}А. уже веде Ін.мова у 2-й підгрупі`],
      warnings: [`Колесник А. І. звільняється, Харченко О.${NB}А. веде дві підгрупи`],
    });
    expect(rowChips(merge, label).map((chip) => chip.text)).toEqual([
      `Харченко О.${NB}А. уже веде Ін.мова у 2-й підгрупі`,
      `Колесник А. І. звільняється, Харченко О.${NB}А. веде дві підгрупи`,
    ]);
  });

  it('коротку форму міняємо лише окремим словом', () => {
    const krysak = { id: 't-kr', shortName: 'Крисак Л.' };
    expect(withTeacherLabels('Крисак Л.В. і Крисак Л. — різні люди', [krysak], () => 'Крисак Л. Б.')).toBe('Крисак Л.В. і Крисак Л. Б. — різні люди');
    expect(withTeacherLabels('Без імен', [null, undefined], label)).toBe('Без імен');
  });

  it('текст без короткої форми не чіпаємо', () => {
    expect(withTeacherLabel('Пари не буде', KOLESNYK, label)).toBe('Пари не буде');
    expect(withTeacherLabel('Перестановка', null, label)).toBe('Перестановка');
  });

  it('рішення, план дня й стрічка подій — з тим самим підписом', () => {
    const change = {
      id: 'c',
      kind: 'REPLACE_DISCIPLINE',
      status: 'CONFIRMED',
      approvalMode: 'AUTO',
      date: '2026-11-20',
      pairFrom: 4,
      group: { id: 'g', code: '4КІ1' },
      original: {
        discipline: { shortName: 'ДК' },
        teacher: NEPYIVODA,
        room: null,
      },
      new: { discipline: { shortName: 'КМ' }, teacher: KOLESNYK, room: null },
      history: [
        {
          at: '2026-11-20T07:00:00Z',
          actor: 'admin',
          from: null,
          to: 'CONFIRMED',
        },
      ],
    } as unknown as ChangeDto;
    expect(decisionSummary(change, label)).toBe('КМ — Колесник А. І.');
    // «4 пара (ДК)» не розривається між рядками на телефоні
    expect(buildFeed([change], [], [], '2026-11-20', label)[0]?.text).toBe(`Підтверджено: Колесник А. І. — 4КІ1, 4${NB}пара${NB}(ДК)`);
    const option = {
      kind: 'REPLACE_DISCIPLINE',
      teacher: KOLESNYK,
    } as unknown as ChangeOption;
    expect(planSummary([{ lesson: {} as never, option }], 'AUTO', label)).toEqual(['Призначимо: Колесник А. І.']);
  });

  it('відмову, названу в рядку пари, панель варіантів не повторює', () => {
    const declined = [
      { teacher: { id: 't-k' }, at: '1' },
      { teacher: { id: 't-n' }, at: '2' },
    ];
    const entry = {
      state: 'declined' as const,
      change: { new: { teacher: { id: 't-k' } } } as ChangeDto,
    };
    expect(otherDecliners(declined, entry)).toEqual({
      earlier: [declined[1]],
      lastShownInList: true,
    });
    expect(otherDecliners(declined, { state: 'uncovered', change: null })).toEqual({ earlier: declined, lastShownInList: false });
  });

  it('тост про відмову: для зміни без відсутності — дорога в «Інші зміни», а не «інший варіант»', () => {
    const base = { teacherAnswer: 'DECLINE', approvalMode: 'CONFIRM', group: { id: 'g', code: '1КІ1' }, pairFrom: 6 } as const;
    expect(answerToast({ ...base, status: 'DECLINED', absenceId: null }, 'Стахова О. А.')).toEqual({
      tone: 'warning',
      text: 'Стахова О. А. відмовився (1КІ1, 6 пара) — в «Інших змінах» оберіть іншого викладача або приберіть зміну',
    });
    expect(answerToast({ ...base, status: 'DECLINED', absenceId: 'abs' }, 'Стахова О. А.').text).toBe('Стахова О. А. відмовився (1КІ1, 6 пара) — оберіть інший варіант');
    expect(answerToast({ ...base, status: 'CONFIRMED', teacherAnswer: 'CONFIRM', absenceId: null }, 'Стахова О. А.').tone).toBe('success');
  });
});
