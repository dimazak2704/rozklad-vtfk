import type { OrderDto, OrderItemDto } from '@/api/types';

/** «СТП · Кренцін» — одна сторона рядка без висячих розділювачів; кілька підгруп — через кому. */
function side(discipline: string | null, teacher: string | null): string {
  return [discipline, teacher]
    .filter(Boolean)
    .map((part) => (part ?? '').replaceAll('\n', ', '))
    .join(' · ');
}

/** Що стало з парою — словами, як читають документ уголос. */
function Change({ item }: { item: OrderItemDto }) {
  const before = side(item.fromDiscipline, item.fromTeacher);
  const after = side(item.toDiscipline, item.toTeacher);
  switch (item.rowType) {
    case 'CANCELLATION':
      return (
        <>
          {before ? <s className="text-muted decoration-[var(--fill-cancel)]">{before}</s> : null} <span className="font-[650] text-cancel">пари не буде</span>
        </>
      );
    case 'ADDITIONAL':
      return (
        <>
          <span className="text-add">Додатково:</span> <span className="font-[650] text-primary">{after}</span>
        </>
      );
    case 'AS_SCHEDULED':
      return (
        <>
          <span className="text-secondary">За розкладом</span>
          {after ? (
            <>
              {' '}
              → <span className="font-[650] text-primary">{after}</span>
            </>
          ) : null}
        </>
      );
    case 'MERGE_SUBGROUPS':
      return (
        <>
          {before ? <s className="text-muted">{before}</s> : null} →{' '}
          <span className="font-[650] text-primary">
            обидві підгрупи: {after || '—'}
          </span>
        </>
      );
    case 'REPLACEMENT':
    case 'BLOCK':
    case 'FINISHED':
    case 'ROOM_CHANGE':
      return (
        <>
          {before ? <s className="text-muted">{before}</s> : null}
          {before && after ? <span className="text-muted"> → </span> : null}
          {after ? <span className="font-[650] text-primary">{after}</span> : null}
        </>
      );
  }
}

/**
 * Рядки розпорядження для телефона: аркуш A4 у 360 px має текст 6–7 px, тож ті самі рядки — читабельним
 * списком: «4КІ1, 2 пара», «~~СТП · Кренцін~~ → НДКС · Непийвода», «Зв.зам. · ауд. 6».
 */
export function OrderRows({ order }: { order: OrderDto }) {
  return (
    <div className="flex flex-col gap-4">
      {order.items.length > 0 ? (
        <ul aria-label="Рядки розпорядження" className="flex flex-col divide-y divide-[var(--border-color-separator)]">
          {order.items.map((item) => (
            <li key={item.id} className="flex flex-col gap-0.5 py-3">
              <span className="text-caption font-semibold text-secondary">
                <span className="figures">{item.groupCode}</span>, <span className="figures">{item.pairLabel}</span> пара
              </span>
              <span className="text-cell">
                <Change item={item} />
              </span>
              {item.reasonText || item.roomText ? (
                <span className="text-caption text-muted">
                  {[item.reasonText, item.roomText ? `ауд. ${item.roomText.replaceAll('\n', ', ')}` : null].filter(Boolean).join(' · ')}
                </span>
              ) : null}
            </li>
          ))}
        </ul>
      ) : null}
      {order.roomChanges.length > 0 ? (
        <section aria-label="Заміна аудиторій" className="flex flex-col gap-1">
          <h3 className="text-ui font-[650] text-secondary">Заміна аудиторій</h3>
          <ul className="flex flex-col divide-y divide-[var(--border-color-separator)]">
            {order.roomChanges.map((item) => (
              <li key={item.id} className="py-2.5 text-ui text-primary">
                <span className="figures">{item.groupCode}</span>, <span className="figures">{item.pairLabel}</span> пара — ауд.{' '}
                <span className="font-[650]">{(item.roomText ?? '—').replaceAll('\n', ', ')}</span>
              </li>
            ))}
          </ul>
        </section>
      ) : null}
      {order.notes.length > 0 ? (
        <div className="flex flex-col gap-1 text-ui font-semibold text-primary">
          {order.notes.map((note, index) => (
            <p key={`${index}-${note}`}>{note}</p>
          ))}
        </div>
      ) : null}
    </div>
  );
}
