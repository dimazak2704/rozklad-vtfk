import { Search } from 'lucide-react';
import { Link, useLocation } from 'react-router';
import { Button } from '@/components/ui/Button';
import { buttonClass } from '@/components/ui/styles';
import { canonicalGroupCode } from '@/lib/search';
import { useCommandStore } from '@/stores/commands';

/** 404: людина, найімовірніше, шукала розклад — даємо пошук і, якщо в адресі код групи, пряме посилання. */
export default function NotFoundPage() {
  const location = useLocation();
  const openSearch = useCommandStore((state) => state.setOpen);
  const lastSegment = decodeURIComponent(location.pathname.split('/').filter(Boolean).pop() ?? '');
  const guessedGroup = canonicalGroupCode(lastSegment);

  return (
    <section className="mx-auto flex max-w-xl flex-col gap-4 py-10 sm:py-16">
      <p className="font-condensed text-numeral leading-none font-bold text-accent figures" aria-hidden="true">
        404
      </p>
      <h1 className="text-title font-semibold text-primary">Такої сторінки немає</h1>
      <p className="text-body text-secondary">
        Адреса <span className="font-semibold break-all text-primary">{location.pathname}</span> нікуди не веде. Можливо,
        посилання застаріло або в ньому одруківка.
      </p>
      <div className="flex flex-wrap gap-2">
        {guessedGroup ? (
          <Link to={`/g/${encodeURIComponent(guessedGroup)}`} className={buttonClass({ variant: 'primary' })}>
            Розклад групи {guessedGroup}
          </Link>
        ) : null}
        <Button
          variant={guessedGroup ? 'secondary' : 'primary'}
          iconStart={<Search aria-hidden="true" className="size-4" />}
          onClick={() => openSearch(true, guessedGroup ?? '')}
        >
          Знайти розклад
        </Button>
        <Link to="/" className={buttonClass({ variant: 'ghost' })}>
          На головну
        </Link>
      </div>
    </section>
  );
}
