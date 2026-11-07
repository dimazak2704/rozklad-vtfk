/** Потік подій SSE для макетів адмінки й кабінету (CONTRACTS §2: GET /api/events/stream). */
import { http } from 'msw';
import { eventStreamResponse } from '../events';
import { apiPath } from '../kit';

export const handlers = [http.get(apiPath('/events/stream'), () => eventStreamResponse())];
