/** «Перейти до вмісту» — перший елемент у порядку Tab, видимий лише у фокусі. */
export function SkipLink({ target = 'main' }: { target?: string }) {
  return (
    <a
      href={`#${target}`}
      className="fixed start-3 top-3 z-[60] -translate-y-20 rounded-control bg-accent px-4 py-2 text-ui font-semibold text-on-accent shadow-popover transition-transform duration-150 focus-visible:translate-y-0 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus"
    >
      Перейти до вмісту
    </a>
  );
}
