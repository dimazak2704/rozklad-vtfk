import type { IsoDate, IsoInstant, Nullable, OrderKind, OrderRowType, OrderStatus, Uuid } from './common';

// ───────────────────────── 2. Admin: розпорядження ─────────────────────────

/** Рядок списку GET /api/admin/orders (Page) */
export interface OrderSummaryDto {
  id: Uuid;
  number: Nullable<number>;
  date: IsoDate;
  kind: OrderKind;
  status: OrderStatus;
  itemCount: number;
  publishedAt: Nullable<IsoInstant>;
  /** Імпортоване паперове розпорядження: без файлів DOCX/PDF/PNG. */
  imported: boolean;
}

export interface OrderItemDto {
  id: Uuid;
  groupCode: string;
  pairLabel: string;
  rowType: OrderRowType;
  fromDiscipline: Nullable<string>;
  fromTeacher: Nullable<string>;
  reasonText: Nullable<string>;
  toDiscipline: Nullable<string>;
  toTeacher: Nullable<string>;
  roomText: Nullable<string>;
  changeId: Nullable<Uuid>;
}

export interface OrderDto {
  id: Uuid;
  number: Nullable<number>;
  date: IsoDate;
  kind: OrderKind;
  parentId: Nullable<Uuid>;
  status: OrderStatus;
  /** «РОЗПОРЯДЖЕННЯ» (чернетка), «РОЗПОРЯДЖЕННЯ №35», «ДОПОВНЕННЯ до розпорядження №35» (власний номер — `number`). */
  title: string;
  weekdayLabel: string;
  parityLabel: string;
  items: OrderItemDto[];
  roomChanges: OrderItemDto[];
  notes: string[];
  signatoryPosition: string;
  signatoryName: string;
  /** Адитивні поля core: рядок під заголовком («По коледжу про деякі зміни в розкладі занять»). */
  subtitle: string;
  /** «На 19 жовтня 2026 р.» */
  dateLabel: string;
  publishedAt: Nullable<IsoInstant>;
  publishedBy: Nullable<string>;
  version: number;
  /** Імпортоване паперове розпорядження (лише текст): файлів DOCX/PDF/PNG немає. */
  imported: boolean;
}

/** GET /api/admin/orders/numbering?semesterId — наступний номер = max(останній + 1, перший). */
export interface OrderNumberingDto {
  semesterId: Uuid;
  firstNumber: number;
  lastNumber: Nullable<number>;
  nextNumber: number;
}

export interface UpdateOrderNotesRequest {
  notes: string[];
  /** `version` чернетки, яку редагував диспетчер: застаріла → 409 `stale-data` (примітки друкуються в розпорядженні). */
  version: number;
}
