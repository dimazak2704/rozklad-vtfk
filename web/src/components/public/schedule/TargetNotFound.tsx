import { Search } from 'lucide-react';
import { Link } from 'react-router';
import { Button, buttonClass, Plate } from '@/components/ui';
import { useCommandStore } from '@/stores/commands';

export interface TargetSuggestion {
  label: string;
  to: string;
  /** Показати як табличку (коди груп і аудиторій). */
  plate?: boolean;
}

interface TargetNotFoundProps {
  /** «Групи 4КІ9 немає в розкладі». */
  title: string;
  description: string;
  suggestions?: readonly TargetSuggestion[];
  /** Початковий текст пошуку в палітрі. */
  searchQuery?: string;
}

/** Невідома група / викладач / аудиторія: схожі варіанти й пошук замість глухого «не знайдено». */
export function TargetNotFound({ title, description, suggestions = [], searchQuery = '' }: TargetNotFoundProps) {
  const openSearch = useCommandStore((state) => state.setOpen);
  return (
    <section className="flex max-w-2xl flex-col gap-4 py-6 sm:py-10">
      <h1 className="text-title font-semibold text-primary">{title}</h1>
      <p className="text-body text-secondary">{description}</p>
      {suggestions.length > 0 ? (
        <div className="flex flex-col gap-2">
          <h2 className="text-ui font-semibold text-primary">Можливо, ви шукали</h2>
          <ul className="flex flex-wrap gap-2">
            {suggestions.map((suggestion) => (
              <li key={suggestion.to}>
                <Link
                  to={suggestion.to}
                  className="inline-flex rounded-plate focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus"
                >
                  {suggestion.plate ? (
                    <Plate size="lg" className="hover:border-accent hover:text-accent">
                      {suggestion.label}
                    </Plate>
                  ) : (
                    <span className="rounded-control border border-default bg-surface px-3 py-1.5 text-ui font-semibold text-primary hover:border-strong">
                      {suggestion.label}
                    </span>
                  )}
                </Link>
              </li>
            ))}
          </ul>
        </div>
      ) : null}
      <div className="flex flex-wrap gap-2">
        <Button variant="primary" iconStart={<Search aria-hidden="true" className="size-4" />} onClick={() => openSearch(true, searchQuery)}>
          Знайти розклад
        </Button>
        <Link to="/" className={buttonClass({ variant: 'ghost' })}>
          На головну
        </Link>
      </div>
    </section>
  );
}
