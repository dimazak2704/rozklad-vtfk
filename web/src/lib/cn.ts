import { clsx, type ClassValue } from 'clsx';
import { extendTailwindMerge } from 'tailwind-merge';

/**
 * Імена з нашої теми (src/styles/theme.css). tailwind-merge має знати, що `text-ui` — розмір,
 * а `text-primary` — колір, інакше він вважатиме їх конфліктом і викине один із класів.
 */
const twMerge = extendTailwindMerge({
  extend: {
    theme: {
      text: ['meta', 'caption', 'ui', 'body', 'heading', 'title', 'headline', 'display', 'numeral'],
      radius: ['plate', 'control', 'panel', 'dialog'],
      shadow: ['popover', 'dialog', 'lift'],
      font: ['condensed'],
      animate: ['shimmer', 'live'],
      color: [
        'canvas',
        'surface',
        'raised',
        'sunken',
        'hover',
        'active',
        'skeleton',
        'selected',
        'selected-hover',
        'shell',
        'shell-hover',
        'shell-raised',
        'plate',
        'scrim',
        'inverse',
        'accent',
        'accent-hover',
        'accent-subtle',
        'danger',
        'danger-hover',
        'danger-subtle',
        'change',
        'change-subtle',
        'cancel',
        'cancel-subtle',
        'add',
        'add-subtle',
        'room',
        'room-subtle',
        'block-subtle',
        'primary',
        'secondary',
        'muted',
        'disabled',
        'on-accent',
        'on-shell',
        'on-shell-muted',
        'on-change',
        'separator',
        'default',
        'control',
        'strong',
        'focus',
        'focus-on-shell',
        'rule',
      ],
    },
  },
});

export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs));
}
