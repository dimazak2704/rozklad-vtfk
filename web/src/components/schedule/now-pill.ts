/** Половина висоти пігулки «зараз» (20 px). */
const PILL_REACH = 10;
/** Рядок підпису вищий за самі цифри: зверху й знизу по ~3 px порожнього міжрядкового простору. */
const CAPTION_LEADING = 3;

interface Span {
  top: number;
  bottom: number;
}

/**
 * На скільки пікселів зсунути пігулку «зараз» по вертикалі, щоб вона не лягла на підписи колонки часу
 * (`labels` — ті, що під нею по горизонталі): 0 — стоїть на точній хвилині; інакше — до найближчого вільного
 * місця над чи під підписами в межах клітинки. Точну хвилину й далі показує штрих біля пігулки.
 */
export function nowPillShift(centre: number, cell: Span, labels: readonly Span[]): number {
  const blocked = labels.map((label) => ({
    top: label.top + CAPTION_LEADING - PILL_REACH,
    bottom: label.bottom - CAPTION_LEADING + PILL_REACH,
  }));
  if (!blocked.some((b) => centre > b.top && centre < b.bottom)) return 0;
  // підписи стоять стовпчиком: зона, що зачіпає центр, разом із сусідніми, які на неї налягають
  let top = centre;
  let bottom = centre;
  let grew = true;
  while (grew) {
    grew = false;
    for (const b of blocked) {
      if (b.bottom > top && b.top < bottom && (b.top < top || b.bottom > bottom)) {
        top = Math.min(top, b.top);
        bottom = Math.max(bottom, b.bottom);
        grew = true;
      }
    }
  }
  const free = [bottom, top].filter((y) => y >= cell.top + PILL_REACH && y <= cell.bottom - PILL_REACH);
  // the free edge nearest to the centre; centre itself when neither edge fits (no shift)
  const best = free.reduce<number | null>((a, b) => (a !== null && Math.abs(a - centre) <= Math.abs(b - centre) ? a : b), null);
  return best === null ? 0 : best - centre;
}
