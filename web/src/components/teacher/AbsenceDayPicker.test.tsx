import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it } from 'vitest';
import { nextDaySelection, selectionSummary, type DaySelection } from './absence-days';
import { AbsenceDayPicker } from './AbsenceDayPicker';

// пʼятниця, 20.11.2026 — день, коли власник клікав кабінет на стенді
const TODAY = '2026-11-20';
/** Нерозривний пробіл між числом і словом (formatDayMonth, countLabel). */
const NBSP = String.fromCharCode(0xa0);

describe('nextDaySelection', () => {
  const start = (day: string): DaySelection => ({ from: day, to: day, awaitingEnd: true });

  it('перший клік — початок (один день), далі чекаємо кінця', () => {
    expect(nextDaySelection(null, '2026-11-23')).toEqual(start('2026-11-23'));
  });

  it('другий клік на пізнішому дні — кінець', () => {
    expect(nextDaySelection(start('2026-11-20'), '2026-11-24')).toEqual({ from: '2026-11-20', to: '2026-11-24', awaitingEnd: false });
  });

  it('другий клік на ранішому дні — новий початок, а не розвернутий діапазон', () => {
    expect(nextDaySelection(start('2026-11-24'), '2026-11-20')).toEqual(start('2026-11-20'));
  });

  it('другий клік на тому самому дні — лишається один день (вибір не знімається)', () => {
    expect(nextDaySelection(start('2026-11-20'), '2026-11-20')).toEqual({ from: '2026-11-20', to: '2026-11-20', awaitingEnd: false });
  });

  it('третій клік починає заново — і після діапазону, і після одного дня, і на тому самому дні', () => {
    const range: DaySelection = { from: '2026-11-20', to: '2026-11-24', awaitingEnd: false };
    expect(nextDaySelection(range, '2026-11-26')).toEqual(start('2026-11-26'));
    expect(nextDaySelection(range, '2026-11-20')).toEqual(start('2026-11-20'));
    const single: DaySelection = { from: '2026-11-20', to: '2026-11-20', awaitingEnd: false };
    expect(nextDaySelection(single, '2026-11-20')).toEqual(start('2026-11-20'));
  });
});

describe('selectionSummary', () => {
  it('один день — день тижня й дата; вихідний так і названо', () => {
    expect(selectionSummary('2026-11-20', '2026-11-20')).toEqual({ when: `Пʼятниця, 20${NBSP}листопада`, note: null });
    expect(selectionSummary('2026-11-21', '2026-11-21').note).toBe('вихідний');
  });

  it('діапазон — лише навчальні дні (вихідні не рахуються)', () => {
    expect(selectionSummary('2026-11-20', '2026-11-24')).toEqual({ when: `20–24${NBSP}листопада`, note: `3${NBSP}навчальні дні` });
    expect(selectionSummary('2026-11-21', '2026-11-22').note).toBe('навчальних днів немає');
    const acrossMonths = selectionSummary('2026-11-30', '2026-12-01');
    expect(acrossMonths.when).toMatch(/^30\sлистопада – 1\sгрудня$/);
    expect(acrossMonths.note).toBe(`2${NBSP}навчальні дні`);
  });
});

/** Як у формі: стан тримає батько, календар лише каже, що обрано далі. */
function Harness({ initial = { from: TODAY, to: TODAY, awaitingEnd: false } }: { initial?: DaySelection | null }) {
  const [value, setValue] = useState<DaySelection | null>(initial);
  return (
    <>
      <AbsenceDayPicker value={value} onChange={setValue} today={TODAY} defaultMonth={TODAY} />
      <span data-testid="value">{value ? `${value.from}..${value.to}${value.awaitingEnd ? ' …' : ''}` : 'none'}</span>
    </>
  );
}

const day = (name: RegExp) => screen.getByRole('button', { name });
const cell = (iso: string) => document.querySelector<HTMLElement>(`td[data-day="${iso}"]`);
// останній status — підсумок під календарем (перший — назва місяця в DayPicker)
const status = () => screen.getAllByRole('status').at(-1);
const summary = () => status()?.querySelector('[data-summary]')?.textContent ?? '';
const hint = () => status()?.querySelector('[data-hint]')?.textContent ?? '';
const value = () => screen.getByTestId('value').textContent;
const FRIDAY_20 = /пʼятниця, 20|п’ятниця, 20|п'ятниця, 20/;

describe('AbsenceDayPicker: кліки', () => {
  it('початково обраний день: клік на ньому — це початок, а не зняття вибору', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    expect(summary()).toMatch(/^П.ятниця, 20\sлистопада$/);
    expect(hint()).toBe('Щоб змінити дні, натисніть перший із них.');

    await user.click(day(FRIDAY_20));
    expect(value()).toBe('2026-11-20..2026-11-20 …');
    expect(cell('2026-11-20')).toHaveAttribute('aria-selected', 'true');
    expect(summary()).toMatch(/^П.ятниця, 20\sлистопада$/);
    expect(hint()).toBe('Оберіть останній день, якщо їх кілька.');
  });

  it('1-й клік — початок одразу підсвічений; 2-й пізніший — діапазон з підсумком навчальних днів', async () => {
    const user = userEvent.setup();
    render(<Harness initial={null} />);
    expect(summary()).toBe('Дні ще не обрано.');
    expect(hint()).toBe('Натисніть перший день відсутності.');

    await user.click(day(FRIDAY_20));
    // раніше перший день не мав жодного підсвічування (діапазон без кінця) — здавалося, що клік не спрацював
    expect(cell('2026-11-20')?.className).toContain('bg-accent');

    await user.click(day(/вівторок, 24/));
    expect(value()).toBe('2026-11-20..2026-11-24');
    expect(summary()).toMatch(/^20–24\sлистопада, 3\sнавчальні дні$/);
    expect(hint()).toBe('Щоб змінити дні, натисніть перший із них.');
    for (const iso of ['2026-11-20', '2026-11-21', '2026-11-23', '2026-11-24']) expect(cell(iso)).toHaveAttribute('aria-selected', 'true');
    expect(cell('2026-11-25')).not.toHaveAttribute('aria-selected');
  });

  it('2-й клік раніше за початок — новий початок; 3-й клік починає заново', async () => {
    const user = userEvent.setup();
    render(<Harness initial={null} />);
    await user.click(day(/вівторок, 24/));
    await user.click(day(/понеділок, 23/));
    expect(value()).toBe('2026-11-23..2026-11-23 …');
    await user.click(day(/середа, 25/));
    expect(value()).toBe('2026-11-23..2026-11-25');

    await user.click(day(/четвер, 26/));
    expect(value()).toBe('2026-11-26..2026-11-26 …');
    expect(summary()).toMatch(/^Четвер, 26\sлистопада$/);
  });

  it('повторний клік на початку лишає один день, і наступний клік — знову початок', async () => {
    const user = userEvent.setup();
    render(<Harness initial={null} />);
    await user.click(day(/понеділок, 23/));
    await user.click(day(/понеділок, 23/));
    expect(value()).toBe('2026-11-23..2026-11-23');
    expect(hint()).toBe('Щоб змінити дні, натисніть перший із них.');
    await user.click(day(/понеділок, 23/));
    expect(value()).toBe('2026-11-23..2026-11-23 …');
  });

  it('минулі дні вимкнені: клік нічого не міняє', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    const yesterday = day(/четвер, 19/);
    expect(yesterday).toBeDisabled();
    await user.click(yesterday);
    expect(value()).toBe('2026-11-20..2026-11-20');
  });

  it('вихідні можна взяти в діапазон, але рахуються лише навчальні дні; один вихідний так і названо', async () => {
    const user = userEvent.setup();
    render(<Harness initial={null} />);
    await user.click(day(/субота, 21/));
    expect(summary()).toMatch(/^Субота, 21\sлистопада, вихідний$/);
    await user.click(day(/неділя, 22/));
    expect(summary()).toMatch(/^21–22\sлистопада, навчальних днів немає$/);
  });
});

describe('AbsenceDayPicker: попередній перегляд і клавіатура', () => {
  const previewEnd = (iso: string) => cell(iso)?.className.includes('border-dashed') ?? false;
  const inPreview = (iso: string) => cell(iso)?.className.includes('[&>button]:rounded-none') ?? false;

  it('після початку наведення показує діапазон до дня під курсором; без початку — нічого', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.hover(day(/вівторок, 24/));
    expect(previewEnd('2026-11-24')).toBe(false);

    await user.click(day(FRIDAY_20));
    await user.hover(day(/вівторок, 24/));
    expect(previewEnd('2026-11-24')).toBe(true);
    expect(['2026-11-21', '2026-11-22', '2026-11-23'].every(inPreview)).toBe(true);
    expect(inPreview('2026-11-25')).toBe(false);

    // раніший день — це буде новий початок, а не діапазон: перегляду немає
    await user.hover(day(/четвер, 19/));
    expect(previewEnd('2026-11-19')).toBe(false);
    expect(inPreview('2026-11-21')).toBe(false);

    await user.unhover(day(/четвер, 19/));
    await user.click(day(/вівторок, 24/));
    expect(value()).toBe('2026-11-20..2026-11-24');
    expect(previewEnd('2026-11-24')).toBe(false);
  });

  it('клавіатура: Enter — початок, стрілки показують перегляд, Enter — кінець', async () => {
    const user = userEvent.setup();
    render(<Harness initial={null} />);
    day(FRIDAY_20).focus();
    await user.keyboard('{Enter}');
    expect(value()).toBe('2026-11-20..2026-11-20 …');

    await user.keyboard('{ArrowRight}{ArrowRight}{ArrowRight}');
    expect(day(/понеділок, 23/)).toHaveFocus();
    expect(previewEnd('2026-11-23')).toBe(true);
    await user.keyboard('{Enter}');
    expect(value()).toBe('2026-11-20..2026-11-23');
    expect(summary()).toMatch(/^20–23\sлистопада, 2\sнавчальні дні$/);
  });
});
