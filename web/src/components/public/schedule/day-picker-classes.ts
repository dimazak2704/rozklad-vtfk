/**
 * Класи react-day-picker у стилі DateNav (бібліотека без власних стилів): місяць, навігація, дні.
 * Спільні для перемикача тижня й календаря відсутностей.
 */
export const DAY_PICKER_CLASSES = {
  root: 'text-ui',
  months: 'relative',
  month_caption: 'flex h-9 items-center justify-center font-semibold capitalize',
  nav: 'absolute inset-x-0 top-0 flex h-9 items-center justify-between',
  button_previous:
    'inline-flex size-8 items-center justify-center rounded-control text-secondary hover:bg-hover hover:text-primary focus-visible:outline-2 focus-visible:outline-focus disabled:opacity-40',
  button_next:
    'inline-flex size-8 items-center justify-center rounded-control text-secondary hover:bg-hover hover:text-primary focus-visible:outline-2 focus-visible:outline-focus disabled:opacity-40',
  chevron: 'size-4 fill-current',
  month_grid: 'mt-1 border-collapse',
  weekdays: '',
  weekday: 'h-8 w-9 text-center text-meta font-semibold text-muted',
  week: '',
  day: 'p-0 text-center',
  day_button:
    'm-px inline-flex size-9 items-center justify-center rounded-control figures hover:bg-hover focus-visible:outline-2 focus-visible:outline-focus',
  selected: '[&>button]:bg-selected [&>button]:text-accent [&>button]:font-semibold',
  range_start: '[&>button]:bg-accent [&>button]:text-on-accent [&>button]:hover:bg-accent-hover',
  range_end: '[&>button]:bg-accent [&>button]:text-on-accent [&>button]:hover:bg-accent-hover',
  range_middle: '[&>button]:rounded-none [&>button]:bg-selected [&>button]:text-accent',
  today: '',
  outside: 'opacity-45',
  disabled: 'opacity-35 [&>button]:cursor-not-allowed [&>button]:hover:bg-transparent',
} as const;
