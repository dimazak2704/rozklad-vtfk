import { QRCodeSVG } from 'qrcode.react';
import { cn } from '@/lib/cn';

interface QrPlateProps {
  value: string;
  /** Розмір коду в пікселях (без поля). */
  size?: number;
  /** Що відкриє код — для читача екрана. */
  label: string;
  className?: string;
}

/**
 * QR-код на білій табличці: у темній темі теж темні модулі на світлому полі, інакше частина
 * сканерів його не прочитає. Колір модулів — поточне чорнило світлого «острівця».
 */
export function QrPlate({ value, size = 168, label, className }: QrPlateProps) {
  return (
    <div data-theme="light" className={cn('inline-flex shrink-0 rounded-panel border border-default bg-surface p-3 text-primary', className)}>
      <QRCodeSVG value={value} size={size} level="M" marginSize={0} bgColor="transparent" fgColor="currentColor" title={label} role="img" />
    </div>
  );
}
