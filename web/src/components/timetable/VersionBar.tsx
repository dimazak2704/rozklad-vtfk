import { FileDiff, LayoutGrid } from 'lucide-react';
import type { TimetableVersionDto, Uuid } from '@/api/types';
import { SegmentedControl, Select } from '@/components/ui';
import { formatNumericDate } from '@/lib/date';
import { VERSION_SOURCE_LABEL, VERSION_STATUS_LABEL } from './model/labels';

export type EditorMode = 'grid' | 'diff';

/** Підпис версії в списку: стан і звідки вона. Конфлікти — лише коли вони є. */
function optionMeta(version: TimetableVersionDto): string {
  const parts = [VERSION_STATUS_LABEL[version.status], VERSION_SOURCE_LABEL[version.source]];
  if (version.status === 'PUBLISHED' && version.effectiveFrom) parts.push(`діє з ${formatNumericDate(version.effectiveFrom)}`);
  if (version.hardConflicts > 0) parts.push(`конфліктів: ${version.hardConflicts}`);
  return parts.join(', ');
}

interface VersionSelectProps {
  versions: readonly TimetableVersionDto[];
  selected: TimetableVersionDto;
  onSelect: (versionId: Uuid) => void;
}

/** Яку версію бачимо. Решта дій з версією — головна кнопка й «⋯» у заголовку сторінки. */
export function VersionSelect({ versions, selected, onSelect }: VersionSelectProps) {
  return (
    <Select
      aria-label="Версія розкладу"
      value={selected.id}
      onValueChange={onSelect}
      className="w-full font-semibold sm:w-72"
      options={versions.map((version) => ({ value: version.id, label: version.name, description: optionMeta(version) }))}
    />
  );
}

/** «Сітка / Порівняння» — друге (і останнє) керування в заголовку. */
export function ModeSwitch({ mode, onModeChange }: { mode: EditorMode; onModeChange: (mode: EditorMode) => void }) {
  return (
    <SegmentedControl<EditorMode>
      aria-label="Що показати"
      value={mode}
      onValueChange={onModeChange}
      options={[
        { value: 'grid', label: 'Сітка', icon: <LayoutGrid aria-hidden="true" className="size-3.5" /> },
        { value: 'diff', label: 'Порівняння', icon: <FileDiff aria-hidden="true" className="size-3.5" /> },
      ]}
    />
  );
}
