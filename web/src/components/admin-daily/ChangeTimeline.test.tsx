import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { ChangeDto, ChangeHistoryEntry } from '@/api/types';
import { formatTeacherName } from '@/lib/teacher-name';
import { renderWithProviders } from '@/test/render';
import { ChangeTimeline } from './ChangeTimeline';
import { historyActor } from './history-actor';
import type { TeacherLabel } from './teacher-label';

const krentsin = { id: 't-k', shortName: 'Кренцін М.Д.', displayName: 'Кренцін' };
const ordynskyi = { id: 't-o', shortName: 'Ординський В.П.', displayName: 'Ординський' };
const parties = { original: { discipline: null, teacher: ordynskyi, room: null }, new: { discipline: null, teacher: krentsin, room: null } };
const name: TeacherLabel = (teacher) => formatTeacherName(teacher, 'grid');

const entry = (patch: Partial<ChangeHistoryEntry>): ChangeHistoryEntry => ({
  at: '2026-11-20T07:00:00Z',
  actor: 'admin',
  from: 'DRAFT',
  to: 'PENDING_CONFIRMATION',
  comment: null,
  ...patch,
});

describe('хто діяв в історії рішення', () => {
  it('логін диспетчера — «Диспетчер», логін викладача у відповіді з кабінету — його імʼя', () => {
    expect(historyActor(entry({}), parties, name)).toEqual({ label: 'Диспетчер', teacher: false });
    expect(historyActor(entry({ actor: 'krentsin', from: 'PENDING_CONFIRMATION', to: 'DECLINED', comment: 'Не встигаю' }), parties, name)).toEqual({
      label: name(krentsin),
      teacher: true,
    });
    // диспетчер підтвердив сам — це не відповідь викладача
    expect(
      historyActor(entry({ actor: 'admin', from: 'PENDING_CONFIRMATION', to: 'CONFIRMED', comment: 'Підтверджено диспетчером' }), parties, name).label,
    ).toBe('Диспетчер');
  });

  it('відповідь з Telegram — імʼя викладача з позначкою; бот — «Telegram-бот»', () => {
    expect(historyActor(entry({ actor: 'Кренцін М.Д. (Telegram)', to: 'CONFIRMED' }), parties, name)).toEqual({
      label: `${name(krentsin)} (Telegram)`,
      teacher: true,
    });
    expect(historyActor(entry({ actor: 'telegram-bot' }), parties, name).label).toBe('Telegram-бот');
  });

  it('слова викладача — у лапках, навіть коли core записав його логін', () => {
    const change = {
      ...parties,
      history: [
        entry({ actor: 'admin', comment: 'Запит надіслано' }),
        entry({ actor: 'krentsin', from: 'PENDING_CONFIRMATION', to: 'DECLINED', comment: 'Не встигаю' }),
      ],
    } as unknown as ChangeDto;
    renderWithProviders(<ChangeTimeline change={change} />);
    const steps = within(screen.getByRole('list', { name: 'Історія рішення' })).getAllByRole('listitem');
    expect(steps[0]).toHaveTextContent('Диспетчер · Запит надіслано');
    expect(steps[1]).toHaveTextContent(/Кренцін\sМ\.\sД\. · «Не встигаю»/);
    expect(screen.queryByText(/krentsin|admin/)).not.toBeInTheDocument();
  });
});
