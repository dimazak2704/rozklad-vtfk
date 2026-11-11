import { Search } from 'lucide-react';
import { Link } from 'react-router';
import { BackLink, Button } from '@/components/ui';
import { useCommandStore } from '@/stores/commands';

export interface TargetSuggestion {
  label: string;
  to: string;
  /** Код групи чи аудиторії: цифри — табличними (Geologica). */
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

/** Невідома група / викладач / аудиторія: один шлях назад («‹ Розклад»), схожі варіанти й пошук замість глухого «не знайдено». */
export function TargetNotFound({ title, description, suggestions = [], searchQuery = '' }: TargetNotFoundProps) {
  const openSearch = useCommandStore((state) => state.setOpen);
  return (
    <section className="flex max-w-2xl flex-col gap-4 py-2 sm:py-8">
      <BackLink to="/" label="Розклад" />
      <h1 className="font-display text-[1.5rem] leading-[1.875rem] font-semibold tracking-[-0.02em] text-balance text-primary sm:text-headline">
        {title}
      </h1>
      <p className="max-w-prose text-body text-secondary">{description}</p>
      {suggestions.length > 0 ? (
        <div className="flex flex-col gap-1">
          <h2 className="text-ui font-semibold text-secondary">Можливо, ви шукали</h2>
          <ul className="-ms-2 flex flex-wrap">
            {suggestions.map((suggestion) => (
              <li key={suggestion.to}>
                <Link
                  to={suggestion.to}
                  className="inline-flex h-11 min-w-14 items-center justify-center rounded-control px-2 text-ui font-semibold text-accent hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus"
                >
                  <span className={suggestion.plate ? 'figures' : undefined}>{suggestion.label}</span>
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
      </div>
    </section>
  );
}
