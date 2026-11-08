import { ClipboardCopy, FileDown } from 'lucide-react';
import { useState } from 'react';
import { canCopyImages, copyOrderPng, DOCUMENT_LABEL, downloadOrderFile } from '@/api/admin-daily';
import type { DocumentFormat, OrderDto } from '@/api/types';
import { Button, notify } from '@/components/ui';

const FORMATS: readonly DocumentFormat[] = ['docx', 'pdf', 'png'];

/**
 * Файли опублікованого розпорядження (сервіс notification): DOCX для архіву, PDF для друку, PNG для чатів,
 * і «Копіювати PNG» — щоб вставити в Instagram чи месенджер без збереження файлу.
 */
export function OrderFiles({ order }: { order: OrderDto }) {
  const [busy, setBusy] = useState<DocumentFormat | 'copy' | null>(null);

  const download = async (format: DocumentFormat) => {
    setBusy(format);
    try {
      await downloadOrderFile(order, format);
    } catch (error) {
      notify.error(error, `Не вдалося завантажити ${DOCUMENT_LABEL[format]}`);
    } finally {
      setBusy(null);
    }
  };

  const copy = async () => {
    setBusy('copy');
    try {
      await copyOrderPng(order.id);
      notify.success('Зображення скопійовано', { description: 'Вставте його в Instagram чи чат (Ctrl+V).' });
    } catch (error) {
      notify.error(error, 'Не вдалося скопіювати зображення');
    } finally {
      setBusy(null);
    }
  };

  return (
    <div className="flex flex-col gap-2">
      <div className="grid grid-cols-3 gap-2">
        {FORMATS.map((format) => (
          <Button
            key={format}
            size="sm"
            variant="secondary"
            loading={busy === format}
            disabled={busy !== null}
            onClick={() => void download(format)}
            iconStart={<FileDown aria-hidden="true" className="size-4" />}
            aria-label={`Завантажити ${DOCUMENT_LABEL[format]}`}
          >
            {DOCUMENT_LABEL[format]}
          </Button>
        ))}
      </div>
      <Button
        size="sm"
        variant="secondary"
        loading={busy === 'copy'}
        disabled={busy !== null || !canCopyImages()}
        onClick={() => void copy()}
        iconStart={<ClipboardCopy aria-hidden="true" className="size-4" />}
      >
        Копіювати PNG
      </Button>
      {!canCopyImages() ? <p className="text-caption text-muted">Цей браузер не копіює зображення — завантажте PNG.</p> : null}
    </div>
  );
}
