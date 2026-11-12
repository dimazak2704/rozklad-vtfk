import { NavLink, useLocation, useNavigate } from 'react-router';
import { Select } from '@/components/ui';
import { cn } from '@/lib/cn';

const SECTIONS = [
  { to: '/admin/catalog/groups', label: 'Групи' },
  { to: '/admin/catalog/teachers', label: 'Викладачі' },
  { to: '/admin/catalog/rooms', label: 'Аудиторії' },
  { to: '/admin/catalog/disciplines', label: 'Дисципліни' },
  { to: '/admin/catalog/structure', label: 'Структура' },
] as const;

/**
 * Перемикач довідників під заголовком: на планшеті бічна панель схована, а між довідниками
 * переходять часто (додали групу → призначили куратора → перевірили аудиторію).
 * На телефоні п'ять вкладок не влазять у 360 px (а прокрутку вбік ніхто не помічає) — там це список.
 */
export function CatalogNav() {
  const { pathname } = useLocation();
  const navigate = useNavigate();
  const current = SECTIONS.find((section) => pathname.startsWith(section.to))?.to ?? SECTIONS[0].to;

  return (
    <>
      <div className="sm:hidden">
        <Select<string>
          aria-label="Довідник"
          className="w-full"
          value={current}
          onValueChange={(to) => void navigate(to)}
          options={SECTIONS.map((section) => ({ value: section.to, label: section.label }))}
        />
      </div>
      <nav aria-label="Довідники" className="-mb-px hidden border-b border-separator sm:block">
        <ul className="flex flex-wrap gap-1">
          {SECTIONS.map((section) => (
            <li key={section.to}>
              <NavLink
                to={section.to}
                className={({ isActive }) =>
                  cn(
                    'relative -mb-px inline-flex h-10 items-center border-b-2 px-3 text-ui font-semibold whitespace-nowrap pointer-coarse:h-11',
                    'transition-[color,border-color] duration-100 ease-out',
                    'focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-focus',
                    isActive ? 'border-accent text-primary' : 'border-transparent text-secondary hover:text-primary',
                  )
                }
              >
                {section.label}
              </NavLink>
            </li>
          ))}
        </ul>
      </nav>
    </>
  );
}

/**
 * Заголовок довідника: на екрані — «Довідники» (вкладки під ним уже кажуть, який саме, і «Групи»
 * не повторюється двічі поспіль), для читача екрана — «Довідники: Групи».
 */
export function CatalogTitle({ section }: { section: string }) {
  // приховану частину не дописуємо окремим span: Chromium відділяє позиціонований span пробілом («Довідники : Групи»)
  return (
    <>
      <span aria-hidden="true">Довідники</span>
      <span className="sr-only">Довідники: {section}</span>
    </>
  );
}
