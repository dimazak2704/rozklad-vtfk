import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { describe, expect, it, vi } from 'vitest';
import type { ImportJob, ImportReportRow } from '@/api/import';
import { apiPath } from '@/mocks/kit';
import { server } from '@/mocks/server';
import { renderWithProviders } from '@/test/render';
import { ApplyPanel } from './ApplyPanel';
import { blockingReason, creates } from './decisions';
import { LoadOptionsPanel } from './LoadOptionsPanel';
import { MappingPanel } from './MappingPanel';

const row = (patch: Partial<ImportReportRow>): ImportReportRow => ({
  severity: 'INFO',
  type: 'ORDER',
  raw: '',
  suggestions: [],
  location: '',
  ...patch,
});

const job = (patch: Partial<ImportJob>): ImportJob => ({
  id: 'job-1',
  kind: 'ORDER_DOCUMENT',
  fileName: 'order.jpg',
  fileType: 'JPEG',
  fileSize: 1000,
  status: 'NEEDS_REVIEW',
  createdAt: '2026-11-08T10:00:00Z',
  updatedAt: '2026-11-08T10:00:00Z',
  summary: { recognized: 0, unknown: 0, conflicts: 1, warnings: 0 },
  report: [],
  canApply: false,
  version: 3,
  ...patch,
});

describe('панель застосування', () => {
  // r3-ai-imports-06: звірка розпорядження блокує на вкладці «Чернетка», а не «Рішення»
  it('веде туди, де виправляють блокувальний рядок', async () => {
    const onShowTab = vi.fn();
    const user = userEvent.setup();
    const review = job({ report: [row({ severity: 'ERROR', message: 'Рядки розпорядження прочитав ШІ з фото/скану: звірте кожен рядок.' })] });
    renderWithProviders(<ApplyPanel job={review} onShowTab={onShowTab} />);

    expect(screen.getByText(/виправте на вкладці «Чернетка»: Рядки розпорядження прочитав ШІ/)).toBeInTheDocument();
    expect(screen.queryByText(/вирішіть/)).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'До чернетки' }));
    expect(onShowTab).toHaveBeenCalledWith('draft');
  });

  // r5-ai-imports-68: відмова core видна, і застосувати можна ще раз
  it('показує відмову core і дозволяє застосувати ще раз', () => {
    const rejected = job({
      summary: { recognized: 0, unknown: 0, conflicts: 0, warnings: 0 },
      error: 'Сервер розкладу не прийняв розпорядження: Дата 04.06.2026 не входить у жоден семестр.',
      canApply: true,
    });
    renderWithProviders(<ApplyPanel job={rejected} onShowTab={vi.fn()} />);

    expect(screen.getByText(/Дата 04\.06\.2026 не входить у жоден семестр/)).toBeInTheDocument();
    expect(screen.queryByText(/вирішіть 0/)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Спробувати ще раз' })).toBeEnabled();
  });

  it('параметри програми — на «Чернетці», рішення щодо назв — на «Рішенні»', () => {
    const options = row({ severity: 'ERROR', type: 'OPTIONS', location: 'параметри імпорту', message: 'Вкажіть групи' });
    expect(blockingReason({ kind: 'DISCIPLINE_PROGRAM', report: [options] }).tab).toBe('draft');
    const duplicate = row({ severity: 'WARN', type: 'TEACHER', raw: 'Стець', suggestions: [{ id: 't', label: 'Стецьо', score: 0.93 }] });
    expect(blockingReason({ kind: 'TIMETABLE_XLSX', report: [options, duplicate] })).toMatchObject({ tab: 'decisions', decisions: 1 });
  });
});

describe('рішення щодо назв', () => {
  it('розпорядження нічого не створює, програма — лише дисципліни', () => {
    expect(creates('ORDER_DOCUMENT', 'TEACHER')).toBe(false);
    expect(creates('DISCIPLINE_PROGRAM', 'GROUP')).toBe(false);
    expect(creates('CURRICULUM', 'DISCIPLINE')).toBe(true);
    expect(creates('TIMETABLE_XLSX', 'GROUP')).toBe(true);
  });

  it('для розпорядження — довідка без «Створити …»', () => {
    const order = job({
      report: [row({ severity: 'INFO', type: 'TEACHER', raw: 'Бадун', location: 'рядок 12', suggestions: [{ id: 't', label: 'Бадунь', score: 0.96 }] })],
    });
    renderWithProviders(<MappingPanel job={order} />);

    expect(screen.getByText(/каталог не змінюється/)).toBeInTheDocument();
    expect(screen.getByText('схоже: «Бадунь»')).toBeInTheDocument();
    expect(screen.queryByRole('radio')).not.toBeInTheDocument();
  });

  it('програма не пропонує створити групу', () => {
    const program = job({
      kind: 'DISCIPLINE_PROGRAM',
      report: [row({ severity: 'ERROR', type: 'GROUP', raw: '4КІ9', location: 'параметри імпорту', message: 'Немає в каталозі.' })],
    });
    renderWithProviders(<MappingPanel job={program} />);

    expect(screen.getByRole('radio', { name: 'Зіставити з наявним' })).toBeInTheDocument();
    expect(screen.queryByRole('radio', { name: 'Створити групу' })).not.toBeInTheDocument();
  });
});

describe('параметри застосування', () => {
  // r3-ai-imports-02: тижневі години задають на завданні, без повторного завантаження
  it('зберігає групи й години з версією завдання', async () => {
    const sent = vi.fn();
    server.use(
      http.put(apiPath('/import/jobs/:id/options'), async ({ request }) => {
        sent(await request.json());
        return HttpResponse.json(job({ kind: 'DISCIPLINE_PROGRAM', status: 'READY', canApply: true, version: 4 }));
      }),
    );
    const user = userEvent.setup();
    const program = job({
      kind: 'DISCIPLINE_PROGRAM',
      options: { groupCodes: ['4КІ1'] },
      report: [row({ severity: 'ERROR', type: 'OPTIONS', location: 'параметри імпорту', message: 'У програмі не вказано годин на тиждень.' })],
    });
    renderWithProviders(<LoadOptionsPanel job={program} />);

    expect(screen.getByText('У програмі не вказано годин на тиждень.')).toBeInTheDocument();
    await user.type(screen.getByRole('spinbutton', { name: /Годин на тиждень/ }), '2');
    await user.click(screen.getByRole('button', { name: 'Зберегти параметри' }));

    await waitFor(() => expect(sent).toHaveBeenCalledOnce());
    expect(sent).toHaveBeenCalledWith({ version: 3, options: { groupCodes: ['4КІ1'], hoursPerWeek: 2 } });
  });
});
