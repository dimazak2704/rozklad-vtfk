import type { UseQueryResult } from '@tanstack/react-query';
import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { usePublicMeta } from '@/api/meta';
import type { ScheduleDto } from '@/api/types';
import type { Perspective } from '@/lib/schedule';
import { useFavoritesStore, type ScheduleTarget } from '@/stores/favorites';
import { SchedulePrint } from '../print/SchedulePrint';
import { usePrintMode } from '../print/usePrintMode';
import { useDocumentTitle } from '../useDocumentTitle';
import { QrDoorDialog } from './QrDoorDialog';
import { ScheduleActions, type IcsTarget } from './ScheduleActions';
import { ScheduleExplorer } from './ScheduleExplorer';
import { ScheduleHeader } from './ScheduleHeader';
import type { ScheduleParams } from './schedule-params';
import { dayOf, dayShareText, filterBySubgroup, semesterWeekOf } from './schedule-utils';

interface SchedulePageProps {
  /** Ціль для обраного й нещодавніх: тип, id, підпис, маршрут у застосунку. */
  target: ScheduleTarget;
  /** «Розклад групи», «Розклад викладача», «Аудиторія». */
  kindLabel: string;
  /** Заголовок вкладки браузера: «4КІ1 — розклад групи». */
  documentTitle: string;
  details?: ReactNode;
  badges?: ReactNode;
  perspective: Perspective;
  query: UseQueryResult<ScheduleDto>;
  params: ScheduleParams;
  subgroups?: readonly number[];
  ics?: IcsTarget | null;
  /** Табличка з QR-кодом на двері (групи й аудиторії). */
  doorSign?: boolean;
  /** Блок між заголовком і розкладом (зайнятість аудиторії тощо). */
  children?: ReactNode;
}

/**
 * Каркас сторінок /g, /t, /r: заголовок з діями (обране, поділитися, календар, друк, QR), розклад, аркуш
 * для друку. Запамʼятовує ціль як останню переглянуту — головна відкриє її розклад.
 */
export function SchedulePage({
  target,
  kindLabel,
  documentTitle,
  details,
  badges,
  perspective,
  query,
  params,
  subgroups = [],
  ics,
  doorSign = false,
  children,
}: SchedulePageProps) {
  const meta = usePublicMeta();
  const { mode, print } = usePrintMode();
  const [qrOpen, setQrOpen] = useState(false);
  const url = `${window.location.origin}${target.url}`;
  const visible = useMemo(() => (query.data ? filterBySubgroup(query.data, params.subgroup) : undefined), [query.data, params.subgroup]);
  const parity = query.isPlaceholderData ? null : (visible?.days[0]?.parity ?? null);
  const weekNumber = semesterWeekOf(params.date, meta.data?.semester);
  const printTitle = params.subgroup ? `${target.label} · ${params.subgroup} підгрупа` : target.label;

  useDocumentTitle(documentTitle);

  useEffect(() => {
    useFavoritesStore.getState().rememberView({ type: target.type, id: target.id, label: target.label, url: target.url });
  }, [target.type, target.id, target.label, target.url]);

  return (
    <div className="flex flex-col gap-6">
      <ScheduleHeader
        kindLabel={kindLabel}
        title={target.label}
        details={details}
        parity={parity}
        weekNumber={weekNumber}
        badges={badges}
        actions={
          <ScheduleActions
            target={target}
            share={{
              title: `Розклад ${target.label}`,
              text: () => dayShareText(target.label, dayOf(visible, params.date), perspective),
              url,
            }}
            ics={ics}
            onPrintWeek={() => print('week')}
            onQr={doorSign ? () => setQrOpen(true) : null}
          />
        }
      />
      {children}
      <ScheduleExplorer query={query} params={params} perspective={perspective} ownerLabel={target.label} subgroups={subgroups} />
      <SchedulePrint
        mode={mode}
        schedule={query.isPlaceholderData ? undefined : visible}
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
    </div>
  );
}
