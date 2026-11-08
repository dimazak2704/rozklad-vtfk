import { NavLink } from 'react-router';
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
 */
export function CatalogNav() {
  return (
    <nav aria-label="Довідники" className="scrollbar-none -mb-px overflow-x-auto border-b border-separator">
      <ul className="flex min-w-max gap-1">
        {SECTIONS.map((section) => (
          <li key={section.to}>
            <NavLink
              to={section.to}
              className={({ isActive }) =>
                cn(
                  'relative -mb-px inline-flex h-10 items-center border-b-2 px-3 text-ui font-semibold whitespace-nowrap',
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
  );
}
