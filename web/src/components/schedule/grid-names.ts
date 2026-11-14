import { createContext, useContext } from 'react';

const EMPTY: ReadonlySet<string> = new Set();

/** Однофамільці на екрані (див. GridNames.tsx); без провайдера — нікого. */
export const GridNamesContext = createContext<ReadonlySet<string>>(EMPTY);

/** Підписи сітки, які в цьому розкладі належать різним викладачам. */
export function useGridNames(): ReadonlySet<string> {
  return useContext(GridNamesContext);
}
