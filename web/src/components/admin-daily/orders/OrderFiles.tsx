import { ClipboardCopy, FileDown } from 'lucide-react';
import { useState, type ReactNode } from 'react';
import { canCopyImages, copyOrderPng, DOCUMENT_LABEL, downloadOrderFile } from '@/api/admin-daily';
import type { DocumentFormat, OrderDto } from '@/api/types';
import { Button, notify, type MoreMenuItem } from '@/components/ui';

/**
 * Файли опублікованого розпорядження (сервіс notification): PDF для друку — одна видима кнопка, а DOCX для
 * архіву, PNG для чатів і «Копіювати PNG» (вставити в Instagram чи месенджер без збереження) — у «⋯».
 * Імпортоване паперове розпорядження — лише текст: файлів немає.
 */
export function useOrderFiles(order: OrderDto | undefined): { pdfButton: ReactNode; menu: MoreMenuItem[] } {
  const [busy, setBusy] = useState<DocumentFormat | 'copy' | null>(null);
  if (!order || order.status !== 'PUBLISHED' || order.imported) return { pdfButton: null, menu: [] };

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
      notify.success('Зображення скопійовано — вставте його в Instagram чи чат (Ctrl+V)');
    } catch (error) {
      notify.error(error, 'Не вдалося скопіювати зображення');
    } finally {
      setBusy(null);
    }
  };

  const pdfButton = (
    <Button
      variant="secondary"
      loading={busy === 'pdf'}
      disabled={busy !== null}
      onClick={() => void download('pdf')}
      iconStart={<FileDown aria-hidden="true" className="size-4" />}
      aria-label={`Завантажити ${DOCUMENT_LABEL.pdf}`}
    >
      {DOCUMENT_LABEL.pdf}
    </Button>
  );

  const menu: MoreMenuItem[] = [
    { label: `Завантажити ${DOCUMENT_LABEL.docx}`, icon: <FileDown aria-hidden="true" />, onSelect: () => void download('docx'), disabled: busy !== null },
    { label: `Завантажити ${DOCUMENT_LABEL.png}`, icon: <FileDown aria-hidden="true" />, onSelect: () => void download('png'), disabled: busy !== null },
    // браузер без копіювання зображень (Firefox без дозволу) — пункт прихований, а PNG можна завантажити
    ...(canCopyImages()
      ? [{ label: 'Копіювати PNG', icon: <ClipboardCopy aria-hidden="true" />, onSelect: () => void copy(), disabled: busy !== null }]
      : []),
  ];
  return { pdfButton, menu };
}
