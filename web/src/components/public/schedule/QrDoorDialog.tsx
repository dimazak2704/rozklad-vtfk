import { Copy, Download, Printer } from 'lucide-react';
import { useRef } from 'react';
import { saveBlob } from '@/api/public';
import { Button, Dialog, DialogContent, IconButton, notify } from '@/components/ui';
import { QrPlate } from '../QrPlate';

interface QrDoorDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** «4КІ1», «ауд. 301». */
  title: string;
  /** Повна адреса сторінки розкладу. */
  url: string;
  /** Імʼя файлу SVG без розширення. */
  fileName: string;
  onPrint: () => void;
}

/**
 * QR-код сторінки розкладу для дверей аудиторії: студент наводить камеру — і бачить розклад із замінами
 * на сьогодні. Друк таблички (A4) або SVG для власного макета.
 */
export function QrDoorDialog({ open, onOpenChange, title, url, fileName, onPrint }: QrDoorDialogProps) {
  const plate = useRef<HTMLDivElement>(null);

  const downloadSvg = () => {
    const svg = plate.current?.querySelector('svg');
    if (!svg) return;
    const copy = svg.cloneNode(true) as SVGSVGElement;
    copy.setAttribute('xmlns', 'http://www.w3.org/2000/svg');
    // поза сторінкою «поточного кольору» немає — фіксуємо чорний
    const markup = copy.outerHTML.replaceAll('currentColor', 'black');
    saveBlob(new Blob([markup], { type: 'image/svg+xml' }), `${fileName}.svg`);
  };

  const copyUrl = async () => {
    try {
      await navigator.clipboard.writeText(url);
      notify.success('Посилання скопійовано');
    } catch {
      notify.error('Не вдалося скопіювати посилання.');
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        size="sm"
        title={`QR-код: ${title}`}
        description="Роздрукуйте й повісьте на двері — камера телефона відкриє розклад із сьогоднішніми замінами."
        footer={
          <>
            <Button variant="secondary" iconStart={<Download aria-hidden="true" className="size-4" />} onClick={downloadSvg}>
              SVG
            </Button>
            <Button
              variant="primary"
              iconStart={<Printer aria-hidden="true" className="size-4" />}
              onClick={() => {
                onOpenChange(false);
                onPrint();
              }}
            >
              Друкувати табличку
            </Button>
          </>
        }
      >
        <div className="flex flex-col items-center gap-3 pt-1">
          <div ref={plate}>
            <QrPlate value={url} size={208} label={`Розклад ${title}`} />
          </div>
          <div className="flex w-full min-w-0 items-center gap-1 rounded-control border border-default bg-sunken ps-3">
            <span className="min-w-0 flex-1 truncate text-caption text-secondary" title={url}>
              {url}
            </span>
            <IconButton label="Копіювати посилання" size="sm" icon={<Copy aria-hidden="true" className="size-4" />} onClick={() => void copyUrl()} />
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}
