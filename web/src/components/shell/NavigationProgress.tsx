import { useNavigation } from 'react-router';

/** Тонка смужка вгорі, поки вантажиться наступна сторінка (код сторінки ділиться на частини). */
export function NavigationProgress() {
  const navigation = useNavigation();
  const busy = navigation.state !== 'idle';
  return (
    <div
      aria-hidden="true"
      className="pointer-events-none fixed inset-x-0 top-0 z-[70] h-0.5 overflow-hidden"
      style={{ opacity: busy ? 1 : 0, transition: 'opacity 200ms ease-out' }}
    >
      <div className="h-full w-1/3 bg-accent motion-safe:animate-[nav-progress_1.1s_var(--ease-in-out)_infinite]" />
    </div>
  );
}
