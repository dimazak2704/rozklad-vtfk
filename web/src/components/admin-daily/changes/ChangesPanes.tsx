import { ChevronLeft } from 'lucide-react';
import type { ReactNode } from 'react';
import { cn } from '@/lib/cn';

/**
 * Колонка варіантів тримається під верхньою панеллю, а прокручується всередині себе. Смуга прокрутки відступає
 * від заокругленого кута панелі (my/me), місце під неї зарезервоване (без стрибка ширини), а низ тихо згасає —
 * видно, що далі є ще варіанти.
 */
const STICKY = 'lg:sticky lg:top-[calc(var(--sticky-top)+1rem)] lg:my-2.5 lg:me-1.5';
const STICKY_SCROLL =
  'lg:max-h-[calc(100dvh-var(--sticky-top)-2.5rem)] lg:overflow-y-auto lg:overscroll-contain lg:[scrollbar-gutter:stable] lg:[scrollbar-width:thin] ' +
  // slop-allow: маска прозорості (не колір): низ колонки тихо згасає, показуючи, що далі є ще варіанти
  'lg:pb-8 lg:[mask-image:linear-gradient(to_bottom,#000_calc(100%-1.5rem),transparent)]';

function DecisionColumn({ children }: { children: ReactNode }) {
  return (
    <aside aria-label="Рішення" className="min-w-0 border-s border-separator">
      <div className={STICKY}>
        <div className={cn('p-5 lg:py-2.5 lg:ps-5 lg:pe-3.5', STICKY_SCROLL)}>{children}</div>
      </div>
    </aside>
  );
}

interface ChangesPanesProps {
  panes: number;
  /** Список відсутніх; `drill` — телефон: рядок веде на рівень пар. */
  absenceList: (drill: boolean) => ReactNode;
  /** Пари обраного відсутнього; `before` — що показати над ними (дорога назад на телефоні). */
  lessons: (before?: ReactNode) => ReactNode;
  /** Колонка варіантів; `showTitle` — з заголовком пари (у шторці його показує сама шторка). */
  optionsPanel: (showTitle: boolean) => ReactNode;
  /** Рядок відсутніх над двома колонками (ноутбук). */
  absenceStrip: ReactNode;
  /** Окремі зміни без відсутності. */
  othersList: ReactNode;
  hasOthers: boolean;
  /** Телефон: чи відкритий рівень пар обраного відсутнього. */
  absenceOpen: boolean;
  onBackToAbsences: () => void;
}

/**
 * Робоча панель замін. Широкий екран — одна панель з трьома колонками; ноутбук — відсутні рядком над двома
 * колонками; телефон і планшет — по рівнях: відсутні → пари (варіанти — у шторці), з дорогою назад.
 */
export function ChangesPanes(props: ChangesPanesProps) {
  const { panes, absenceList, lessons, optionsPanel, othersList, hasOthers } = props;
  if (panes === 3) {
    return (
      <div className="grid grid-cols-[28fr_32fr_40fr] items-stretch rounded-panel border border-default bg-surface">
        <section aria-labelledby="absent-heading" className="flex min-w-0 flex-col gap-2 p-3">
          <h2 id="absent-heading" className="px-3 pt-2 text-ui font-[650] text-secondary">
            Відсутні
          </h2>
          {absenceList(false)}
          {othersList}
        </section>
        <div className="min-w-0 border-s border-separator p-3 pt-5">{lessons()}</div>
        <DecisionColumn>{optionsPanel(true)}</DecisionColumn>
      </div>
    );
  }
  if (panes === 2) {
    return (
      <div className="flex flex-col gap-4">
        {props.absenceStrip}
        <div className="grid grid-cols-[minmax(0,1fr)_minmax(0,1.2fr)] items-stretch rounded-panel border border-default bg-surface">
          <div className="min-w-0 p-3 pt-5">{lessons()}</div>
          <DecisionColumn>{optionsPanel(true)}</DecisionColumn>
        </div>
        {hasOthers ? <div className="rounded-panel border border-default bg-surface p-3">{othersList}</div> : null}
      </div>
    );
  }
  // телефон і вузький планшет: по рівнях, і з кожного видно дорогу назад
  return (
    <div className="rounded-panel border border-default bg-surface p-2 pt-3">
      {props.absenceOpen ? (
        lessons(
          <button
            type="button"
            onClick={props.onBackToAbsences}
            className="ms-1 inline-flex min-h-9 w-fit items-center gap-0.5 rounded-control pe-2 text-ui font-[550] text-accent focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus pointer-coarse:min-h-11"
          >
            <ChevronLeft aria-hidden="true" className="size-[1.125rem] rtl:rotate-180" />
            Усі відсутні
          </button>,
        )
      ) : (
        <>
          {absenceList(true)}
          {othersList}
        </>
      )}
    </div>
  );
}
