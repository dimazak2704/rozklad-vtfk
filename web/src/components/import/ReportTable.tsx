import { Search } from 'lucide-react';
import { useMemo, useState } from 'react';
import type { ImportReportRow, ReportRowType } from '@/api/import';
import type { ReportSeverity } from '@/api/types';
import { Badge, Chip, DataTable, EmptyState, Input, Select, type DataTableColumn } from '@/components/ui';
import { matchScore } from '@/lib/search';
import { ROW_TYPE_LABEL, scorePercent, SEVERITY_LABEL, SEVERITY_ORDER, SEVERITY_TONE } from './labels';

const PAGE_SIZE = 50;
const ALL = 'all';
const SEVERITIES: readonly ReportSeverity[] = ['ERROR', 'WARN', 'INFO', 'OK'];

interface ReportRowView extends ImportReportRow {
  key: string;
}

/**
 * Звіт нормалізації: що розпізнано, що виправлено, що чекає рішення. Спершу важливе (помилки й
 * попередження), фільтр за рівнем, видом і текстом; довгі звіти — сторінками.
 */
export function ReportTable({ report }: { report: readonly ImportReportRow[] }) {
  const counts = useMemo(() => {
    const out: Record<ReportSeverity, number> = { ERROR: 0, WARN: 0, INFO: 0, OK: 0 };
    for (const row of report) out[row.severity] += 1;
    return out;
  }, [report]);
  const [severities, setSeverities] = useState<ReadonlySet<ReportSeverity>>(() => {
    const important = new Set<ReportSeverity>(['ERROR', 'WARN', 'INFO']);
    return report.some((row) => important.has(row.severity)) ? important : new Set(SEVERITIES);
  });
  const [type, setType] = useState<ReportRowType | typeof ALL>(ALL);
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);

  const types = useMemo(() => [...new Set(report.map((row) => row.type))], [report]);
  const rows = useMemo<ReportRowView[]>(
    () =>
      report
        .map((row, index) => ({ ...row, key: `${index}` }))
        .filter(
          (row) =>
            severities.has(row.severity) &&
            (type === ALL || row.type === type) &&
            (!query.trim() || matchScore(query, row.raw, row.matched?.label, row.message, row.location) > 0),
        )
        .sort((a, b) => SEVERITY_ORDER[a.severity] - SEVERITY_ORDER[b.severity]),
    [report, severities, type, query],
  );
  const pageRows = rows.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE);

  const toggle = (severity: ReportSeverity) => {
    const next = new Set(severities);
    if (next.has(severity)) next.delete(severity);
    else next.add(severity);
    setSeverities(next);
    setPage(0);
  };

  const columns = useMemo<Array<DataTableColumn<ReportRowView>>>(
    () => [
      {
        id: 'severity',
        header: 'Рівень',
        width: '7.5rem',
        cell: (row) => (
          <Badge tone={SEVERITY_TONE[row.severity]} size="sm">
            {SEVERITY_LABEL[row.severity]}
          </Badge>
        ),
      },
      { id: 'type', header: 'Що', width: '8rem', hideBelow: 'sm', cell: (row) => ROW_TYPE_LABEL[row.type] },
      {
        id: 'raw',
        header: 'Як у файлі',
        rowHeader: true,
        cell: (row) => <span className="font-condensed text-body font-bold break-words">{row.raw}</span>,
      },
      {
        id: 'matched',
        header: 'Збіг у каталозі',
        cell: (row) =>
          row.matched ? (
            <span className="flex items-baseline gap-1.5">
              <span className="font-semibold">{row.matched.label}</span>
              {scorePercent(row.matched.score) < 100 ? <span className="text-meta text-muted figures">{scorePercent(row.matched.score)}%</span> : null}
            </span>
          ) : row.suggestions.length > 0 ? (
            <span className="text-caption text-change">
              Схоже на: {row.suggestions.map((s) => `${s.label} (${scorePercent(s.score)}%)`).join(', ')}
            </span>
          ) : row.type === 'TEACHER' || row.type === 'DISCIPLINE' || row.type === 'GROUP' || row.type === 'ROOM' ? (
            <span className="text-caption text-secondary">Новий запис</span>
          ) : (
            <span className="text-muted">—</span>
          ),
      },
      {
        id: 'message',
        header: 'Пояснення',
        hideBelow: 'md',
        cell: (row) => <span className="text-caption text-secondary">{row.message ?? ''}</span>,
      },
      { id: 'location', header: 'Де', hideBelow: 'lg', width: '15rem', cell: (row) => <span className="text-caption text-muted">{row.location}</span> },
    ],
    [],
  );

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        <div role="group" aria-label="Рівень рядків" className="flex flex-wrap gap-2">
          {SEVERITIES.map((severity) => (
            <Chip key={severity} selected={severities.has(severity)} onToggle={() => toggle(severity)} count={counts[severity]} disabled={counts[severity] === 0}>
              {SEVERITY_LABEL[severity]}
            </Chip>
          ))}
        </div>
        <Select<string>
          aria-label="Вид рядків"
          size="sm"
          className="w-40"
          value={type}
          onValueChange={(value) => {
            setType(value as ReportRowType | typeof ALL);
            setPage(0);
          }}
          options={[{ value: ALL, label: 'Усі види' }, ...types.map((t) => ({ value: t, label: ROW_TYPE_LABEL[t] }))]}
        />
        <Input
          type="search"
          size="sm"
          value={query}
          onChange={(event) => {
            setQuery(event.target.value);
            setPage(0);
          }}
          placeholder="Пошук у звіті"
          iconStart={<Search aria-hidden="true" className="size-4" />}
          aria-label="Пошук у звіті"
          wrapperClassName="w-full sm:w-56"
        />
      </div>
      <DataTable
        caption="Звіт імпорту"
        columns={columns}
        rows={pageRows}
        getRowId={(row) => row.key}
        density="compact"
        pagination={rows.length > PAGE_SIZE ? { page, size: PAGE_SIZE, total: rows.length, onPageChange: setPage, noun: 'рядків' } : undefined}
        empty={<EmptyState size="sm" title="Немає рядків з такими умовами" description="Увімкніть інші рівні або змініть пошук." />}
      />
    </div>
  );
}
