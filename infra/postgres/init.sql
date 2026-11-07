-- Бази й користувачі сервісів для розробки (виконується один раз, коли том postgres порожній).
-- Паролі — лише для локального стенда; кожен сервіс бачить тільки свою базу.

CREATE USER core WITH PASSWORD 'core';
CREATE DATABASE core OWNER core ENCODING 'UTF8';
REVOKE CONNECT ON DATABASE core FROM PUBLIC;

CREATE USER scheduler WITH PASSWORD 'scheduler';
CREATE DATABASE scheduler OWNER scheduler ENCODING 'UTF8';
REVOKE CONNECT ON DATABASE scheduler FROM PUBLIC;

CREATE USER importer WITH PASSWORD 'importer';
CREATE DATABASE importer OWNER importer ENCODING 'UTF8';
REVOKE CONNECT ON DATABASE importer FROM PUBLIC;

CREATE USER notification WITH PASSWORD 'notification';
CREATE DATABASE notification OWNER notification ENCODING 'UTF8';
REVOKE CONNECT ON DATABASE notification FROM PUBLIC;

CREATE USER keycloak WITH PASSWORD 'keycloak';
CREATE DATABASE keycloak OWNER keycloak ENCODING 'UTF8';
REVOKE CONNECT ON DATABASE keycloak FROM PUBLIC;
