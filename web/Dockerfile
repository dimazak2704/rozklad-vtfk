# syntax=docker/dockerfile:1
# SPA «Розклад ВТФК»: збірка Vite → статика в nginx (SPA-fallback, /api → gateway, gzip, кеш).
# docker build -t rozklad-web web/   →   docker run -p 8088:80 rozklad-web

FROM node:24-alpine AS build
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY . .

# Налаштування збірки (import.meta.env). Значення за замовчуванням — для docker compose на localhost.
# VITE_OIDC_AUTHORITY порожній — вхід на тому ж походженні: ${origin}/auth (nginx проксіює /auth/ на сервіс auth)
ARG VITE_API_BASE=/api
ARG VITE_OIDC_AUTHORITY=
ARG VITE_OIDC_CLIENT_ID=rozklad-web
ARG VITE_TELEGRAM_BOT_URL=https://t.me/scheduler_vtfk_bot
ENV VITE_API_BASE=$VITE_API_BASE \
    VITE_OIDC_AUTHORITY=$VITE_OIDC_AUTHORITY \
    VITE_OIDC_CLIENT_ID=$VITE_OIDC_CLIENT_ID \
    VITE_TELEGRAM_BOT_URL=$VITE_TELEGRAM_BOT_URL

RUN npm run build

# стабільна гілка nginx (парна мінорна версія) отримує виправлення безпеки; mainline-тег на кшталт 1.29
# замерзає, щойно починається наступна mainline
FROM nginx:1.30-alpine AS runtime
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
HEALTHCHECK --interval=30s --timeout=3s --retries=3 CMD wget -qO- http://127.0.0.1/healthz || exit 1
