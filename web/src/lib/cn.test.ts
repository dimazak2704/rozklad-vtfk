import { describe, expect, it } from 'vitest';
import { cn } from './cn';

describe('cn', () => {
  it('розмір тексту з теми й колір не конфліктують: обидва класи лишаються', () => {
    expect(cn('text-cell font-[650]', 'text-primary')).toBe('text-cell font-[650] text-primary');
    expect(cn('text-ui', 'text-secondary')).toBe('text-ui text-secondary');
  });

  it('два розміри з теми — конфлікт: лишається останній', () => {
    expect(cn('text-cell', 'text-caption')).toBe('text-caption');
  });
});
