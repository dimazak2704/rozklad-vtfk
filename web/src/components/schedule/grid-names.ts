import { createContext, useContext } from 'react';
import type { TeacherNameOptions } from '@/lib/teacher-name';

const EMPTY: TeacherNameOptions = {};

/** Як писати викладачів у клітинках цього екрана (див. GridNames.tsx); без провайдера — коротка форма. */
export const GridNamesContext = createContext<TeacherNameOptions>(EMPTY);

/** Довідник імен і однофамільці для `formatTeacherName(teacher, 'grid', options)`. */
export function useGridNames(): TeacherNameOptions {
  return useContext(GridNamesContext);
}
