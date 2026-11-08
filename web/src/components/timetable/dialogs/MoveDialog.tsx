import { useQuery } from '@tanstack/react-query';
import { CircleCheck, LoaderCircle, TriangleExclamationPoint } from 'lucide-react';
import { useMemo, useState } from 'react';
import { errorMessage } from '@/api/errors';
import { qk } from '@/api/queryKeys';
import { previewMove } from '@/api/timetable';
import type { MoveLessonRequest, Parity, PublicRoomDto, TemplateLessonDto, Uuid, Weekday } from '@/api/types';
import { Button, Combobox, Dialog, DialogClose, DialogContent, Field, SegmentedControl, Select, type ComboboxOption } from '@/components/ui';
import { useDebouncedValue } from '@/hooks/useDebouncedValue';
import { weekdayShort } from '@/lib/labels';
import { roomLabel } from '@/lib/schedule';
import { WEEKDAYS } from '../model/grid';
import { SlotIndex, withStreamMates } from '../model/occupancy';
import { lessonTitle } from '../model/text';

interface MoveDialogProps {
  versionId: Uuid;
  lesson: TemplateLessonDto | null;
  onOpenChange: (open: boolean) => void;
  rooms: readonly PublicRoomDto[];
  lessons: readonly TemplateLessonDto[];
  onMove: (lesson: TemplateLessonDto, target: MoveLessonRequest, room: PublicRoomDto | null) => Promise<boolean>;
}

/**
 * Перенесення з клавіатури чи без перетягування: день, пара, тижні й аудиторія, а сервер одразу
 * перевіряє слот (`move?dryRun=true`). Тут можна й змінити частоту — ціла пара ↔ половинка.
 */
export function MoveDialog({ versionId, lesson, onOpenChange, rooms, lessons, onMove }: MoveDialogProps) {
  return (
    <Dialog open={lesson !== null} onOpenChange={onOpenChange}>
      {lesson ? (
        <MoveForm key={lesson.id} versionId={versionId} lesson={lesson} rooms={rooms} lessons={lessons} onMove={onMove} onDone={() => onOpenChange(false)} />
      ) : null}
    </Dialog>
  );
}

function MoveForm({
  versionId,
  lesson,
  rooms,
  lessons,
  onMove,
  onDone,
}: Omit<MoveDialogProps, 'lesson' | 'onOpenChange'> & { lesson: TemplateLessonDto; onDone: () => void }) {
  const [weekday, setWeekday] = useState<Weekday>(lesson.weekday);
  const [pair, setPair] = useState(lesson.pair);
  const [parity, setParity] = useState<Parity>(lesson.parity);
  const [roomId, setRoomId] = useState<Uuid | null>(lesson.room?.id ?? null);
  const [submitting, setSubmitting] = useState(false);

  const target = useMemo<MoveLessonRequest>(() => {
    const body: MoveLessonRequest = { weekday, pair, parity };
    if (roomId && roomId !== lesson.room?.id) body.roomId = roomId;
    return body;
  }, [weekday, pair, parity, roomId, lesson.room?.id]);
  const unchanged = weekday === lesson.weekday && pair === lesson.pair && parity === lesson.parity && !target.roomId;
  const debounced = useDebouncedValue(target, 200);
  const settled = debounced === target;

  const preview = useQuery({
    queryKey: [...qk.admin.timetable.all, 'move-preview', versionId, lesson.id, debounced],
    queryFn: ({ signal }) => previewMove(versionId, lesson.id, debounced, signal),
    enabled: !unchanged,
    staleTime: 0,
    gcTime: 0,
    retry: false,
  });

  const index = useMemo(() => new SlotIndex(lessons), [lessons]);
  const ignoreIds = useMemo(() => withStreamMates(lesson, lessons), [lesson, lessons]);
  const roomOptions: ComboboxOption[] = rooms.map((room) => {
    const busy = index.roomLessons({
      weekday,
      pair,
      parity,
      groupId: lesson.group.id,
      subgroup: lesson.subgroup,
      shift: null,
      roomId: room.id,
      ignoreIds,
      streamId: lesson.streamId ?? null,
    });
    return {
      value: room.id,
      label: roomLabel(room.name) ?? room.name,
      description: room.id === lesson.room?.id ? 'Поточна' : busy.length > 0 ? `Зайнята: ${busy.map((l) => l.group.code).join(', ')}` : 'Вільна',
    };
  });

  const frequencyChanged = (lesson.parity === 'BOTH') !== (parity === 'BOTH');
  const checking = !unchanged && (!settled || preview.isFetching);
  const result = !unchanged && settled ? preview.data : undefined;
  const blocked = result?.status === 'blocked';

  const submit = async () => {
    setSubmitting(true);
    const room = target.roomId ? (rooms.find((r) => r.id === target.roomId) ?? null) : null;
    const moved = await onMove(lesson, target, room);
    setSubmitting(false);
    if (moved) onDone();
  };

  return (
    <DialogContent
      // фокус — на вибраний день: стрілки одразу змінюють слот
      onOpenAutoFocus={(event) => {
        event.preventDefault();
        document.querySelector<HTMLElement>('[data-move-form] [data-state="on"]')?.focus();
      }}
      title="Перемістити пару"
      description={lessonTitle(lesson)}
      footer={
        <>
          <DialogClose asChild>
            <Button variant="ghost">Скасувати</Button>
          </DialogClose>
          <Button variant="primary" onClick={() => void submit()} disabled={unchanged || blocked || checking} loading={submitting}>
            Перемістити
          </Button>
        </>
      }
    >
      <div data-move-form="" className="flex flex-col gap-4">
        <div className="flex flex-col gap-1.5">
          <span className="text-ui font-semibold text-primary">День</span>
          <SegmentedControl
            aria-label="День тижня"
            stretch
            value={String(weekday)}
            onValueChange={(value) => setWeekday(Number(value) as Weekday)}
            options={WEEKDAYS.map((day) => ({ value: String(day), label: weekdayShort(day) }))}
          />
        </div>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Пара">
            <Select
              value={String(pair)}
              onValueChange={(value) => setPair(Number(value))}
              options={[1, 2, 3, 4, 5, 6, 7, 8].map((p) => ({ value: String(p), label: `${p} пара` }))}
            />
          </Field>
          <Field label="Аудиторія">
            <Combobox value={roomId} onValueChange={setRoomId} options={roomOptions} placeholder="Не призначено" searchPlaceholder="Номер: 301, с.з." />
          </Field>
        </div>
        <div className="flex flex-col gap-1.5">
          <span className="text-ui font-semibold text-primary">Тижні</span>
          <SegmentedControl<Parity>
            aria-label="Тижні"
            value={parity}
            onValueChange={setParity}
            options={[
              { value: 'BOTH', label: 'Щотижня' },
              { value: 'NUMERATOR', label: 'Чисельник' },
              { value: 'DENOMINATOR', label: 'Знаменник' },
            ]}
          />
          {frequencyChanged ? (
            <p className="text-caption text-change">
              {parity === 'BOTH'
                ? 'Пара стане щотижневою — годин за семестр буде вдвічі більше.'
                : 'Пара стане через тиждень — годин за семестр буде вдвічі менше.'}
            </p>
          ) : null}
        </div>

        <div role="status" aria-live="polite" className="min-h-14 rounded-control border border-default bg-sunken px-3 py-2.5 text-caption">
          {unchanged ? (
            <p className="text-secondary">Оберіть новий день, пару, тижні чи аудиторію — слот перевіримо одразу.</p>
          ) : checking ? (
            <p className="flex items-center gap-1.5 text-secondary">
              <LoaderCircle aria-hidden="true" className="size-4 text-accent motion-safe:animate-spin" />
              Перевіряємо слот…
            </p>
          ) : preview.error ? (
            <p className="text-danger">Не вдалося перевірити: {errorMessage(preview.error)}</p>
          ) : result?.status === 'blocked' ? (
            <>
              <p className="flex items-center gap-1.5 font-semibold text-danger">
                <TriangleExclamationPoint aria-hidden="true" className="size-4" />
                Сюди не можна: зʼявиться конфлікт
              </p>
              <ul className="mt-1 flex flex-col gap-0.5 ps-5.5 text-primary">
                {result.conflicts.map((conflict) => (
                  <li key={conflict.message}>{conflict.message}</li>
                ))}
              </ul>
            </>
          ) : result?.status === 'ok' ? (
            <>
              <p className="flex items-center gap-1.5 font-semibold text-add">
                <CircleCheck aria-hidden="true" className="size-4" />
                {result.conflicts.length > 0 ? 'Можна перенести, але будуть попередження' : 'Слот вільний — можна переносити'}
              </p>
              {result.conflicts.length > 0 ? (
                <ul className="mt-1 flex flex-col gap-0.5 ps-5.5 text-change">
                  {result.conflicts.map((conflict) => (
                    <li key={conflict.message}>{conflict.message}</li>
                  ))}
                </ul>
              ) : null}
            </>
          ) : null}
        </div>
      </div>
    </DialogContent>
  );
}
