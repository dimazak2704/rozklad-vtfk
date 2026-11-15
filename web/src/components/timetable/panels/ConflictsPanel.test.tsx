/**
 * Речення якості й панель конфліктів разом: «можна публікувати» — лише для чернетки, і «Жорстких конфліктів
 * немає» звучить на екрані один раз, а не і в реченні, і в панелі.
 */
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { TimetableConflict } from '@/api/timetable';
import type { MetricsDto } from '@/api/types';
import { renderWithProviders } from '@/test/render';
import { ConflictsPanel, type ConflictSeverityTab } from './ConflictsPanel';
import { MetricsBar } from './MetricsBar';

const METRICS: MetricsDto = { groupWindows: 3, teacherWindows: 0, brokenDays: 1, latePairs: 0, maxPairsPerDay: 4, perGroup: [] };

const WINDOW: TimetableConflict = { kind: 'WINDOW', severity: 'SOFT', message: '1ВЕ: вікно у вівторок', lessonIds: [], pair: 2 };

function Screen({ publishable, soft, onTabChange = vi.fn() }: { publishable: boolean; soft: TimetableConflict[]; onTabChange?: (tab: ConflictSeverityTab) => void }) {
  return (
    <>
      <MetricsBar
        versionId="v1"
        metrics={METRICS}
        baseline={null}
        hardConflicts={0}
        softWarnings={soft.length}
        loading={false}
        publishable={publishable}
        onShowConflicts={vi.fn()}
      />
      <ConflictsPanel
        hard={[]}
        soft={soft}
        loading={false}
        error={null}
        onRetry={vi.fn()}
        tab="HARD"
        onTabChange={onTabChange}
        onFocusConflict={vi.fn()}
      />
    </>
  );
}

describe('ConflictsPanel without hard conflicts', () => {
  it('does not suggest publishing a version that is already published', () => {
    renderWithProviders(<Screen publishable={false} soft={[WINDOW]} />);

    expect(screen.queryByText(/можна публікувати/)).toBeNull();
    expect(screen.getAllByText(/Жорстких конфліктів немає/)).toHaveLength(1);
    // попередження — теж порушення правил: панель не каже «порушень немає» поруч із «Попередження · 1»
    expect(screen.getByText(/^Накладок немає; 1\sпопередження\.$/)).toBeInTheDocument();
    expect(screen.queryByText(/порушень правил немає/)).toBeNull();
  });

  it('without warnings either, says there are no rule violations at all', () => {
    renderWithProviders(<Screen publishable={false} soft={[]} />);

    expect(screen.getByText('Накладок і порушень правил немає.')).toBeInTheDocument();
  });

  it('says a draft can be published once, in the quality sentence', () => {
    renderWithProviders(<Screen publishable soft={[WINDOW]} />);

    expect(screen.getAllByText(/можна публікувати/)).toHaveLength(1);
    expect(screen.getAllByText(/Жорстких конфліктів немає/)).toHaveLength(1);
  });

  it('offers the warnings tab as the next step when there are warnings', async () => {
    const onTabChange = vi.fn();
    renderWithProviders(<Screen publishable={false} soft={[WINDOW]} onTabChange={onTabChange} />);

    await userEvent.click(screen.getByRole('button', { name: 'Переглянути попередження' }));

    expect(onTabChange).toHaveBeenCalledWith('SOFT');
  });

  it('has no warnings button when there are no warnings', () => {
    renderWithProviders(<Screen publishable={false} soft={[]} />);

    expect(screen.queryByRole('button', { name: 'Переглянути попередження' })).toBeNull();
  });
});
