import { useNavigation } from 'react-router';

/**
 * Тонка смужка вгорі, поки вантажиться наступна сторінка (код сторінки ділиться на частини).
 * Дотягується до 85 % і чекає — без безкінечного циклу; з'являється лише після 150 мс очікування.
 */
export function NavigationProgress() {
  const navigation = useNavigation();
  const busy = navigation.state !== 'idle';
  return (
    <div
      aria-hidden="true"
      className="pointer-events-none fixed inset-x-0 top-0 z-[70] h-0.5 overflow-hidden"
      style={{ opacity: busy ? 1 : 0, transition: busy ? 'opacity 150ms ease-out 150ms' : 'opacity 200ms ease-out' }}
    >
      {busy ? (
        <div className="h-full w-full origin-left bg-accent motion-safe:animate-[nav-progress_4s_var(--ease-out)_forwards] motion-reduce:scale-x-50" />
      ) : null}
    </div>
  );
}
