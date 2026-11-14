import { screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ChangeDto } from '@/api/types';
import { renderWithProviders } from '@/test/render';
import { DecisionCard } from './DecisionCard';

const SUBSTITUTE = { id: 't-sub', shortName: 'Михальчук О.', displayName: 'Михальчук' };

// замінник привʼязав Telegram, бот працює: прохання доходить, тож «Нагадати» має сенс — поки пара не минула
vi.mock('@/api/telegram', () => ({
  useBindings: () => ({ data: [{ type: 'TEACHER', teacherId: 't-sub' }] }),
  useBotStatus: () => ({ data: { connected: true } }),
}));
vi.mock('@/api/admin-daily', () => ({ useUpdateChange: () => ({ isPending: false, mutateAsync: vi.fn() }) }));

const discipline = { id: 'd-1', shortName: 'КМ', name: 'Комп’ютерні мережі', color: null };

function change(patch: Partial<ChangeDto>): ChangeDto {
  return {
    id: 'c-1',
    kind: 'REPLACE_TEACHER',
    status: 'PENDING_CONFIRMATION',
    date: '2026-11-09',
    pairFrom: 3,
    pairTo: 3,
    group: { id: 'g', code: '4КІ1', course: 4 },
    subgroup: 0,
    original: { discipline, teacher: { id: 't-abs', shortName: 'Колесник А.І.', displayName: 'Колесник' }, room: null },
    new: { discipline, teacher: SUBSTITUTE, room: null },
    reasonCode: 'ABSENT',
    reasonText: null,
    absenceId: 'a-1',
    confirmDeadline: '2026-11-09T09:00:00Z',
    respondedAt: null,
    orderId: null,
    approvalMode: 'CONFIRM',
    originalLessonId: 'l-1',
    swapLessonId: null,
    swapFromPair: null,
    printedReason: 'Відп.',
    responseComment: null,
    remindedAt: null,
    createdBy: 'admin',
    createdAt: '2026-11-09T06:00:00Z',
    version: 1,
    history: [],
    ...patch,
  };
}

function renderCard(props: { change: ChangeDto; state: 'pending' | 'declined' }) {
  return renderWithProviders(
    <DecisionCard {...props} lessonEndsAt="12:20" busy={null} onResend={vi.fn()} onConfirm={vi.fn()} onCancel={vi.fn()} />,
  );
}

// 3 пара 11:00–12:20 за Києвом (UTC+2 у листопаді): кінець — 10:20Z
const DURING = new Date('2026-11-09T09:30:00Z');
const AFTER = new Date('2026-11-09T10:20:00Z');

beforeEach(() => vi.useFakeTimers({ toFake: ['Date'] }));
afterEach(() => vi.useRealTimers());

describe('DecisionCard: пара вже минула', () => {
  it('очікування до кінця пари: можна нагадати й підтвердити', () => {
    vi.setSystemTime(DURING);
    renderCard({ change: change({}), state: 'pending' });
    expect(screen.getByRole('button', { name: 'Нагадати в Telegram' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Підтвердити за викладача' })).toBeInTheDocument();
  });

  it('очікування після кінця пари: без «Нагадати» (core: 422 CHANGE_LESSON_OVER), «Підтвердити» лишається головною дією', () => {
    vi.setSystemTime(AFTER);
    renderCard({ change: change({}), state: 'pending' });
    expect(screen.queryByRole('button', { name: 'Нагадати в Telegram' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Підтвердити за викладача' })).toBeEnabled();
    expect(screen.getByRole('alert')).toHaveTextContent('Пара вже минула, а відповіді так і не було. Якщо пару провели, підтвердьте за викладача.');
    expect(screen.queryByText(/Нагадайте/)).not.toBeInTheDocument();
  });

  it('відмова до кінця пари: можна надіслати прохання ще раз', () => {
    vi.setSystemTime(DURING);
    renderCard({ change: change({ status: 'DECLINED', confirmDeadline: null }), state: 'declined' });
    expect(screen.getByRole('button', { name: 'Надіслати прохання ще раз' })).toBeInTheDocument();
  });

  it('відмова на минулу пару (й давніший день): не просимо ще раз, а пропонуємо позначити, що було', () => {
    vi.setSystemTime(AFTER);
    const { unmount } = renderCard({ change: change({ status: 'DECLINED', confirmDeadline: null }), state: 'declined' });
    expect(screen.queryByRole('button', { name: 'Надіслати прохання ще раз' })).not.toBeInTheDocument();
    expect(screen.getByText('Пара вже минула, тож просити ще раз запізно. Позначте нижче, що було насправді.')).toBeInTheDocument();
    unmount();

    vi.setSystemTime(new Date('2026-11-10T06:00:00Z'));
    renderCard({ change: change({ status: 'DECLINED', confirmDeadline: null }), state: 'declined' });
    expect(screen.queryByRole('button', { name: 'Надіслати прохання ще раз' })).not.toBeInTheDocument();
  });
});
