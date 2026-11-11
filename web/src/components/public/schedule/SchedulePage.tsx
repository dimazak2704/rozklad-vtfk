import type { UseQueryResult } from '@tanstack/react-query';
import { X } from 'lucide-react';
import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { usePublicMeta } from '@/api/meta';
import type { ScheduleDto } from '@/api/types';
import { PageErrorScope, PageHeader, ParityBadge } from '@/components/ui';
import type { Perspective } from '@/lib/schedule';
import { useFavoritesStore, type ScheduleTarget } from '@/stores/favorites';
import { SchedulePrint } from '../print/SchedulePrint';
import { usePrintMode } from '../print/usePrintMode';
import { useDocumentTitle } from '../useDocumentTitle';
import { ChangesNote } from './ChangesNote';
import { QrDoorDialog } from './QrDoorDialog';
import type { IcsTarget } from './ScheduleActions';
import { useScheduleMenuItems } from './useScheduleMenuItems';
import { ScheduleControls, ScheduleExplorer } from './ScheduleExplorer';
import type { ScheduleParams } from './schedule-params';
import { changedLessons, dayOf, dayShareText, filterBySubgroup, semesterWeekOf } from './schedule-utils';

interface SchedulePageProps {
  /** Ціль для обраного й нещодавніх: тип, id, підпис, маршрут у застосунку. */
  target: ScheduleTarget;
  /** «Розклад групи», «Розклад викладача», «Розклад аудиторії» — для друку й читача екрана. */
  kindLabel: string;
  /** Заголовок вкладки браузера: «4КІ1 — розклад групи». */
  documentTitle: string;
  /** Поруч із назвою, дрібніше: «Компʼютерна інженерія · 4 курс». */
  details?: ReactNode;
  /** Ще один факт у рядку контексту (аудиторія: «Зараз вільна до кінця дня»). */
  status?: ReactNode;
  perspective: Perspective;
  query: UseQueryResult<ScheduleDto>;
  params: ScheduleParams;
  subgroups?: readonly number[];
  ics?: IcsTarget | null;
  /** Табличка з QR-кодом на двері (групи й аудиторії). */
  doorSign?: boolean;
}

/**
 * Каркас сторінок /g, /t, /r: «‹ Розклад», назва великим, один рядок контексту (парність, заміни тижня),
 * два керування (День/Тиждень, тиждень) і «⋯» з усім іншим. Запамʼятовує ціль як останню переглянуту —
 * головна покаже її розклад.
 */
export function SchedulePage({
  target,
  kindLabel,
  documentTitle,
  details,
  status,
  perspective,
  query,
  params,
  subgroups = [],
  ics,
  doorSign = false,
}: SchedulePageProps) {
  const meta = usePublicMeta();
  const { mode, print } = usePrintMode();
  const [qrOpen, setQrOpen] = useState(false);
  const url = `${window.location.origin}${target.url}`;
  const visible = useMemo(() => (query.data ? filterBySubgroup(query.data, params.subgroup) : undefined), [query.data, params.subgroup]);
  const placeholder = query.isPlaceholderData;
  const changes = useMemo(() => (placeholder ? [] : changedLessons(visible)), [visible, placeholder]);
  const parity = placeholder ? null : (visible?.days[0]?.parity ?? null);
  const weekNumber = semesterWeekOf(params.date, meta.data?.semester);
  const printTitle = params.subgroup ? `${target.label} · ${params.subgroup} підгрупа` : target.label;

  useDocumentTitle(documentTitle);

  useEffect(() => {
    useFavoritesStore.getState().rememberView({ type: target.type, id: target.id, label: target.label, url: target.url });
  }, [target.type, target.id, target.label, target.url]);

  const more = useScheduleMenuItems({
    target,
    share: {
      title: `Розклад ${target.label}`,
      text: () => dayShareText(target.label, dayOf(visible, params.date), perspective),
      url,
    },
    ics,
    onPrintWeek: () => print('week'),
    onQr: doorSign ? () => setQrOpen(true) : null,
    subgroups,
    subgroup: params.subgroup,
    onSubgroup: params.setSubgroup,
  });

  const openDay = (date: string) => {
    params.setDate(date);
    if (params.view !== 'day') params.setView('day');
  };

  const headerMeta = (
    <>
      {parity ? <ParityBadge parity={parity} size="sm" /> : null}
      {params.subgroup ? (
        <span className="inline-flex items-center gap-1">
          <span aria-hidden="true">·</span>
          Лише {params.subgroup} підгрупа
          <button
            type="button"
            onClick={() => params.setSubgroup(null)}
            aria-label="Показати всю групу"
            className="inline-flex size-7 items-center justify-center rounded-control text-muted hover:bg-hover hover:text-primary focus-visible:outline-2 focus-visible:outline-focus pointer-coarse:size-11"
          >
            <X aria-hidden="true" className="size-4" />
          </button>
        </span>
      ) : null}
      {status}
      <ChangesNote items={changes} view={params.view} onOpenDay={openDay} />
    </>
  );

  return (
    <PageErrorScope queries={[query]} title={`Розклад ${target.label} не завантажився`}>
      <PageHeader
        size="display"
        title={
          <>
            <span className="figures">{target.label}</span>
            <span className="sr-only">{` — ${kindLabel.toLocaleLowerCase('uk')}. `}</span>
            {details ? (
              <span className="mt-1 block font-sans text-body font-normal tracking-normal text-secondary sm:ms-4 sm:mt-0 sm:inline">{details}</span>
            ) : null}
          </>
        }
        meta={headerMeta}
        controls={<ScheduleControls params={params} />}
        more={more}
      />
      <ScheduleExplorer
        query={query}
        params={params}
        perspective={perspective}
        ownerLabel={target.label}
        subgroups={subgroups}
        toolbar={false}
      />
      <SchedulePrint
        mode={mode}
        schedule={placeholder ? undefined : visible}
        perspective={perspective}
        kindLabel={kindLabel}
        title={mode === 'door' ? target.label : printTitle}
        url={url}
        weekNumber={weekNumber}
      />
      {doorSign ? (
        <QrDoorDialog
          open={qrOpen}
          onOpenChange={setQrOpen}
          title={target.label}
          url={url}
          fileName={`qr-${target.label}`}
          onPrint={() => print('door')}
        />
      ) : null}
    </PageErrorScope>
  );
}
