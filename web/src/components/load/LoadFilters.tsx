import { ListFilter, Search } from 'lucide-react';
import type { RefObject } from 'react';
import type { CalendarSemester } from '@/api/calendar';
import type { LoadSource } from '@/api/types';
import type { TableUrlState } from '@/components/catalog/useTableUrlState';
import { Button, Combobox, Input, Popover, PopoverContent, PopoverTrigger, Select } from '@/components/ui';
import { handleSearchEscape } from '@/hooks/useHotkeys';
import { LOAD_SOURCE_LABEL } from './labels';

const ALL = 'all';
const SOURCES: readonly LoadSource[] = ['TIMETABLE_DERIVED', 'MANUAL', 'CURRICULUM', 'PROGRAM'];
const FILTER_LABEL = 'flex w-full flex-col gap-1 text-caption font-[650] text-primary sm:w-auto';

interface LoadFiltersProps {
  table: TableUrlState;
  groupId: string | null;
  groupOptions: ReadonlyArray<{ value: string; label: string; description?: string }>;
  groupsLoading: boolean;
  searchRef: RefObject<HTMLInputElement | null>;
  semester: CalendarSemester;
  semesters: readonly CalendarSemester[];
  activeSemesterId: string | undefined;
  source: LoadSource | undefined;
}

/**
 * Фільтри навантаження: група й пошук на виду; семестр і джерело годин змінюють рідко — вони за кнопкою «Фільтр».
 * Під ними — підказка, що показано (інший семестр) і навіщо обрати групу.
 */
export function LoadFilters({ table, groupId, groupOptions, groupsLoading, searchRef, semester, semesters, activeSemesterId, source }: LoadFiltersProps) {
  const searching = table.q.trim() !== '';
  const extraFilters = (source ? 1 : 0) + (semester.id !== activeSemesterId ? 1 : 0);
  return (
    <>
      <div className="flex flex-wrap items-end gap-3">
        <label className={FILTER_LABEL}>
          Група
          <Combobox
            className="w-full sm:w-44"
            value={groupId}
            onValueChange={(value) => table.set({ group: value })}
            options={groupOptions}
            placeholder="Усі групи"
            searchPlaceholder="Код: 4КІ1 або 4ki1"
            loading={groupsLoading}
            clearable
          />
        </label>
        <Input
          ref={searchRef}
          type="search"
          value={table.q}
          onChange={(event) => table.set({ q: event.target.value })}
          onKeyDown={(event) => handleSearchEscape(event, table.q, () => table.set({ q: null }))}
          placeholder="Дисципліна або викладач"
          iconStart={<Search aria-hidden="true" className="size-4" />}
          aria-label="Пошук у навантаженні"
          wrapperClassName="w-full sm:w-64"
        />
        {/* семестр і джерело змінюють рідко — вони за кнопкою «Фільтр», а не чотирма полями в ряд */}
        <Popover>
          <PopoverTrigger asChild>
            <Button iconStart={<ListFilter aria-hidden="true" className="size-4" />}>
              Фільтр
              {extraFilters > 0 ? (
                <span aria-hidden="true" className="ms-0.5 inline-flex size-5 items-center justify-center rounded-full bg-accent text-meta text-on-accent figures">
                  {extraFilters}
                </span>
              ) : null}
            </Button>
          </PopoverTrigger>
          <PopoverContent title="Що показати" width="md">
            <div className="flex flex-col gap-4">
              <label className={FILTER_LABEL}>
                Семестр
                <Select<string>
                  className="w-full"
                  value={semester.id}
                  onValueChange={(value) => table.set({ semester: value === activeSemesterId ? null : value })}
                  options={semesters.map((s) => ({ value: s.id, label: s.name, description: s.active ? 'Активний' : undefined }))}
                />
              </label>
              <label className={FILTER_LABEL}>
                Джерело годин
                <Select<string>
                  className="w-full"
                  value={source ?? ALL}
                  onValueChange={(value) => table.set({ source: value === ALL ? null : value })}
                  options={[{ value: ALL, label: 'Будь-яке' }, ...SOURCES.map((s) => ({ value: s, label: LOAD_SOURCE_LABEL[s] }))]}
                />
              </label>
            </div>
          </PopoverContent>
        </Popover>
      </div>
      {/* підказка — одразу під полем «Група», а не під 50 рядками й сторінками */}
      {(!groupId && !searching) || semester.id !== activeSemesterId ? (
        <p className="-mt-3 text-caption text-muted">
          {semester.id !== activeSemesterId ? `Показано ${semester.name}. ` : null}
          {!groupId && !searching ? 'Оберіть групу, щоб побачити, як заповнено її тиждень.' : null}
        </p>
      ) : null}
    </>
  );
}
