import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderWithProviders } from '@/test/render';
import { reasonWords } from '../format';
import type { FeedItem } from '../model';
import { ActivityFeed } from './ActivityFeed';

const item = (id: string, at: string, text: string): FeedItem => ({ id, sourceId: id, at, kind: 'absence', text, href: null });

describe('ActivityFeed: події через північ', () => {
  it('події вчорашнього дня відділяє рядок «Вчора», сьогоднішні згори — без рядка', () => {
    // 00:30 і 00:03 — сьогодні за Києвом (12.11), 23:50 — ще 11.11
    const items = [
      item('a', '2026-11-11T22:30:00Z', 'Сьогоднішня подія'),
      item('b', '2026-11-11T22:03:00Z', 'Ще сьогодні'),
      item('c', '2026-11-11T21:50:00Z', 'Вчорашня подія'),
    ];
    renderWithProviders(<ActivityFeed items={items} fresh={new Set()} today="2026-11-12" />);
    const rows = within(screen.getByRole('list', { name: 'Останні дії' })).getAllByRole('listitem');
    expect(rows.map((row) => row.textContent)).toEqual(['00:30Сьогоднішня подія', '00:03Ще сьогодні', 'Вчора', '23:50Вчорашня подія']);
  });

  it('усі події — учора: рядок дня стоїть першим', () => {
    renderWithProviders(<ActivityFeed items={[item('a', '2026-11-11T15:00:00Z', 'Подія')]} fresh={new Set()} today="2026-11-12" />);
    const rows = within(screen.getByRole('list', { name: 'Останні дії' })).getAllByRole('listitem');
    expect(rows[0]).toHaveTextContent(/^Вчора$/);
  });
});

describe('reasonWords: причина словами, а не скороченням з аркуша', () => {
  it('«Зв.зам.» читається як «взаємна заміна», інша причина — текстом диспетчера', () => {
    expect(reasonWords('MUTUAL', null)).toBe('взаємна заміна');
    expect(reasonWords('ABSENT', null)).toBe('викладач відсутній');
    expect(reasonWords('OTHER', 'Конф.')).toBe('Конф.');
    expect(reasonWords('OTHER', '  ')).toBe('інша причина');
  });
});
