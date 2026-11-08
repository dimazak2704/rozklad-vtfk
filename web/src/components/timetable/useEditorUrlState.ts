/**
 * Стан редактора в адресі (UI.md §3: посиланням можна поділитися): версія, режим, подання, фільтри,
 * база порівняння. Зміни — `replace`, щоб «Назад» не проходив кожен клік по фільтру.
 */
import { useCallback, useMemo } from 'react';
import { useSearchParams } from 'react-router';
import type { Uuid } from '@/api/types';
import { BOARD_VIEWS, type BoardFilters, type BoardView } from './model/grid';
import type { EditorMode } from './VersionBar';

export interface EditorUrlState {
  versionId: Uuid | null;
  mode: EditorMode;
  baseId: Uuid | null;
  view: BoardView;
  filters: BoardFilters;
}

function parseCourses(raw: string | null): number[] {
  if (!raw) return [];
  return [...new Set(raw.split(',').map(Number))].filter((n) => Number.isInteger(n) && n >= 1 && n <= 6).sort();
}

export function useEditorUrlState() {
  const [params, setParams] = useSearchParams();

  const state = useMemo<EditorUrlState>(() => {
    const view = params.get('view');
    return {
      versionId: params.get('version'),
      mode: params.get('mode') === 'diff' ? 'diff' : 'grid',
      baseId: params.get('base'),
      view: BOARD_VIEWS.includes(view as BoardView) ? (view as BoardView) : 'group',
      filters: {
        courses: parseCourses(params.get('course')),
        department: params.get('dept'),
        query: params.get('q') ?? '',
        conflictsOnly: params.get('conflicts') === '1',
      },
    };
  }, [params]);

  const update = useCallback(
    (patch: Partial<EditorUrlState>) => {
      setParams(
        (previous) => {
          const next = new URLSearchParams(previous);
          const set = (key: string, value: string | null | undefined) => {
            if (value) next.set(key, value);
            else next.delete(key);
          };
          if ('versionId' in patch) set('version', patch.versionId);
          if ('mode' in patch) set('mode', patch.mode === 'diff' ? 'diff' : null);
          if ('baseId' in patch) set('base', patch.baseId);
          if ('view' in patch) set('view', patch.view === 'group' ? null : patch.view);
          if (patch.filters) {
            set('course', patch.filters.courses.join(','));
            set('dept', patch.filters.department);
            set('q', patch.filters.query);
            set('conflicts', patch.filters.conflictsOnly ? '1' : null);
          }
          return next;
        },
        { replace: true },
      );
    },
    [setParams],
  );

  return [state, update] as const;
}
