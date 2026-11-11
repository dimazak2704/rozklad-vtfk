import { cn } from '@/lib/cn';
import { initials } from '@/lib/names';

interface AvatarProps {
  name: string;
  size?: 'sm' | 'md' | 'lg';
  /** Застаріле: колишня синя смуга; вигляд тепер один. */
  tone?: 'default' | 'on-shell';
  className?: string;
}

const SIZES = { sm: 'size-6 text-meta', md: 'size-8 text-caption', lg: 'size-10 text-ui' } as const;

/**
 * Аватар з ініціалами. Фотографій викладачів система не зберігає. Нейтральний: колір у системі
 * зарезервований для станів і «живого», тож людина не отримує випадкового відтінку.
 */
export function Avatar({ name, size = 'md', className }: AvatarProps) {
  return (
    <span
      aria-hidden="true"
      className={cn(
        'inline-flex shrink-0 items-center justify-center rounded-full bg-selected font-display leading-none font-semibold text-accent select-none',
        SIZES[size],
        className,
      )}
    >
      {/* «Розробник (dev)» → «Р»: пояснення в дужках ініціалів не дає */}
      {initials(name.replace(/\s*\([^)]*\)/g, '').trim() || name)}
    </span>
  );
}
