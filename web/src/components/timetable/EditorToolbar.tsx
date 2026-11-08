import { DoorOpen, ListFilter, Plus, Redo2, Search, Undo2, UserRound, Users, X } from 'lucide-react';
import type { RefObject } from 'react';
import { Button, Chip, IconButton, Input, SegmentedControl, Select, Switch } from '@/components/ui';
import { cn } from '@/lib/cn';
import type { BoardFilters, BoardView } from './model/grid';

const VIEW_OPTIONS = [
  { value: 'group' as const, label: 'Групи', icon: <Users aria-hidden="true" className="size-3.5" /> },
  { value: 'teacher' as const, label: 'Викладачі', icon: <UserRound aria-hidden="true" className="size-3.5" /> },
  { value: 'room' as const, label: 'Аудиторії', icon: <DoorOpen aria-hidden="true" className="size-3.5" /> },
];

const SEARCH_PLACEHOLDER: Record<BoardView, string> = {
  group: 'Група: 4КІ1',
  teacher: 'Викладач: Кренцін',
  room: 'Аудиторія: 301',
};

const ALL_DEPARTMENTS = 'all';

interface EditorToolbarProps {
  view: BoardView;
  onViewChange: (view: BoardView) => void;
  filters: BoardFilters;
  onFiltersChange: (filters: BoardFilters) => void;
  departments: readonly string[];
  shown: number;
  total: number;
  searchRef: RefObject<HTMLInputElement | null>;
  editable: boolean;
  canUndo: boolean;
  canRedo: boolean;
  undoLabel: string | null;
  redoLabel: string | null;
  onUndo: () => void;
  onRedo: () => void;
  onAdd: () => void;
  className?: string;
}

/** Що й кого показати: подання, курси, відділення, пошук; праворуч — скасування й додавання пари. */
export function EditorToolbar({
  view,
  onViewChange,
  filters,
  onFiltersChange,
  departments,
  shown,
  total,
  searchRef,
  editable,
  canUndo,
  canRedo,
  undoLabel,
  redoLabel,
  onUndo,
  onRedo,
  onAdd,
  className,
}: EditorToolbarProps) {
  const toggleCourse = (course: number) =>
    onFiltersChange({
      ...filters,
      courses: filters.courses.includes(course) ? filters.courses.filter((c) => c !== course) : [...filters.courses, course].sort(),
    });
  const filtered = filters.courses.length > 0 || filters.department !== null || filters.query !== '' || filters.conflictsOnly;

  return (
    <div className={cn('flex flex-col gap-2.5', className)}>
      <div className="flex flex-wrap items-center gap-2">
        <SegmentedControl<BoardView> aria-label="Подання сітки" value={view} onValueChange={onViewChange} options={VIEW_OPTIONS} />
        <Input
          ref={searchRef}
          type="search"
          size="md"
          value={filters.query}
          onChange={(event) => onFiltersChange({ ...filters, query: event.target.value })}
          placeholder={SEARCH_PLACEHOLDER[view]}
          aria-label="Пошук у сітці"
          iconStart={<Search aria-hidden="true" className="size-4" />}
          wrapperClassName="w-full sm:w-56"
        />
        <div className="ms-auto flex items-center gap-1">
          <IconButton
            label={undoLabel ? `Скасувати: ${undoLabel}` : 'Скасувати — правок ще немає'}
            shortcut="mod+z"
            icon={<Undo2 aria-hidden="true" className="size-4" />}
            onClick={onUndo}
            disabled={!editable || !canUndo}
          />
          <IconButton
            label={redoLabel ? `Повторити: ${redoLabel}` : 'Повторити — нічого скасованого'}
            shortcut="mod+shift+z"
            icon={<Redo2 aria-hidden="true" className="size-4" />}
            onClick={onRedo}
            disabled={!editable || !canRedo}
          />
          <Button onClick={onAdd} disabled={!editable} iconStart={<Plus aria-hidden="true" className="size-4" />} className="ms-1">
            Додати пару
          </Button>
        </div>
      </div>

      <div className="flex flex-wrap items-center gap-2" role="group" aria-label="Фільтри">
        <span className="inline-flex items-center gap-1.5 text-caption font-semibold text-muted">
          <ListFilter aria-hidden="true" className="size-3.5" />
          Курс
        </span>
        {[1, 2, 3, 4].map((course) => (
          <Chip key={course} selected={filters.courses.includes(course)} onToggle={() => toggleCourse(course)} className="h-7 text-caption">
            {course}
          </Chip>
        ))}
        <Select
          aria-label="Відділення"
          size="sm"
          className="w-56"
          value={filters.department ?? ALL_DEPARTMENTS}
          onValueChange={(value) => onFiltersChange({ ...filters, department: value === ALL_DEPARTMENTS ? null : value })}
          options={[{ value: ALL_DEPARTMENTS, label: 'Усі відділення' }, ...departments.map((name) => ({ value: name, label: name }))]}
        />
        <Switch
          label="Лише з конфліктами"
          checked={filters.conflictsOnly}
          onCheckedChange={(checked) => onFiltersChange({ ...filters, conflictsOnly: checked })}
          className="ms-1"
        />
        <span className="ms-auto text-caption text-muted figures" aria-live="polite">
          Показано {shown} з {total}
        </span>
        {filtered ? (
          <Button
            size="sm"
            variant="ghost"
            iconStart={<X aria-hidden="true" className="size-3.5" />}
            onClick={() => onFiltersChange({ courses: [], department: null, query: '', conflictsOnly: false })}
          >
            Скинути
          </Button>
        ) : null}
      </div>
    </div>
  );
}
