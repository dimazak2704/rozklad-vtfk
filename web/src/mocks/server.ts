/**
 * Мок-API для Vitest (msw/node): ті самі обробники, що й у браузері. Підключено в src/test/setup.ts:
 * незмоковані запити падають з помилкою, обробники й сховища скидаються після кожного тесту.
 * Свій варіант відповіді в тесті: `server.use(http.get(apiPath('/…'), () => problem({ status: 500 })))`.
 */
import { setupServer } from 'msw/node';
import { handlers } from './registry';

export const server = setupServer(...handlers);
