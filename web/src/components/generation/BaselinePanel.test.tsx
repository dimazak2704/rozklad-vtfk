import { screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { GenerationJobDto, TimetableVersionDto } from '@/api/types';
import { renderWithProviders } from '@/test/render';
import { BaselinePanel } from './BaselinePanel';

const jobs = vi.hoisted(() => ({ data: [] as Array<Partial<GenerationJobDto>> }));
vi.mock('@/api/generation', () => ({ useGenerationJobs: () => ({ data: jobs.data }) }));

const version = { id: 'v1', semesterId: 's', name: 'Опублікований', hardConflicts: 0, metrics: null } as unknown as TimetableVersionDto;

describe('стан базової версії до запуску', () => {
  beforeEach(() => {
    jobs.data = [];
  });

  it('база без конфліктів редактора, але з жорсткими порушеннями генератора — не «Конфліктів і вікон немає»', () => {
    jobs.data = [{ baseVersionId: 'v1', baseHardScore: -2, createdAt: '2026-11-16T10:00:00Z' }];
    renderWithProviders(<BaselinePanel version={version} />);

    const sentence = screen.getByText(/за правилами генератора тут 2\s+жорсткі порушення/);
    expect(sentence).toHaveClass('text-cancel');
    expect(screen.queryByText('Конфліктів і вікон немає.')).not.toBeInTheDocument();
  });

  it('без оцінки бази — як раніше, спокійним кольором', () => {
    renderWithProviders(<BaselinePanel version={version} />);

    expect(screen.getByText('Конфліктів і вікон немає.')).toHaveClass('text-secondary');
  });
});
