import { useRef, useState, type ReactNode } from 'react';
import { useDailyLiveSync, type ChangeOption } from '@/api/admin-daily';
import { usePublicMeta } from '@/api/meta';
import { useSettings } from '@/api/settings';
import type { Uuid } from '@/api/types';
import { AbsenceDialog } from '@/components/admin-daily/changes/AbsenceDialog';
import { AbsenceList } from '@/components/admin-daily/changes/AbsenceList';
import { AbsenceStrip } from '@/components/admin-daily/changes/AbsenceStrip';
import { LessonsPlaceholder, NoAbsencesState, WeekendState } from '@/components/admin-daily/changes/ChangesEmptyStates';
import { ChangesHeader } from '@/components/admin-daily/changes/ChangesHeader';
import { ChangesPanes } from '@/components/admin-daily/changes/ChangesPanes';
import { LessonList } from '@/components/admin-daily/changes/LessonList';
import { prefillFromParams, standaloneChanges } from '@/components/admin-daily/changes/new-change';
import { NewChangeDialog } from '@/components/admin-daily/changes/NewChangeDialog';
import { OptionsPanel } from '@/components/admin-daily/changes/OptionsPanel';
import { StandaloneChanges } from '@/components/admin-daily/changes/StandaloneChanges';
import { useAbsenceJournal } from '@/components/admin-daily/changes/useAbsenceJournal';
import { useChangesDialogs } from '@/components/admin-daily/changes/useChangesDialogs';
import { useChangesParams, useChangesSelection } from '@/components/admin-daily/changes/useChangesSelection';
import { useChangesShortcuts } from '@/components/admin-daily/changes/useChangesShortcuts';
import { useChangesWorkspace } from '@/components/admin-daily/changes/useChangesWorkspace';
import { useDecisionActions } from '@/components/admin-daily/changes/useDecisionActions';
import { useTeacherAnswerToasts } from '@/components/admin-daily/changes/useTeacherAnswerToasts';
import { WorkspaceSkeleton } from '@/components/admin-daily/changes/WorkspaceSkeleton';
import { OfflineState } from '@/components/admin-daily/OfflineState';
import { useContainerWidth } from '@/components/admin-daily/useContainerWidth';
import { Drawer, DrawerContent, LoadingRegion, PageErrorBanner } from '@/components/ui';
import { useMediaQuery } from '@/hooks/useMediaQuery';
import { usePulse } from '@/hooks/usePulse';
import { useWorkingDate } from '@/hooks/useWorkingDate';
import { isWeekend, shiftStudyDay } from '@/lib/date';

/** Ширина робочої області, з якої поміщаються дві і три колонки. */
const TWO_PANES = 760;
const THREE_PANES = 1080;
const PULSE_MS = 2_400;

/**
 * Головний щоденний екран диспетчера: відсутні → їхні пари → варіанти рішення → підтвердження.
 * Широкий екран — одна панель з трьома колонками; ноутбук — відсутні рядком над двома колонками;
 * телефон і планшет — по рівнях: відсутні → пари → варіанти в шторці, з дорогою назад на кожному.
 */
export default function ChangesPage() {
  const { date, setDate } = useWorkingDate();
  const { params, period, absenceParam, lessonParam, updateParams } = useChangesParams();
  const meta = usePublicMeta();
  const rootRef = useRef<HTMLDivElement>(null);
  const width = useContainerWidth(rootRef);
  const panes = width >= THREE_PANES ? 3 : width >= TWO_PANES ? 2 : 1;
  const narrow = !useMediaQuery('(min-width: 640px)', true);

  useDailyLiveSync();
  const workspace = useChangesWorkspace(date, period);
  const actions = useDecisionActions();
  // напис дії залежить від режиму погодження; будь-який інший за CONFIRM — «Призначити»
  const approvalMode = useSettings().data?.approvalMode ?? null;
  // з «Обліку годин» («Додати 2 пари», «Оформити «Вичит.»») — одразу форма зміни для цієї групи й дисципліни,
  // а не перша-ліпша відсутність
  const urlPrefill = prefillFromParams(params);
  const [pulsing, pulse] = usePulse<Uuid>(PULSE_MS);
  const selection = useChangesSelection({ workspace, panes, absenceParam, lessonParam, updateParams });
  const { selected, selectedAbsence, selectedAbsenceId } = selection;

  const act = (option: ChangeOption) => {
    if (!selected) return;
    // закріплюємо пару в адресі: після рішення диспетчер бачить його й строк, а не наступну пару
    updateParams({ lesson: selected.item.lesson.id });
    void actions.pick(option, selected, selection.reasonFor(option)).then((done) => {
      if (done) selection.clearOptionSelection();
    });
  };

  const dialogs = useChangesDialogs({
    onChangeDialogClosed: () => {
      if (urlPrefill) updateParams({ group: null, discipline: null, suggest: null, lessons: null });
    },
  });
  const { openNewAbsence, openNewChange } = dialogs;

  useChangesShortcuts({ date, move: selection.move, selectNumber: selection.selectNumber, openNewAbsence, openNewChange });
  useTeacherAnswerToasts({ workspace, isOwn: actions.isOwn, pulse });
  const journal = useAbsenceJournal({
    onAbsenceCancelled: (absence) => {
      if (selectedAbsenceId === absence.id) updateParams({ absence: null, lesson: null });
    },
  });

  const others = standaloneChanges(workspace.changesById.values());
  const othersList = (
    <StandaloneChanges changes={others} showDates={period === 'week'} onCancel={(change) => void journal.cancelStandalone(change)} onReplace={dialogs.openChangeWith} />
  );

  const weekend = period === 'day' && isWeekend(date);
  const nextStudyDay = shiftStudyDay(date, 1);
  const reason = selection.reasonForSelected;

  const optionsPanel = (showTitle: boolean) =>
    selected ? (
      <OptionsPanel
        entry={selected}
        showTitle={showTitle}
        approvalMode={approvalMode}
        reasonCode={reason.code}
        reasonText={reason.text}
        onReasonCodeChange={(code) => selection.changeReason({ code })}
        onReasonTextChange={(text) => selection.changeReason({ text })}
        selectedKey={selection.explicitOptionKey}
        onSelect={selection.selectOption}
        expanded={selection.expanded}
        onExpandedChange={selection.setExpanded}
        pickingKey={actions.pickingKey}
        onAct={act}
        onAssignManual={(teacher) => {
          updateParams({ lesson: selected.item.lesson.id });
          return actions.assignManual(teacher, selected, reason);
        }}
        planCount={selection.planCount}
        onApplyPlan={(choices) => actions.applyPlan(choices, selected.absence, selected.item.date)}
        decisionBusy={actions.busy}
        onResend={() => selected.change && actions.resend(selected.change)}
        onConfirm={() => selected.change && actions.confirm(selected.change)}
        onCancelDecision={() => selected.change && actions.cancelDecision(selected.change, selected)}
        registerOption={selection.registerOption}
      />
    ) : null;

  // перше завантаження дати чи періоду: панель чекає на всі запити (відсутні, їхні пари, рішення) і доти
  // показує силует тієї ж форми — без «напівзаповненої» панелі, що потім стрибає. Живі оновлення вже не ховають її.
  const viewKey = `${date}|${period}`;
  const [settledKey, setSettledKey] = useState<string | null>(null);
  if (!workspace.loading && settledKey !== viewKey) setSettledKey(viewKey);
  const settling = workspace.loading && settledKey !== viewKey;

  const showDates = period === 'week' || workspace.absences.some((a) => a.dateFrom !== a.dateTo);
  const selectedLessons = selectedAbsenceId ? workspace.lessonsStatus.get(selectedAbsenceId) : undefined;
  const lessons = (before?: ReactNode) =>
    selection.group ? (
      <LessonList
        group={selection.group}
        showDates={showDates}
        selectedKey={selection.selectedKey}
        pulsing={pulsing}
        onSelect={(entry) => selection.selectEntry(entry)}
        onEditAbsence={(absence) => dialogs.openAbsence(absence)}
        onCancelAbsence={(absence) => void journal.cancelAbsenceFlow(absence)}
        before={before}
        registerLesson={selection.registerLesson}
      />
    ) : (
      <LessonsPlaceholder absence={selectedAbsence} status={selectedLessons} loading={workspace.loading} before={before} />
    );

  const absenceList = (drill: boolean) => (
    <AbsenceList
      absences={workspace.absences}
      entries={workspace.entries}
      lessonsStatus={workspace.lessonsStatus}
      selectedId={selectedAbsenceId}
      onSelect={(id) => selection.selectAbsence(id)}
      onAdd={openNewAbsence}
      onAddChange={narrow ? openNewChange : undefined}
      loading={workspace.loading}
      drill={drill}
      showDates={period === 'week'}
    />
  );

  let body;
  if (workspace.error && workspace.absences.length === 0) {
    body = <PageErrorBanner error={workspace.error} title="Не вдалося завантажити відсутності" onRetry={workspace.retry} retrying={workspace.loading} />;
  } else if (weekend) {
    body = <WeekendState date={date} nextStudyDay={nextStudyDay} onNextDay={() => setDate(nextStudyDay)} onShowWeek={() => updateParams({ period: 'week' })} />;
  } else if (workspace.offline) {
    body = <OfflineState />;
  } else if (settling) {
    body = (
      <LoadingRegion label="Завантажуємо відсутніх і їхні пари…">
        <WorkspaceSkeleton panes={panes} />
      </LoadingRegion>
    );
  } else if (workspace.absences.length === 0) {
    body = (
      <NoAbsencesState date={date} period={period} onNewAbsence={openNewAbsence} onNewChange={openNewChange} others={others.length > 0 ? othersList : null} />
    );
  } else {
    body = (
      <ChangesPanes
        panes={panes}
        absenceList={absenceList}
        lessons={lessons}
        optionsPanel={optionsPanel}
        absenceStrip={
          <AbsenceStrip
            absences={workspace.absences}
            entries={workspace.entries}
            lessonsStatus={workspace.lessonsStatus}
            selectedId={selectedAbsenceId}
            onSelect={(id) => selection.selectAbsence(id)}
            onAdd={openNewAbsence}
          />
        }
        othersList={othersList}
        hasOthers={others.length > 0}
        absenceOpen={Boolean(selectedAbsence)}
        onBackToAbsences={() => selection.selectAbsence(null)}
      />
    );
  }

  // відсутні вже є, а пари чи рішення не прийшли — одна смуга з повтором над робочою панеллю
  const partialError =
    workspace.error && workspace.absences.length > 0 && !weekend ? (
      <PageErrorBanner error={workspace.error} title="Не вдалося завантажити пари відсутніх" onRetry={workspace.retry} retrying={workspace.loading} />
    ) : null;

  const explicit = selection.explicit;
  return (
    <div ref={rootRef} className="flex flex-col gap-6">
      <ChangesHeader
        date={date}
        period={period}
        onPeriodChange={(value) => updateParams({ period: value === 'day' ? null : value, lesson: null })}
        progress={weekend || (!settling && workspace.absences.length === 0) ? 'hidden' : settling ? 'settling' : 'ready'}
        entries={workspace.entries}
        newAbsenceInMenu={panes === 1 && Boolean(selectedAbsence) && !weekend}
        narrow={narrow}
        onNewAbsence={openNewAbsence}
        onNewChange={openNewChange}
      />

      {partialError}
      {body}

      {panes === 1 ? (
        <Drawer direction="right" open={Boolean(explicit)} onOpenChange={(open) => !open && updateParams({ lesson: null })}>
          <DrawerContent side="right" title={explicit ? `${explicit.item.lesson.group.code}, ${explicit.item.lesson.pair} пара` : 'Варіанти'}>
            {optionsPanel(false)}
          </DrawerContent>
        </Drawer>
      ) : null}

      <NewChangeDialog
        key={urlPrefill ? `url:${params.get('group')}:${params.get('discipline')}:${params.get('suggest')}` : `seq:${dialogs.changeDialog.seq}`}
        open={dialogs.changeDialog.open || urlPrefill !== null}
        onOpenChange={dialogs.setChangeDialogOpen}
        date={date}
        semester={meta.data?.semester ?? null}
        prefill={urlPrefill ?? dialogs.changeDialog.prefill}
        approvalMode={approvalMode}
      />

      <AbsenceDialog
        key={dialogs.absenceDialog.seq}
        open={dialogs.absenceDialog.open}
        onOpenChange={dialogs.setAbsenceDialogOpen}
        date={date}
        absence={dialogs.absenceDialog.absence}
        semester={meta.data?.semester ?? null}
        onSaved={(absence, created, before) => {
          updateParams({ absence: absence.id, lesson: null });
          journal.journalAbsence(created ? null : before, absence);
        }}
      />
    </div>
  );
}
