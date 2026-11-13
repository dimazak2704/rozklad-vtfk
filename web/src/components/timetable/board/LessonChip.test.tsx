import { DndContext } from '@dnd-kit/core';
import { act, fireEvent, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { TemplateLessonDto } from '@/api/types';
import { resetHoverCards } from '@/components/ui/hover-intent';
import { renderWithProviders } from '@/test/render';
import { createDragStore } from './dragStore';
import { LessonChip } from './LessonChip';

function lesson(id: string, partial: Partial<TemplateLessonDto> = {}): TemplateLessonDto {
  return {
    id,
    weekday: 1,
    pair: 1,
    parity: 'BOTH',
    group: { id: 'g-1ea1', code: '1ЕА1', course: 1 },
    subgroup: 1,
    discipline: { id: 'd-en', shortName: 'Ін.мова', name: 'Іноземна мова', color: null },
    teacher: { id: 't-1', shortName: 'Крисак Л.В.', displayName: 'Крисак' },
    room: { id: 'r-110', name: '110', type: 'CLASSROOM' },
    lessonKind: 'PRACTICE',
    loadItemId: null,
    pinned: false,
    online: false,
    streamId: null,
    ...partial,
  };
}

const noop = () => {};

function renderChips(items: Array<{ lesson: TemplateLessonDto; compact?: boolean; narrow?: boolean }>) {
  const store = createDragStore();
  return renderWithProviders(
    <DndContext>
      {items.map((item) => (
        <LessonChip
          key={item.lesson.id}
          lesson={item.lesson}
          view="group"
          compact={item.compact ?? false}
          narrow={item.narrow ?? true}
          conflicts={[]}
          selected={false}
          editable
          store={store}
          tabIndex={-1}
          onSelect={noop}
          onOpen={noop}
          onFocusItem={noop}
        />
      ))}
    </DndContext>,
  );
}

describe('LessonChip: підгрупа на всю пару', () => {
  it('під назвою — викладач і аудиторія окремими рядками', () => {
    renderChips([{ lesson: lesson('a') }, { lesson: lesson('b', { subgroup: 2, teacher: { id: 't-2', shortName: 'Михальчук О.', displayName: 'Михальчук' }, room: { id: 'r-20', name: '20', type: 'CLASSROOM' } }) }]);
    const [first, second] = screen.getAllByRole('button');
    expect(first).toHaveTextContent('Ін.мова');
    expect(first).toHaveTextContent('Крисак');
    expect(first).toHaveTextContent('ауд. 110');
    expect(second).toHaveTextContent('Михальчук');
    expect(second).toHaveTextContent('ауд. 20');
    // кожне — своїм рядком, а не «Крисак · ауд. 110» в один
    expect(first).not.toHaveTextContent('·');
  });

  it('у половинці пари (чисельник чи знаменник) лишається одна назва: два рядки туди не вміщаються', () => {
    renderChips([{ lesson: lesson('a', { parity: 'NUMERATOR' }), compact: true }]);
    const chip = screen.getByRole('button');
    expect(chip).toHaveTextContent('Ін.мова');
    expect(chip).not.toHaveTextContent('Крисак');
  });
});

describe('LessonChip: картка при наведенні', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    resetHoverCards();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  const hover = (el: HTMLElement) => fireEvent.pointerOver(el, { pointerType: 'mouse' });
  const leave = (el: HTMLElement) => fireEvent.pointerOut(el, { pointerType: 'mouse' });

  it('картка лише для читання: миша проходить крізь неї до сусідньої клітинки', () => {
    renderChips([{ lesson: lesson('a'), narrow: false }]);
    hover(screen.getByRole('button'));
    act(() => {
      vi.advanceTimersByTime(400);
    });
    const card = screen.getByText('Іноземна мова').closest('[data-hover-peek]');
    expect(card).not.toBeNull();
  });

  it('поки картка відкрита, сусідня пара показує свою одразу, а попередня зникає', () => {
    renderChips([
      { lesson: lesson('a', { discipline: { id: 'd-1', shortName: 'БіЕ', name: 'Біологія і екологія', color: null } }), narrow: false },
      { lesson: lesson('b', { discipline: { id: 'd-2', shortName: 'Вст.спц.', name: 'Вступ до спеціальності', color: null } }), narrow: false },
    ]);
    const [monday, tuesday] = screen.getAllByRole('button') as [HTMLElement, HTMLElement];
    hover(monday);
    act(() => {
      vi.advanceTimersByTime(400);
    });
    expect(screen.getByText('Біологія і екологія')).toBeInTheDocument();

    leave(monday);
    hover(tuesday);
    // без затримки: навіть один «тік» таймера не потрібен
    act(() => {
      vi.advanceTimersByTime(0);
    });
    expect(screen.getByText('Вступ до спеціальності')).toBeInTheDocument();
    expect(screen.queryByText('Біологія і екологія')).not.toBeInTheDocument();
    expect(screen.getByText('Вступ до спеціальності').closest('[data-hover-instant]')).not.toBeNull();
  });

  it('Escape закриває картку', () => {
    renderChips([{ lesson: lesson('a'), narrow: false }]);
    hover(screen.getByRole('button'));
    act(() => {
      vi.advanceTimersByTime(400);
    });
    expect(screen.getByText('Іноземна мова')).toBeInTheDocument();
    fireEvent.keyDown(document.body, { key: 'Escape' });
    act(() => {
      vi.runAllTimers();
    });
    expect(screen.queryByText('Іноземна мова')).not.toBeInTheDocument();
  });

  it('фокус з клавіатури картки не відкриває: ті самі відомості — у панелі «Вибрана пара»', () => {
    renderChips([{ lesson: lesson('a'), narrow: false }]);
    act(() => {
      screen.getByRole('button').focus();
    });
    act(() => {
      vi.advanceTimersByTime(1000);
    });
    expect(screen.queryByText('Іноземна мова')).not.toBeInTheDocument();
  });
});
