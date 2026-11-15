import type { Nullable, PeriodEffect } from '@/api/types';
import { cn } from '@/lib/cn';
import { EFFECT_DOT } from './labels';

interface KindSwatchProps {
  /** Колір виду з легенди графіка (`#RRGGBB`); без нього — крапка кольору ефекту. */
  color: Nullable<string>;
  effect: PeriodEffect;
  className?: string;
}

/**
 * Крапка виду періоду: колір з графіка коледжу, як клітинка легенди, або тон ефекту, якщо колір не задано.
 * Лише прикраса поруч зі словом (DESIGN §2.3): світлі кольори графіка («#FFFFCC») тримає тонка рамка.
 */
export function KindSwatch({ color, effect, className }: KindSwatchProps) {
  return (
    <span
      aria-hidden="true"
      data-color={color ?? undefined}
      className={cn(
        'inline-block size-2.5 shrink-0 rounded-full',
        color ? 'border border-strong' : EFFECT_DOT[effect],
        className,
      )}
      style={color ? { backgroundColor: color } : undefined}
    />
  );
}
