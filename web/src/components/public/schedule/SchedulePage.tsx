import type { UseQueryResult } from '@tanstack/react-query';
import { ChevronDown } from 'lucide-react';
import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { useBells, usePublicMeta } from '@/api/meta';
import type { ScheduleDto } from '@/api/types';
import { LessonLinksContext, lessonLinkSearch, NowIndicator, type LessonLinks, type ScheduleEntityKind } from '@/components/schedule';
import { PageErrorScope, PageHeader } from '@/components/ui';
import { useMediaQuery } from '@/hooks/useMediaQuery';
import { useTeacherNameDirectory } from '@/hooks/useTeacherNames';
import type { Perspective } from '@/lib/schedule';
import { useFavoritesStore, type ScheduleTarget } from '@/stores/favorites';
import { SchedulePrint } from '../print/SchedulePrint';
import { usePrintMode } from '../print/usePrintMode';
import { HeaderParity } from '../HeaderParity';
import { isHistoryClosed } from '../history';
import { useDocumentTitle } from '../useDocumentTitle';
import { CalendarDialog } from './CalendarDialog';
import { ChangesNote } from './ChangesNote';
import { QrDoorDialog } from './QrDoorDialog';
import { SubgroupDialog } from './SubgroupDialog';
import type { IcsTarget } from './ScheduleActions';
import { useScheduleMenuItems } from './useScheduleMenuItems';
import { PHONE_QUERY, ScheduleControls, ScheduleExplorer } from './ScheduleExplorer';
import type { ScheduleParams } from './schedule-params';
import { activePairs, changedLessons, dayOf, dayShareText, filterBySubgroup, ownRoomOf, semesterWeekOf, withoutOwnRoom } from './schedule-utils';

const TARGET_KIND = { GROUP: 'group', TEACHER: 'teacher', ROOM: 'room' } as const satisfies Record<ScheduleTarget['type'], ScheduleEntityKind>;

interface SchedulePageProps {
  /** Ціль для обраного й нещодавніх: тип, id, підпис, маршрут у застосунку. */
  target: ScheduleTarget;
  /** «Розклад групи», «Розклад викладача», «Розклад аудиторії» — для друку й читача екрана. */
  kindLabel: string;
  /** Назва великим, коли вона інша, ніж короткий підпис цілі: «Аудиторія 3» замість «ауд. 3». */
  heading?: string;
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
 * Каркас сторінок /g, /t, /r: «‹ Розклад», назва великим, один рядок контексту (парність, підгрупа, заміни тижня),
 * два керування (День/Тиждень, тиждень) і «⋯» з усім іншим, підгрупою теж. Запамʼятовує ціль як останню переглянуту —
 * головна покаже її розклад.
 */
export function SchedulePage({
  target,
  kindLabel,
  heading,
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
  const phone = useMediaQuery(PHONE_QUERY);
  const { mode, print } = usePrintMode();
  const [qrOpen, setQrOpen] = useState(false);
  const [calendarOpen, setCalendarOpen] = useState(false);
  const [subgroupOpen, setSubgroupOpen] = useState(false);
  // підгрупа має сенс, лише коли цього тижня є пари підгруп; запамʼятована без них нічого не ховає
  const subgroupShown = subgroups.length > 0 ? params.subgroup : null;
  const url = `${window.location.origin}${target.url}`;
  const visible = useMemo(() => (query.data ? filterBySubgroup(query.data, params.subgroup) : undefined), [query.data, params.subgroup]);
  const placeholder = query.isPlaceholderData;
  const changes = useMemo(() => (placeholder ? [] : changedLessons(visible)), [visible, placeholder]);
  const parity = placeholder ? null : (visible?.days[0]?.parity ?? null);
  const weekNumber = semesterWeekOf(params.date, meta.data?.semester);
  // тиждень, у якому є сьогодні: стан дня одним тихим рядком у рядку контексту («Зараз 2 пара · ще 35 хв»,
  // «Пари закінчилися о 13:10»). Подання «День» показує його над списком, аудиторія має свій рядок стану
  const today = meta.data?.today ?? null;
  const todayDay = placeholder || !today || params.view !== 'week' || perspective === 'room' ? undefined : dayOf(visible, today);
  const liveDate = todayDay?.studyDay ? todayDay.date : null;
  const todayBells = useBells(liveDate);
  const todayPairs = useMemo(() => activePairs(todayDay), [todayDay]);
  const printTitle = params.subgroup ? `${target.label} · ${params.subgroup} підгрупа` : target.label;

  // на сторінці аудиторії клітинки не повторюють її номер: другий рядок — група й викладач
  const roomId = target.type === 'ROOM' ? target.id : null;
  const select = useMemo(() => (roomId ? (schedule: ScheduleDto) => withoutOwnRoom(schedule, roomId) : undefined), [roomId]);

  // картка заняття: викладач, аудиторія й група ведуть на свої розклади з тим самим поданням і днем;
  // сама сторінка (ця група, цей викладач, ця аудиторія) — простим текстом
  // сирі дані ще несуть свою аудиторію (з типом): картка покаже «ауд. 14 · лабораторія», а не лише номер
  const ownRoom = useMemo(() => (roomId ? ownRoomOf(query.data, roomId) : null), [query.data, roomId]);
  const lessonLinks = useMemo<LessonLinks>(
    () => ({
      self: { kind: TARGET_KIND[target.type], id: target.id, label: target.label, room: ownRoom },
      search: lessonLinkSearch({ view: params.view, date: params.date, homeDate: params.homeDate }),
    }),
    [target.type, target.id, target.label, ownRoom, params.view, params.date, params.homeDate],
  );

  useDocumentTitle(documentTitle);

  useEffect(() => {
    useFavoritesStore.getState().rememberView({ type: target.type, id: target.id, label: target.label, url: target.url });
  }, [target.type, target.id, target.label, target.url]);

  // текст «Поділитися» пише викладачів «Прізвище І. Б.», як клітинки
  const teacherNames = useTeacherNameDirectory();
  const more = useScheduleMenuItems({
    target,
    share: {
      title: `Розклад ${target.label}`,
      text: () => dayShareText(target.label, dayOf(visible, params.date), perspective, { names: teacherNames }),
      url,
    },
    onCalendar: ics ? () => setCalendarOpen(true) : null,
    onPrintWeek: () => print('week'),
    onQr: doorSign ? () => setQrOpen(true) : null,
    onSubgroup: subgroups.length > 0 ? () => setSubgroupOpen(true) : null,
    subgroup: subgroupShown,
  });

  const openDay = (date: string) => {
    params.setDate(date);
    if (params.view !== 'day') params.setView('day');
  };

  const headerMeta = (
    <>
      <HeaderParity parity={parity} />
      {liveDate && todayBells.data && todayBells.data.slots.length > 0 ? (
        <NowIndicator
          variant="inline"
          bells={todayBells.data.slots}
          lessonPairs={todayPairs}
          date={liveDate}
          // розділювач — частина рядка стану: коли стану немає, крапка не повисає
          className="before:text-muted before:content-['·']"
        />
      ) : null}
      {status}
      {subgroupShown !== null ? (
        // обрана підгрупа — тихо в рядку контексту, поки вона не «уся група»: інакше людина не зрозуміє, куди зникли пари
        <button
          type="button"
          onClick={() => setSubgroupOpen(true)}
          aria-label={`${subgroupShown} підгрупа — змінити`}
          className="-mx-1.5 inline-flex h-7 items-center gap-1 rounded-control px-1.5 text-caption font-[650] whitespace-nowrap text-primary transition-[background-color] duration-150 hover:bg-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus pointer-coarse:h-11"
        >
          {subgroupShown} підгрупа
          <ChevronDown aria-hidden="true" className="size-3.5 text-muted" />
        </button>
      ) : null}
      <ChangesNote items={changes} view={params.view} onOpenDay={openDay} />
    </>
  );

  return (
    // «архів недоступний» гостю — не помилка сторінки: пояснення стоїть на місці розкладу
    <PageErrorScope queries={[isHistoryClosed(query.error) ? null : query]} title={`Розклад ${target.label} не завантажився`}>
      <PageHeader
        size="display"
        title={
          <>
            <span className="figures">{heading ?? target.label}</span>
            <span className="sr-only">{` — ${kindLabel.toLocaleLowerCase('uk')}. `}</span>
            {details ? (
              <span className="mt-1 block font-sans text-body font-normal tracking-normal text-secondary sm:ms-4 sm:mt-0 sm:inline">{details}</span>
            ) : null}
          </>
        }
        meta={headerMeta}
        controls={phone ? undefined : <ScheduleControls params={params} />}
        more={more}
      />
      <LessonLinksContext value={lessonLinks}>
        <ScheduleExplorer
          query={query}
          params={params}
          perspective={perspective}
          ownerLabel={target.label}
          toolbar={false}
          select={select}
        />
      </LessonLinksContext>
      <SchedulePrint
        mode={mode}
        schedule={placeholder ? undefined : visible}
        perspective={perspective}
        kindLabel={kindLabel}
        title={mode === 'door' ? target.label : printTitle}
        url={url}
        weekNumber={weekNumber}
      />
      {subgroups.length > 0 ? (
        <SubgroupDialog
          open={subgroupOpen}
          onOpenChange={setSubgroupOpen}
          groupLabel={target.label}
          subgroups={subgroups}
          value={subgroupShown}
          onChange={params.setSubgroup}
        />
      ) : null}
      {ics ? <CalendarDialog ics={ics} label={target.label} open={calendarOpen} onOpenChange={setCalendarOpen} /> : null}
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
