import { WifiOff } from 'lucide-react';
import { EmptyState } from '@/components/ui';

/**
 * Даних на обрану дату ще немає, а запит чекає мережі (без інтернету TanStack ставить його на паузу):
 * пояснення замість вічних силуетів і замість даних іншої дати.
 */
export function OfflineState({ size = 'md' }: { size?: 'sm' | 'md' }) {
  return (
    <EmptyState
      size={size}
      icon={<WifiOff aria-hidden="true" className="size-5" />}
      title="Немає звʼязку з сервером"
      description="Дані на цю дату ще не завантажені, а без інтернету їх не отримати. Підключіться до мережі — сторінка оновиться сама."
    />
  );
}
