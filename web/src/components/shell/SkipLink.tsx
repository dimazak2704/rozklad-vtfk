/** «Перейти до вмісту» — перший елемент у порядку Tab, видимий лише у фокусі. */
export function SkipLink({ target = 'main' }: { target?: string }) {
  return (
    <a
      href={`#${target}`}
      className="sr-only focus-visible:not-sr-only focus-visible:fixed focus-visible:start-3 focus-visible:top-3 focus-visible:z-[60] focus-visible:rounded-control focus-visible:bg-accent focus-visible:px-4 focus-visible:py-2 focus-visible:text-ui focus-visible:font-semibold focus-visible:text-on-accent focus-visible:shadow-popover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus"
    >
      Перейти до вмісту
    </a>
  );
}
