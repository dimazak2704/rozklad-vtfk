import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { GenerationJobDto } from '@/api/types';
import { renderWithProviders } from '@/test/render';
import { JobHistory } from './JobHistory';

const job = (id: string, patch: Partial<GenerationJobDto>): GenerationJobDto => ({
  id,
  semesterId: 's',
  status: 'SOLVED',
  createdAt: '2026-11-20T10:00:00Z',
  startedAt: '2026-11-20T10:00:00Z',
  finishedAt: '2026-11-20T10:01:00Z',
  bestScore: '0hard/-18soft',
  progress: [],
  resultVersionId: 'v2',
  baseVersionId: 'v1',
  timeLimitSeconds: null,
  analysis: null,
  metricsBefore: null,
  metricsAfter: null,
  error: null,
  requestedBy: null,
  ...patch,
});

describe('історія запусків: зупинені вручну', () => {
  it('«Зупинено» — як заголовок панелі, без «· зупинено вручну»; сама дійшла до кінця — «Готово»', () => {
    renderWithProviders(
      <JobHistory
        jobs={[job('a', { stopReason: 'STOPPED' }), job('b', { stopReason: 'NO_IMPROVEMENT' })]}
        selectedId={null}
        onSelect={() => undefined}
      />,
    );
    const [stopped, finished] = screen.getAllByRole('button');
    expect(stopped).toHaveTextContent(/^Зупинено/);
    expect(stopped).not.toHaveTextContent('зупинено вручну');
    expect(finished).toHaveTextContent(/^Готово.*зупинилась сама/);
  });

  it('зупинена з жорсткими порушеннями — «З конфліктами» і причина «зупинено вручну»', () => {
    renderWithProviders(
      <JobHistory jobs={[job('a', { stopReason: 'STOPPED', hardScore: -2 })]} selectedId={null} onSelect={() => undefined} />,
    );
    const row = screen.getByRole('button');
    expect(row).toHaveTextContent(/^З конфліктами/);
    expect(row).toHaveTextContent('зупинено вручну');
    expect(row).toHaveTextContent('2 жорстк.');
  });
});
