import type { ReactElement } from 'react';
import { usePublicGroups } from '@/api/public';
import { Tooltip } from '@/components/ui';
import { groupTooltipText } from './group-hint';

/**
 * Код групи в списку при наведенні мишею називає групу повністю: «Комп'ютерна інженерія · 4 курс»
 * (без відомої назви спеціальності підказки немає — див. groupTooltipText).
 * Довідник груп — той самий кешований запит, що й на головній; поки його немає, підказки просто немає.
 * На дотик і для читача екрана назва — на сторінці групи, тож підказка не єдиний шлях до неї.
 */
export function GroupHint({ code, children }: { code: string; children: ReactElement }) {
  const groups = usePublicGroups();
  const group = groups.data?.find((g) => g.code === code);
  return (
    <Tooltip content={group ? groupTooltipText(group) : null} side="top" align="start">
      {children}
    </Tooltip>
  );
}
