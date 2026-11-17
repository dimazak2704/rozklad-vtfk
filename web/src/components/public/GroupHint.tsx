import type { ReactElement } from 'react';
import { usePublicGroups } from '@/api/public';
import { Tooltip } from '@/components/ui';
import { openScheduleHint } from '@/lib/labels';
import { groupTooltipText } from './group-hint';

/**
 * Посилання на код групи при наведенні чи фокусі каже, куди веде («Розклад групи на цей день»),
 * і, коли назва спеціальності відома, називає групу повністю другим, тихішим рядком:
 * «Комп'ютерна інженерія · 4 курс» (див. groupTooltipText).
 * Довідник груп — той самий кешований запит, що й на головній; поки його немає, лишається сама дія.
 * На дотик і для читача екрана назва — на сторінці групи, тож підказка не єдиний шлях до неї.
 */
export function GroupHint({ code, when, children }: { code: string; /** «на цей день», «на сьогодні». */ when?: string; children: ReactElement }) {
  const groups = usePublicGroups();
  const group = groups.data?.find((g) => g.code === code);
  const about = group ? groupTooltipText(group) : null;
  return (
    <Tooltip
      content={
        <span className="flex flex-col">
          <span>{openScheduleHint('GROUP', when)}</span>
          {about ? <span className="font-normal opacity-80">{about}</span> : null}
        </span>
      }
      side="top"
      align="start"
    >
      {children}
    </Tooltip>
  );
}
