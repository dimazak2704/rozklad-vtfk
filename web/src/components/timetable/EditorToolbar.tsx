import { DoorOpen, ListFilter, Plus, Redo2, Search, Undo2, UserRound, Users } from 'lucide-react';
import type { RefObject } from 'react';
import type { Weekday } from '@/api/types';
import {
  Button,
  Chip,
  IconButton,
  Input,
  Popover,
  PopoverContent,
  PopoverTrigger,
  SegmentedControl,
  Select,
  Switch,
} from '@/components/ui';
import { cn } from '@/lib/cn';
import { capitalize, weekdayName, weekdayShort } from '@/lib/labels';
import { EMPTY_FILTERS, WEEKDAYS, type BoardFilters, type BoardView } from './model/grid';

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
  /** Телефон: сітка показує один день, а тут — перемикач днів. */
  day?: Weekday | null;
  onDayChange?: (day: Weekday) => void;
  className?: string;
}

/**
 * Що й кого показати: подання і пошук на виду; курси, відділення й «лише з конфліктами» — за кнопкою
 * «Фільтр». Праворуч — скасування й додавання пари, лише коли версію можна правити.
 */
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
  day = null,
  onDayChange,
  className,
}: EditorToolbarProps) {
  const toggleCourse = (course: number) =>
    onFiltersChange({
      ...filters,
      courses: filters.courses.includes(course) ? filters.courses.filter((c) => c !== course) : [...filters.courses, course].sort(),
    });
  const activeFilters = filters.courses.length + (filters.department !== null ? 1 : 0) + (filters.conflictsOnly ? 1 : 0);
  const filtered = activeFilters > 0 || filters.query !== '';

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
          wrapperClassName="min-w-0 flex-1 sm:w-56 sm:flex-none"
        />
        <Popover>
          <PopoverTrigger asChild>
            <Button iconStart={<ListFilter aria-hidden="true" className="size-4" />} aria-label={activeFilters > 0 ? `Фільтр: увімкнено ${activeFilters}` : 'Фільтр'}>
              Фільтр
              {activeFilters > 0 ? (
                <span aria-hidden="true" className="ms-0.5 inline-flex size-5 items-center justify-center rounded-full bg-accent text-meta text-on-accent figures">
                  {activeFilters}
                </span>
              ) : null}
            </Button>
          </PopoverTrigger>
          <PopoverContent title="Що показати" width="md">
            <div className="flex flex-col gap-4">
              <div className="flex flex-col gap-1.5" role="group" aria-label="Курс">
                <span aria-hidden="true" className="text-caption font-[650] text-primary">
                  Курс
                </span>
                <div className="flex flex-wrap gap-1.5">
                  {[1, 2, 3, 4].map((course) => (
                    <Chip key={course} selected={filters.courses.includes(course)} onToggle={() => toggleCourse(course)}>
                      {course} курс
                    </Chip>
                  ))}
                </div>
              </div>
              <Select
                aria-label="Відділення"
                value={filters.department ?? ALL_DEPARTMENTS}
                onValueChange={(value) => onFiltersChange({ ...filters, department: value === ALL_DEPARTMENTS ? null : value })}
                options={[{ value: ALL_DEPARTMENTS, label: 'Усі відділення' }, ...departments.map((name) => ({ value: name, label: name }))]}
              />
              <Switch
                label="Лише з конфліктами"
                checked={filters.conflictsOnly}
                onCheckedChange={(checked) => onFiltersChange({ ...filters, conflictsOnly: checked })}
              />
              {activeFilters > 0 ? (
                <Button
                  size="sm"
                  variant="ghost"
                  className="self-start"
                  onClick={() => onFiltersChange({ ...EMPTY_FILTERS, query: filters.query })}
                >
                  Скинути фільтр
                </Button>
              ) : null}
            </div>
          </PopoverContent>
        </Popover>
        {editable ? (
          <div className="ms-auto flex items-center gap-1">
            <IconButton
              label={undoLabel ? `Скасувати: ${undoLabel}` : 'Скасувати — правок ще немає'}
              shortcut="mod+z"
              icon={<Undo2 aria-hidden="true" className="size-4" />}
              onClick={onUndo}
              disabled={!canUndo}
            />
            <IconButton
              label={redoLabel ? `Повторити: ${redoLabel}` : 'Повторити — нічого скасованого'}
              shortcut="mod+shift+z"
              icon={<Redo2 aria-hidden="true" className="size-4" />}
              onClick={onRedo}
              disabled={!canRedo}
            />
            <Button onClick={onAdd} iconStart={<Plus aria-hidden="true" className="size-4" />} className="ms-1">
              Додати пару
            </Button>
          </div>
        ) : null}
      </div>

      {day !== null && onDayChange ? (
        <SegmentedControl
          aria-label="День тижня"
          stretch
          value={String(day)}
          onValueChange={(value) => onDayChange(Number(value) as Weekday)}
          options={WEEKDAYS.map((weekday) => ({ value: String(weekday), label: capitalize(weekdayShort(weekday)), ariaLabel: weekdayName(weekday) }))}
        />
      ) : null}

      {filtered ? (
        <p className="flex flex-wrap items-center gap-x-3 text-caption text-muted" aria-live="polite">
          <span className="figures">
            Показано {shown} з {total}
          </span>
          <button
            type="button"
            onClick={() => onFiltersChange(EMPTY_FILTERS)}
            className="rounded-tag font-[650] text-accent hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus pointer-coarse:min-h-11"
          >
            Показати всіх
          </button>
        </p>
      ) : null}
    </div>
  );
}
