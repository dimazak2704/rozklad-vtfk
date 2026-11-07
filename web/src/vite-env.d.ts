/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Базовий шлях REST API (через gateway). За замовчуванням `/api`. */
  readonly VITE_API_BASE?: string;
  /** Keycloak realm: `http://localhost:8180/realms/rozklad`. */
  readonly VITE_OIDC_AUTHORITY?: string;
  readonly VITE_OIDC_CLIENT_ID?: string;
  readonly VITE_OIDC_SCOPE?: string;
  /** Лише для `npm run dev`: ролі фіктивного користувача без Keycloak, напр. `ADMIN` або `TEACHER`. */
  readonly VITE_DEV_AUTH_ROLES?: string;
  /** Куди Vite-проксі веде /api (за замовчуванням gateway :8080). */
  readonly VITE_DEV_API_TARGET?: string;
  /** `1` — показати панель TanStack Query Devtools у `npm run dev`. */
  readonly VITE_QUERY_DEVTOOLS?: string;
  /** Посилання на Telegram-бота для сторінки «Про систему». */
  readonly VITE_TELEGRAM_BOT_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
