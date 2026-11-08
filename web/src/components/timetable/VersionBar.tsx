import { Archive, CopyPlus, FileDiff, LayoutGrid, Pencil, Send } from 'lucide-react';
import type { TimetableVersionDto, Uuid } from '@/api/types';
import { Badge, Button, SegmentedControl, Select, type BadgeTone } from '@/components/ui';
import { formatNumericDate } from '@/lib/date';
import { countLabel, PAIR_FORMS } from '@/lib/labels';
import { VERSION_SOURCE_LABEL, VERSION_STATUS_LABEL } from './model/labels';

export type EditorMode = 'grid' | 'diff';

const STATUS_TONE: Record<TimetableVersionDto['status'], BadgeTone> = {
  DRAFT: 'neutral',
  PUBLISHED: 'success',
  ARCHIVED: 'neutral',
};

const STATUS_ICON = {
  DRAFT: <Pencil aria-hidden="true" className="size-3" />,
  PUBLISHED: <Send aria-hidden="true" className="size-3" />,
  ARCHIVED: <Archive aria-hidden="true" className="size-3" />,
} as const;

function versionMeta(version: TimetableVersionDto): string {
  const parts = [VERSION_SOURCE_LABEL[version.source], countLabel(version.lessonCount, PAIR_FORMS)];
  if (version.status === 'PUBLISHED' && version.effectiveFrom) parts.push(`діє з ${formatNumericDate(version.effectiveFrom)}`);
  if (version.hardConflicts > 0) parts.push(`конфліктів: ${version.hardConflicts}`);
  return parts.join(' · ');
}

interface VersionBarProps {
  versions: readonly TimetableVersionDto[];
  selected: TimetableVersionDto;
  onSelect: (versionId: Uuid) => void;
  mode: EditorMode;
  onModeChange: (mode: EditorMode) => void;
  onCreate: () => void;
  onPublish: () => void;
}

/**
 * Рядок версії: яку версію бачимо, її стан і звідки вона, а праворуч — що з нею можна зробити:
 * копіювати в нову чернетку, порівняти, опублікувати (лише чернетку — одна заповнена кнопка на екран).
 */
export function VersionBar({ versions, selected, onSelect, mode, onModeChange, onCreate, onPublish }: VersionBarProps) {
  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-3 rounded-panel border border-default bg-surface px-3 py-2.5">
      <div className="flex min-w-0 flex-1 flex-wrap items-center gap-x-3 gap-y-2">
        <Select
          aria-label="Версія розкладу"
          value={selected.id}
          onValueChange={onSelect}
          className="w-full font-semibold sm:w-80"
          options={versions.map((version) => ({
            value: version.id,
            label: version.name,
            description: `${VERSION_STATUS_LABEL[version.status]} · ${versionMeta(version)}`,
          }))}
        />
        <span className="flex min-w-0 flex-wrap items-center gap-2">
          <Badge tone={STATUS_TONE[selected.status]} icon={STATUS_ICON[selected.status]} className={selected.status === 'ARCHIVED' ? 'text-muted' : undefined}>
            {VERSION_STATUS_LABEL[selected.status]}
          </Badge>
          <span className="text-caption text-secondary figures">{versionMeta(selected)}</span>
        </span>
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <SegmentedControl<EditorMode>
          aria-label="Що показати"
          value={mode}
          onValueChange={onModeChange}
          options={[
            { value: 'grid', label: 'Сітка', icon: <LayoutGrid aria-hidden="true" className="size-3.5" /> },
            { value: 'diff', label: 'Порівняння', icon: <FileDiff aria-hidden="true" className="size-3.5" /> },
          ]}
        />
        <Button onClick={onCreate} iconStart={<CopyPlus aria-hidden="true" className="size-4" />}>
          Нова версія
        </Button>
        {selected.status === 'DRAFT' ? (
          <Button variant="primary" onClick={onPublish} iconStart={<Send aria-hidden="true" className="size-4" />}>
            Опублікувати
          </Button>
        ) : null}
      </div>
    </div>
  );
}
