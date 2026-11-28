/** Скільки тримати посилання на Blob після кліку: деякі браузери забирають файл не одразу. */
const REVOKE_DELAY_MS = 10_000;

/** Зберегти Blob як файл (посилання з атрибутом download). */
export function saveBlob(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob);
  try {
    const link = document.createElement('a');
    link.href = url;
    link.download = fileName;
    link.rel = 'noopener';
    document.body.append(link);
    link.click();
    link.remove();
  } finally {
    // посилання вже клікнули: памʼять можна звільнити трохи згодом
    setTimeout(() => URL.revokeObjectURL(url), REVOKE_DELAY_MS);
  }
}
