import { screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { TimetableVersionDto } from '@/api/types';
import { renderWithProviders } from '@/test/render';
import { PublishDialog } from './PublishDialog';

const semester = { id: 's1', name: 'Осінь 2026', startsOn: '2026-09-01', endsOn: '2026-12-31' };

function version(hardConflicts: number): TimetableVersionDto {
  return {
    id: 'v1',
    semesterId: 's1',
    name: 'Чернетка',
    status: 'DRAFT',
    source: 'MANUAL',
    effectiveFrom: null,
    publishedAt: null,
    publishedBy: null,
    generationJobId: null,
    notes: null,
    createdAt: '2026-11-01T08:00:00Z',
    lessonCount: 10,
    hardConflicts,
    softWarnings: 0,
    metrics: null,
  } as TimetableVersionDto;
}

function renderDialog(hardConflicts: number) {
  renderWithProviders(
    <PublishDialog
      version={version(hardConflicts)}
      onOpenChange={() => {}}
      semester={semester}
      today="2026-11-10"
      hardConflicts={[]}
      onShowConflicts={() => {}}
    />,
  );
}

describe('PublishDialog: фокус при відкритті', () => {
  it('без конфліктів — у полі дати', async () => {
    renderDialog(0);
    await waitFor(() => expect(screen.getByRole('textbox', { name: /Діє з/ })).toHaveFocus());
  });

  it('з жорсткими конфліктами — на кнопці «Показати конфлікти в сітці»', async () => {
    renderDialog(2);
    await waitFor(() => expect(screen.getByRole('button', { name: 'Показати конфлікти в сітці' })).toHaveFocus());
  });
});
