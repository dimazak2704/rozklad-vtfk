import { screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import type { DeliveryDto } from '@/api/types';
import { apiPath } from '@/mocks/kit';
import { server } from '@/mocks/server';
import { resetMockStores } from '@/mocks/store';
import { renderWithProviders } from '@/test/render';
import { DeliverySummary } from './DeliverySummary';

const delivery = (id: string, chatId: string, chatTitle: string, kind: DeliveryDto['kind'], status: DeliveryDto['status']): DeliveryDto => ({
  id,
  orderId: 'o-1',
  chatId,
  chatTitle,
  bindingType: 'GROUP_CHAT',
  purpose: 'ORDER_GROUP',
  kind,
  status,
  attempts: 1,
  error: status === 'FAILED' ? 'Чат не знайдено' : null,
  createdAt: '2026-11-09T05:30:00Z',
  sentAt: status === 'SENT' ? '2026-11-09T05:30:05Z' : null,
  nextAttemptAt: null,
});

function serve(items: DeliveryDto[]) {
  server.use(http.get(apiPath('/notify/deliveries'), () => HttpResponse.json({ items, page: 0, size: 200, total: items.length })));
}

beforeEach(() => resetMockStores());

describe('DeliverySummary', () => {
  it('рахує чати, а не повідомлення: текст і зображення в один чат — це один чат', async () => {
    serve([
      delivery('d1', '-•••1', 'Чат 4КІ1', 'TEXT', 'SENT'),
      delivery('d2', '-•••1', 'Чат 4КІ1', 'PHOTO', 'SENT'),
      delivery('d3', '-•••2', 'Чат 2М', 'TEXT', 'SENT'),
      delivery('d4', '-•••2', 'Чат 2М', 'PHOTO', 'SENT'),
    ]);
    renderWithProviders(<DeliverySummary orderId="o-1" />);
    expect(await screen.findByText(/^Надіслано: 2\s+чати \(4\s+повідомлення\)$/)).toBeInTheDocument();
  });

  it('чат з недоставленим повідомленням не рахується надісланим і названий один раз', async () => {
    serve([
      delivery('d1', '-•••1', 'Чат 4КІ1', 'TEXT', 'SENT'),
      delivery('d2', '-•••1', 'Чат 4КІ1', 'PHOTO', 'SENT'),
      delivery('d3', '-•••2', 'Чат 2М', 'TEXT', 'FAILED'),
      delivery('d4', '-•••2', 'Чат 2М', 'PHOTO', 'FAILED'),
      delivery('d5', '-•••3', 'Чат 3Е', 'TEXT', 'QUEUED'),
    ]);
    renderWithProviders(<DeliverySummary orderId="o-1" />);
    expect(await screen.findByText(/^Надіслано: 1\s+чат \(2\s+повідомлення\)$/)).toBeInTheDocument();
    expect(screen.getByText(/^У черзі: 1\s+чат$/)).toBeInTheDocument();
    expect(screen.getByText(/^Не\sдоставлено:\sЧат\s2М$/)).toBeInTheDocument();
  });

  it('по одному повідомленню на чат — без зайвого уточнення', async () => {
    serve([delivery('d1', '-•••1', 'Чат 4КІ1', 'TEXT', 'SENT')]);
    renderWithProviders(<DeliverySummary orderId="o-1" />);
    expect(await screen.findByText(/^Надіслано: 1\s+чат$/)).toBeInTheDocument();
  });
});
