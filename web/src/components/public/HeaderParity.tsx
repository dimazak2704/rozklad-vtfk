import type { WeekParity } from '@/api/types';
import { ParityMark, Popover, PopoverContent, PopoverTrigger, Tooltip } from '@/components/ui';
import { capitalize, PARITY_HINT, PARITY_LABEL } from '@/lib/labels';

const TIP: Record<WeekParity, string> = {
  NUMERATOR: 'Чисельник: пари з верхнього рядка розкладу',
  DENOMINATOR: 'Знаменник: пари з нижнього рядка розкладу',
};

/**
 * «◐ Чисельник» у рядку контексту під заголовком сторінки. Те саме, що `ParityBadge size="sm"`, але
 * підказка відкривається вниз: угорі над рядком — назва сторінки, і підказка її не накриває.
 */
export function HeaderParity({ parity }: { parity: WeekParity | null | undefined }) {
  if (!parity) return null;
  const label = capitalize(PARITY_LABEL[parity]);
  return (
    <Popover>
      <Tooltip content={TIP[parity]} side="bottom" align="start">
        <PopoverTrigger
          aria-label={`${label}. Як чергуються тижні`}
          className="-mx-1.5 inline-flex h-7 items-center gap-1.5 rounded-control px-1.5 text-caption whitespace-nowrap transition-[background-color] duration-150 ease-out hover:bg-hover active:bg-active focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus pointer-coarse:h-11"
        >
          <ParityMark parity={parity} className="text-secondary" />
          <span className="font-[650] text-primary">{label}</span>
        </PopoverTrigger>
      </Tooltip>
      <PopoverContent width="sm" align="start" title={`Цього тижня ${PARITY_LABEL[parity]}`}>
        <p className="text-ui text-secondary">{PARITY_HINT[parity]}</p>
      </PopoverContent>
    </Popover>
  );
}
