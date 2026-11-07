import { toast, type ExternalToast } from 'sonner';
import { isApiError } from '@/api/errors';

type Options = Pick<ExternalToast, 'description' | 'duration' | 'id'>;

export interface UndoOptions extends Options {
  onUndo: () => void;
  /** Підпис кнопки, за замовчуванням «Скасувати». */
  undoLabel?: string;
}

/**
 * Сповіщення інтерфейсу. Текст — назва результату тими ж словами, що й дія:
 * кнопка «Опублікувати» → `notify.success('Розпорядження №35 опубліковано')`.
 */
export const notify = {
  success: (message: string, options?: Options) => toast.success(message, options),
  info: (message: string, options?: Options) => toast.info(message, options),
  warning: (message: string, options?: Options) => toast.warning(message, options),

  /**
   * Помилка: заголовок — що не вдалося (`fallback`), опис — чому (текст сервера) і код запиту.
   * Рядок замість помилки показується як є: `notify.error('Файл завеликий')`.
   */
  error: (error: unknown, fallback = 'Не вдалося виконати дію', options?: Options) => {
    if (typeof error === 'string') return toast.error(error, { duration: 8000, ...options });
    let description: string | undefined;
    if (isApiError(error)) {
      description = error.requestId ? `${error.message} Код запиту: ${error.requestId}` : error.message;
    } else if (error instanceof Error && error.message) {
      description = error.message;
    }
    return toast.error(fallback, { description, duration: 8000, ...options });
  },

  /** Оптимістична дія з можливістю скасувати (6 с). */
  undo: (message: string, { onUndo, undoLabel = 'Скасувати', duration = 6000, ...options }: UndoOptions) =>
    toast(message, {
      duration,
      ...options,
      action: { label: undoLabel, onClick: onUndo },
    }),

  /** Довга дія: «Публікуємо…» → «Опубліковано» / помилка. */
  promise: <T>(promise: Promise<T>, messages: { loading: string; success: string | ((value: T) => string); error?: string }) =>
    toast.promise(promise, {
      loading: messages.loading,
      success: messages.success,
      error: (error: unknown) => (isApiError(error) ? error.message : (messages.error ?? 'Не вдалося виконати дію')),
    }),

  dismiss: (id?: string | number) => toast.dismiss(id),
};
