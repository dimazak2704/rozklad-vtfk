import type { ReactNode } from 'react';
import { BrandMark } from '@/components/shell/Wordmark';
import { Spinner } from '@/components/ui/Spinner';
import { cn } from '@/lib/cn';

interface AuthTransitionProps {
  /** Один короткий рядок: «Відкриваємо сторінку входу…». */
  text: string;
  /** Що показати, коли перехід затягнувся: пояснення й дія. */
  slow?: ReactNode;
  /** На весь екран (сторінка без оболонки) чи в межах оболонки сайту. */
  fullScreen?: boolean;
}

/**
 * Мить між сторінками входу: знак, рядок і кільце по центру. Зʼявляється за 300 мс і проявляється за 160 мс
 * (DESIGN.md §11.9), тож швидкий перехід проходить без спалаху розмітки.
 */
export function AuthTransition({ text, slow, fullScreen = false }: AuthTransitionProps) {
  return (
    <section
      className={cn(
        'mx-auto flex w-full max-w-sm flex-col items-center justify-center gap-4 px-4 text-center',
        fullScreen ? 'min-h-dvh' : 'min-h-[min(60dvh,28rem)]',
      )}
    >
      {/* запасний блок висить під рядком, а не в потоці: знак і рядок не підскакують, коли він зʼявляється */}
      <div className="relative">
        <div className="flex flex-col items-center gap-4 motion-safe:animate-shimmer">
          <BrandMark size={40} />
          <p role="status" aria-live="polite" className="flex items-center gap-2.5 text-ui text-secondary">
            {/* кільце лише видно: про стан каже рядок поруч, другої живої області не треба */}
            <span aria-hidden="true" className="inline-flex">
              <Spinner size="sm" label="" className="text-secondary" />
            </span>
            {text}
          </p>
        </div>
        {slow ? (
          <div className="absolute top-full left-1/2 mt-6 flex w-[min(20rem,calc(100vw-2rem))] -translate-x-1/2 flex-col items-center gap-3 text-balance motion-safe:animate-[fade-in_160ms_var(--ease-out)_both]">
            {slow}
          </div>
        ) : null}
      </div>
    </section>
  );
}
