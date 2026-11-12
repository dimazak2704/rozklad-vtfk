import { Search } from 'lucide-react';
import { Link, useLocation } from 'react-router';
import { Button } from '@/components/ui/Button';
import { BackLink } from '@/components/ui/PageHeader';
import { buttonClass } from '@/components/ui/styles';
import { canonicalGroupCode } from '@/lib/search';
import { useCommandStore } from '@/stores/commands';

function safeDecode(value: string): string {
  try {
    return decodeURIComponent(value);
  } catch {
    return value;
  }
}

/** Куди веде «‹ …»: на головну свого розділу — панелі диспетчера, кабінету чи публічного розкладу. */
function homeOf(pathname: string): { to: string; label: string } {
  if (pathname === '/admin' || pathname.startsWith('/admin/')) return { to: '/admin', label: 'Сьогодні' };
  if (pathname === '/me' || pathname.startsWith('/me/')) return { to: '/me', label: 'Мій розклад' };
  return { to: '/', label: 'Розклад' };
}

/**
 * 404 — одне речення і шлях далі, без велетенського «404». Людина, найімовірніше, шукала розклад:
 * даємо пошук, а якщо в адресі схоже на код групи — пряме посилання на неї. Шлях додому один — на
 * головну свого розділу («‹ Розклад», у панелі — «‹ Сьогодні», у кабінеті — «‹ Мій розклад»).
 */
export default function NotFoundPage() {
  const location = useLocation();
  const home = homeOf(location.pathname);
  const openSearch = useCommandStore((state) => state.setOpen);
  const lastSegment = safeDecode(location.pathname.split('/').filter(Boolean).pop() ?? '');
  const guessedGroup = canonicalGroupCode(lastSegment);

  return (
    <section className="flex max-w-xl flex-col gap-4 py-2 sm:py-8">
      <BackLink to={home.to} label={home.label} />
      <h1 className="font-display text-[1.5rem] leading-[1.875rem] font-semibold tracking-[-0.02em] text-primary sm:text-headline">
        Такої сторінки немає
      </h1>
      <p className="text-body text-secondary">
        Можливо, посилання застаріло або в ньому одруківка.
        {guessedGroup ? ` Схоже, ви шукали групу ${guessedGroup}.` : ' Знайдіть потрібний розклад пошуком.'}
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
      </div>
    </section>
  );
}
