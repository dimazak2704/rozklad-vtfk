/**
 * Команди відсутностей і змін: перевірка запиту як у core (400 з полями, 404, 409 з conflicts[]), переходи
 * станів з історією, SSE `change.updated` / `absence.created`, запит у Telegram і його імітована відповідь.
 */
import type {
  AbsenceDto,
  AbsenceReasonCategory,
  AbsenceRequest,
  ChangeKind,
  Conflict,
  CreateChangeRequest,
  FieldErrorDto,
  LessonView,
  ReasonCode,
  ReplyDecision,
  Uuid,
} from '@/api/types';
import { compareIsoDates, formatDateRange, formatNumericDate, isIsoDate } from '@/lib/date';
import { CHANGE_KIND_LABEL } from '@/lib/labels';
import {
  ACTIVE_SEMESTER,
  disciplineRef,
  findDiscipline,
  findGroup,
  findRoom,
  findTeacher,
  groupRef,
  roomRef,
  teacherRef,
} from '../../fixtures';
import { emitServerEvent } from '../../events';
import { mockId } from '../../ids';
import { badRequest, conflict, problem } from '../../kit';
import { currentSettings } from '../settings';
import { isTeacherBound, recordConfirmationRequest } from '../telegram';
import { makeChange, transition } from './factory';
import { findTemplateLesson } from './lessons';
import { decisionFor, groupPairs, isAbsent, isCovered, teacherSlots } from './rules';
import { getChange, saveChange, world } from './state';
import { cancelSimulatedReply, scheduleSimulatedReply, simulatedDecision } from './telegram-sim';
import { ADMIN_ACTOR, toChangeDto, type ChangeRecord } from './world';

const REASON_CATEGORIES: readonly AbsenceReasonCategory[] = ['SICK_LEAVE', 'VACATION', 'BUSINESS_TRIP', 'PERSONAL', 'OTHER'];
const REASON_CODES: readonly ReasonCode[] = ['ABSENT', 'MUTUAL', 'FINISHED', 'BUSINESS_TRIP', 'OTHER'];
/** Види, які диспетчер створює зі сторінки «Заміни» (блок і «вичитано» — з календаря й обліку). */
const CREATABLE_KINDS: readonly ChangeKind[] = ['REPLACE_TEACHER', 'REPLACE_DISCIPLINE', 'SWAP', 'CANCEL', 'ADDITIONAL', 'ROOM_CHANGE'];
const MAX_PAIR = 8;
const MAX_COMMENT = 500;
/** Причина «Інше» друкується в колонці розпорядження — коротко. */
const MAX_REASON_TEXT = 20;

function emitChange(change: ChangeRecord): void {
  emitServerEvent('change.updated', toChangeDto(change));
}

const semesterRange = `${formatNumericDate(ACTIVE_SEMESTER.startsOn)}–${formatNumericDate(ACTIVE_SEMESTER.endsOn)}`;

// ───────────────────────── Відсутності ─────────────────────────

/** Перевіряє тіло відсутності; повертає помилки полів (порожній масив — усе гаразд). */
export function validateAbsence(body: Partial<AbsenceRequest>): FieldErrorDto[] {
  const errors: FieldErrorDto[] = [];
  if (!body.teacherId || !findTeacher(body.teacherId)) errors.push({ field: 'teacherId', message: 'Оберіть викладача' });
  const fromValid = isIsoDate(body.dateFrom);
  const toValid = isIsoDate(body.dateTo);
  if (!fromValid) errors.push({ field: 'dateFrom', message: 'Вкажіть дату початку' });
  if (!toValid) errors.push({ field: 'dateTo', message: 'Вкажіть дату завершення' });
  if (fromValid && toValid) {
    const from = body.dateFrom as string;
    const to = body.dateTo as string;
    if (compareIsoDates(to, from) < 0) errors.push({ field: 'dateTo', message: 'Не раніше за дату початку' });
    else if (compareIsoDates(from, ACTIVE_SEMESTER.startsOn) < 0 || compareIsoDates(to, ACTIVE_SEMESTER.endsOn) > 0) {
      errors.push({ field: 'dateFrom', message: `Дати мають бути в межах семестру (${semesterRange})` });
    }
  }
  if (body.pairs !== null && body.pairs !== undefined) {
    const pairs = body.pairs;
    const valid =
      Array.isArray(pairs) &&
      pairs.length > 0 &&
      pairs.every((p) => Number.isInteger(p) && p >= 1 && p <= MAX_PAIR) &&
      new Set(pairs).size === pairs.length;
    if (!valid) errors.push({ field: 'pairs', message: `Оберіть пари від 1 до ${MAX_PAIR} або «увесь день»` });
  }
  if (!body.reasonCategory || !REASON_CATEGORIES.includes(body.reasonCategory)) {
    errors.push({ field: 'reasonCategory', message: 'Оберіть причину' });
  }
  if (body.comment && body.comment.length > MAX_COMMENT) errors.push({ field: 'comment', message: `Не довше ${MAX_COMMENT} символів` });
  return errors;
}

function overlaps(a: Pick<AbsenceDto, 'dateFrom' | 'dateTo' | 'pairs'>, b: Pick<AbsenceDto, 'dateFrom' | 'dateTo' | 'pairs'>): boolean {
  if (compareIsoDates(a.dateTo, b.dateFrom) < 0 || compareIsoDates(b.dateTo, a.dateFrom) < 0) return false;
  if (a.pairs === null || b.pairs === null) return true;
  return a.pairs.some((pair) => b.pairs?.includes(pair));
}

function assertNoOverlap(candidate: Pick<AbsenceDto, 'dateFrom' | 'dateTo' | 'pairs'>, teacherId: Uuid, ignoreId?: Uuid): void {
  for (const other of world().absences.values()) {
    if (other.id === ignoreId || other.status !== 'ACTIVE' || other.teacher.id !== teacherId) continue;
    if (overlaps(candidate, other)) {
      throw conflict(
        `У ${other.teacher.shortName} уже є відсутність на ${formatDateRange(other.dateFrom, other.dateTo)}. Змініть її замість нової.`,
      );
    }
  }
}

export function createAbsence(body: Partial<AbsenceRequest>, now: Date): AbsenceDto {
  const errors = validateAbsence(body);
  if (errors.length > 0) throw badRequest('Перевірте дані відсутності.', errors);
  const teacher = findTeacher(body.teacherId as string);
  if (!teacher) throw badRequest('Невідомий викладач.', [{ field: 'teacherId', message: 'Оберіть викладача' }]);
  const pairs = body.pairs ? [...body.pairs].sort((a, b) => a - b) : null;
  const draft = { dateFrom: body.dateFrom as string, dateTo: body.dateTo as string, pairs };
  assertNoOverlap(draft, teacher.id);
  const absence: AbsenceDto = {
    id: mockId('absence', `${teacher.id}@${now.toISOString()}`),
    teacher: teacherRef(teacher),
    ...draft,
    reasonCategory: body.reasonCategory as AbsenceReasonCategory,
    comment: body.comment?.trim() || null,
    source: 'ADMIN',
    status: 'ACTIVE',
    createdAt: now.toISOString(),
  };
  world().absences.set(absence.id, absence);
  emitServerEvent('absence.created', absence);
  return absence;
}

export function getAbsence(id: Uuid): AbsenceDto {
  const absence = world().absences.get(id);
  if (!absence || absence.status !== 'ACTIVE') {
    throw problem({ status: 404, detail: `Відсутність з id ${id} не знайдено.` });
  }
  return absence;
}

export function updateAbsence(id: Uuid, body: Partial<AbsenceRequest>): AbsenceDto {
  const current = getAbsence(id);
  const errors = validateAbsence(body);
  if (errors.length > 0) throw badRequest('Перевірте дані відсутності.', errors);
  if (body.teacherId !== current.teacher.id) {
    throw badRequest('Викладача у відсутності змінити не можна — створіть нову.', [
      { field: 'teacherId', message: 'Викладача змінити не можна' },
    ]);
  }
  const pairs = body.pairs ? [...body.pairs].sort((a, b) => a - b) : null;
  const next: AbsenceDto = {
    ...current,
    dateFrom: body.dateFrom as string,
    dateTo: body.dateTo as string,
    pairs,
    reasonCategory: body.reasonCategory as AbsenceReasonCategory,
    comment: body.comment?.trim() || null,
  };
  assertNoOverlap(next, current.teacher.id, id);
  world().absences.set(id, next);
  return next;
}

/** Відсутність скасовано (викладач вийшов): невнесені рішення за нею теж скасовуються. */
export function cancelAbsence(id: Uuid, now: Date): void {
  const absence = getAbsence(id);
  world().absences.set(id, { ...absence, status: 'CANCELLED' });
  for (const change of [...world().changes.values()]) {
    if (change.absenceId !== id || change.status === 'APPLIED' || change.status === 'CANCELLED') continue;
    cancelSimulatedReply(change.id);
    emitChange(saveChange(transition(change, 'CANCELLED', now, ADMIN_ACTOR, 'Відсутність скасовано', { confirmDeadline: null })));
  }
}

// ───────────────────────── Зміни: створення ─────────────────────────

interface Resolved {
  lesson: LessonView | null;
  swapLesson: LessonView | null;
}

function lessonOn(id: string | null | undefined, date: string, field: string, errors: FieldErrorDto[]): LessonView | null {
  if (!id) {
    errors.push({ field, message: 'Оберіть пару' });
    return null;
  }
  const found = findTemplateLesson(id);
  if (!found || found.date !== date) {
    errors.push({ field, message: 'Такої пари на цю дату немає' });
    return null;
  }
  return found.lesson;
}

function teacherConflict(teacherId: Uuid, date: string, pair: number): Conflict | null {
  const teacher = findTeacher(teacherId);
  const name = teacher?.shortName ?? 'Викладач';
  if (isAbsent(world(), teacherId, date, pair)) {
    return { kind: 'UNAVAILABLE', severity: 'HARD', message: `У ${name} на цю пару є відсутність`, lessonIds: [], date, pair };
  }
  const slot = teacherSlots(world(), teacherId, date).get(pair);
  if (!slot) return null;
  return {
    kind: 'TEACHER',
    severity: 'HARD',
    message: `${name} у цей час уже веде ${slot.label}`,
    lessonIds: slot.lessonId ? [slot.lessonId] : [],
    date,
    pair,
  };
}

function resolveRequest(body: Partial<CreateChangeRequest>): Resolved & { errors: FieldErrorDto[] } {
  const errors: FieldErrorDto[] = [];
  const kind = body.kind;
  const result: Resolved = { lesson: null, swapLesson: null };
  if (!kind || !CREATABLE_KINDS.includes(kind)) {
    errors.push({ field: 'kind', message: 'Цей вид зміни тут не створюється' });
    return { ...result, errors };
  }
  if (!isIsoDate(body.date)) errors.push({ field: 'date', message: 'Вкажіть дату' });
  else if (compareIsoDates(body.date, ACTIVE_SEMESTER.startsOn) < 0 || compareIsoDates(body.date, ACTIVE_SEMESTER.endsOn) > 0) {
    errors.push({ field: 'date', message: `Дата має бути в межах семестру (${semesterRange})` });
  }
  if (!body.groupId || !findGroup(body.groupId)) errors.push({ field: 'groupId', message: 'Оберіть групу' });
  if (!Number.isInteger(body.pairFrom) || (body.pairFrom as number) < 1 || (body.pairFrom as number) > MAX_PAIR) {
    errors.push({ field: 'pairFrom', message: `Пара від 1 до ${MAX_PAIR}` });
  }
  if (!body.reasonCode || !REASON_CODES.includes(body.reasonCode)) errors.push({ field: 'reasonCode', message: 'Оберіть причину' });
  if (body.reasonCode === 'OTHER' && !body.reasonText?.trim()) {
    errors.push({ field: 'reasonText', message: 'Коротко вкажіть причину для розпорядження' });
  }
  if (body.reasonText && body.reasonText.trim().length > MAX_REASON_TEXT) {
    errors.push({ field: 'reasonText', message: `Не довше ${MAX_REASON_TEXT} символів: текст іде в колонку розпорядження` });
  }
  if (body.absenceId && world().absences.get(body.absenceId)?.status !== 'ACTIVE') {
    errors.push({ field: 'absenceId', message: 'Відсутність не знайдено або скасовано' });
  }
  if (errors.length > 0) return { ...result, errors };
  const date = body.date as string;

  if (kind !== 'ADDITIONAL') {
    result.lesson = lessonOn(body.originalLessonId, date, 'originalLessonId', errors);
    if (result.lesson && (result.lesson.group.id !== body.groupId || result.lesson.pair !== body.pairFrom)) {
      errors.push({ field: 'originalLessonId', message: 'Пара не збігається з групою чи номером пари' });
    }
  }
  const needsTeacher = kind === 'REPLACE_TEACHER' || kind === 'REPLACE_DISCIPLINE' || kind === 'ADDITIONAL';
  if (needsTeacher && (!body.newTeacherId || !findTeacher(body.newTeacherId))) {
    errors.push({ field: 'newTeacherId', message: 'Оберіть викладача' });
  }
  if (needsTeacher && result.lesson && body.newTeacherId === result.lesson.teacher?.id) {
    errors.push({ field: 'newTeacherId', message: 'Це той самий викладач' });
  }
  if ((kind === 'REPLACE_DISCIPLINE' || kind === 'ADDITIONAL') && (!body.newDisciplineId || !findDiscipline(body.newDisciplineId))) {
    errors.push({ field: 'newDisciplineId', message: 'Оберіть дисципліну' });
  }
  if (kind === 'ROOM_CHANGE' && (!body.newRoomId || !findRoom(body.newRoomId))) {
    errors.push({ field: 'newRoomId', message: 'Оберіть аудиторію' });
  }
  if (kind === 'SWAP') {
    result.swapLesson = lessonOn(body.swapLessonId, date, 'swapLessonId', errors);
    const swap = result.swapLesson;
    if (swap && result.lesson && (swap.group.id !== result.lesson.group.id || swap.pair <= result.lesson.pair)) {
      errors.push({ field: 'swapLessonId', message: 'Переставити можна лише пізнішу пару тієї ж групи' });
    }
  }
  return { ...result, errors };
}

/** POST /api/admin/changes: перевірка, конфлікти й статус за режимом погодження. */
export function createChange(body: Partial<CreateChangeRequest>, now: Date): ChangeRecord {
  const { lesson, swapLesson, errors } = resolveRequest(body);
  if (errors.length > 0) throw badRequest('Перевірте дані зміни.', errors);
  const kind = body.kind as ChangeKind;
  const date = body.date as string;
  const pair = body.pairFrom as number;

  if (lesson) {
    const decision = decisionFor(world(), lesson.id);
    if (decision && isCovered(decision)) {
      throw conflict(
        `Для цієї пари вже є рішення: ${CHANGE_KIND_LABEL[decision.kind].toLocaleLowerCase('uk')}. Скасуйте його, щоб обрати інше.`,
      );
    }
  }
  const conflicts: Conflict[] = [];
  const newTeacherId = kind === 'SWAP' ? swapLesson?.teacher?.id : body.newTeacherId;
  if (newTeacherId) {
    const busy = teacherConflict(newTeacherId, date, pair);
    if (busy) conflicts.push(busy);
  }
  if (kind === 'ADDITIONAL' && body.groupId && groupPairs(world(), body.groupId, date).includes(pair)) {
    conflicts.push({ kind: 'GROUP', severity: 'HARD', message: 'У групи на цій парі вже є заняття', lessonIds: [], date, pair });
  }
  if (conflicts.length > 0) throw conflict('Зміна конфліктує з розкладом.', conflicts);

  const group = findGroup(body.groupId as string);
  const settings = currentSettings();
  const newTeacher = body.newTeacherId ? findTeacher(body.newTeacherId) : undefined;
  const newDiscipline = body.newDisciplineId ? findDiscipline(body.newDisciplineId) : undefined;
  const newRoom = body.newRoomId ? findRoom(body.newRoomId) : undefined;
  const change = makeChange(
    {
      id: mockId('change', `${body.originalLessonId ?? body.groupId}@${now.toISOString()}#${world().changes.size}`),
      kind,
      date,
      group: group ? groupRef(group) : (lesson?.group as LessonView['group']),
      subgroup: lesson?.subgroup ?? body.subgroup ?? 0,
      pairFrom: pair,
      pairTo: body.pairTo ?? null,
      lesson,
      swapLesson,
      newTeacher: newTeacher ? teacherRef(newTeacher) : null,
      newDiscipline: newDiscipline ? disciplineRef(newDiscipline) : null,
      newRoom: newRoom ? roomRef(newRoom) : null,
      reasonCode: body.reasonCode as ReasonCode,
      reasonText: body.reasonText ?? null,
      absenceId: body.absenceId ?? null,
    },
    { at: now, actor: ADMIN_ACTOR, mode: settings.approvalMode, timeoutMinutes: settings.confirmTimeoutMinutes },
  );
  saveChange(change);
  emitChange(change);
  if (change.status === 'PENDING_CONFIRMATION') requestConfirmation(change);
  return change;
}

// ───────────────────────── Зміни: життєвий цикл ─────────────────────────

/** Надсилає запит викладачу в Telegram і (у браузерному моку) планує його відповідь. */
function requestConfirmation(change: ChangeRecord): void {
  const teacherId = change.new.teacher?.id;
  if (!teacherId) return;
  recordConfirmationRequest(change.id, teacherId);
  // запит без привʼязаного чату не доходить — відповіді не буде, диспетчер підтверджує вручну
  if (!isTeacherBound(teacherId)) return;
  scheduleSimulatedReply(
    change.id,
    () => {
      const current = world().changes.get(change.id);
      if (!current || current.status !== 'PENDING_CONFIRMATION') return null;
      const pairs = teacherSlots(world(), teacherId, current.date).size - 1;
      return simulatedDecision(current.id, pairs);
    },
    (decision, comment) => {
      const current = world().changes.get(change.id);
      if (current?.status === 'PENDING_CONFIRMATION') replyToChange(current.id, teacherId, decision, comment, new Date());
    },
  );
}

function assertNotFinal(change: ChangeRecord, action: string): void {
  if (change.status === 'APPLIED') {
    throw conflict(`Зміну вже внесено в опубліковане розпорядження — ${action} можна лише доповненням.`);
  }
  if (change.status === 'CANCELLED') throw conflict('Зміну вже скасовано.');
}

/** DELETE /api/admin/changes/{id} → CANCELLED: пара знову потребує рішення. */
export function cancelChange(id: Uuid, now: Date): ChangeRecord {
  const change = getChange(id);
  assertNotFinal(change, 'скасувати її');
  cancelSimulatedReply(id);
  const next = saveChange(transition(change, 'CANCELLED', now, ADMIN_ACTOR, 'Рішення скасовано диспетчером', { confirmDeadline: null }));
  emitChange(next);
  return next;
}

/** POST …/request-confirmation: нагадування в Telegram і новий строк відповіді. */
export function resendConfirmation(id: Uuid, now: Date): ChangeRecord {
  const change = getChange(id);
  if (change.status === 'DECLINED') throw conflict('Викладач уже відмовився. Оберіть іншого кандидата.');
  if (change.status !== 'PENDING_CONFIRMATION') throw conflict('Нагадати можна лише про заміну, що чекає підтвердження.');
  const deadline = new Date(now.getTime() + currentSettings().confirmTimeoutMinutes * 60_000).toISOString();
  const next = saveChange(
    transition(change, 'PENDING_CONFIRMATION', now, ADMIN_ACTOR, 'Запит надіслано повторно', { confirmDeadline: deadline }),
  );
  emitChange(next);
  requestConfirmation(next);
  return next;
}

/** POST …/confirm: диспетчер підтверджує сам (домовився телефоном). */
export function confirmByAdmin(id: Uuid, now: Date): ChangeRecord {
  const change = getChange(id);
  if (change.status !== 'PENDING_CONFIRMATION' && change.status !== 'DECLINED') {
    throw conflict('Підтвердити можна лише заміну, що чекає відповіді або була відхилена.');
  }
  cancelSimulatedReply(id);
  const next = saveChange(transition(change, 'CONFIRMED', now, ADMIN_ACTOR, 'Підтверджено диспетчером', { confirmDeadline: null }));
  emitChange(next);
  return next;
}

/** POST /api/internal/changes/{id}/reply — так бот передає відповідь викладача. */
export function replyToChange(id: Uuid, teacherId: string | undefined, decision: ReplyDecision | undefined, comment: string | null | undefined, now: Date): ChangeRecord {
  if (decision !== 'CONFIRM' && decision !== 'DECLINE') {
    throw badRequest('Невідома відповідь.', [{ field: 'decision', message: 'CONFIRM або DECLINE' }]);
  }
  const change = getChange(id);
  if (!teacherId || change.new.teacher?.id !== teacherId) {
    throw problem({ status: 403, detail: 'Відповісти може лише викладач, якому запропоновано заміну.' });
  }
  if (change.status !== 'PENDING_CONFIRMATION') throw conflict('Заміна вже не чекає підтвердження.');
  cancelSimulatedReply(id);
  const to = decision === 'CONFIRM' ? 'CONFIRMED' : 'DECLINED';
  const next = saveChange(
    transition(change, to, now, change.new.teacher?.shortName ?? 'telegram', comment?.trim() || null, {
      respondedAt: now.toISOString(),
      confirmDeadline: null,
    }),
  );
  emitChange(next);
  return next;
}

/** PUT /api/admin/changes/{id}: причина й аудиторія (решта — нове рішення). */
export function updateChange(id: Uuid, body: Partial<CreateChangeRequest>): ChangeRecord {
  const change = getChange(id);
  assertNotFinal(change, 'змінити її');
  const errors: FieldErrorDto[] = [];
  if (body.reasonCode !== undefined && !REASON_CODES.includes(body.reasonCode)) errors.push({ field: 'reasonCode', message: 'Оберіть причину' });
  const reasonCode = body.reasonCode ?? change.reasonCode;
  const reasonText = body.reasonText !== undefined ? body.reasonText?.trim() || null : change.reasonText;
  if (reasonCode === 'OTHER' && !reasonText) errors.push({ field: 'reasonText', message: 'Коротко вкажіть причину для розпорядження' });
  if (reasonText && reasonText.length > MAX_REASON_TEXT) errors.push({ field: 'reasonText', message: `Не довше ${MAX_REASON_TEXT} символів` });
  const room = body.newRoomId ? findRoom(body.newRoomId) : undefined;
  if (body.newRoomId && !room) errors.push({ field: 'newRoomId', message: 'Оберіть аудиторію' });
  if (errors.length > 0) throw badRequest('Перевірте дані зміни.', errors);
  const next = saveChange({
    ...change,
    reasonCode,
    reasonText,
    new: room ? { ...change.new, room: roomRef(room) } : change.new,
  });
  emitChange(next);
  return next;
}

/** На публікації підтверджені зміни розпорядження стають внесеними (DOMAIN §7). */
export function applyChanges(ids: readonly Uuid[], orderId: Uuid, number: number, now: Date): ChangeRecord[] {
  const applied: ChangeRecord[] = [];
  for (const id of new Set(ids)) {
    const change = world().changes.get(id);
    if (!change || change.status !== 'CONFIRMED') continue;
    const next = saveChange(transition(change, 'APPLIED', now, ADMIN_ACTOR, `Внесено в розпорядження №${number}`, { orderId }));
    emitChange(next);
    applied.push(next);
  }
  return applied;
}
