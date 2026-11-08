# Module `settings` (core)

Typed system settings stored as JSON under dotted keys; a leaf module (others depend on it, it
depends on nothing but `shared`).

## API (base package `ua.vtc.rozklad.core.settings`)

- `SettingsService`: `current()` → `SystemSettings`, `update(settings, actor)`, convenience accessors
  (`approvalMode()`, `requiresConfirmation()`, `confirmTimeoutMinutes()`, `signatoryPosition()`,
  `signatoryName()`, `collegeName()`, `hoursPerPair()`, `showTeacherFullNames()`,
  `activeBellProfileId()`). Each accessor reads the table; call `current()` once for several values.
- `ChangeApprovalMode {AUTO, CONFIRM, NOTIFY}` — own enum so settings does not depend on substitution; the
  substitution module maps it to its `ApprovalMode` (DOMAIN §7). `requiresConfirmation()` is true for CONFIRM
  only.
- `SettingsConstraint` (SPI): rules that need another module's data, e.g. calendar checks that the
  default bell profile exists and is active.

## Keys and defaults

| Key | DTO field | Default |
|---|---|---|
| `substitution.approvalMode` | `approvalMode` (NOTIFY, CONFIRM, AUTO) | `NOTIFY` (`V1_805` moves an untouched stored CONFIRM to NOTIFY) |
| `substitution.confirmTimeoutMinutes` | `confirmTimeoutMinutes` (5..1440) | `120` |
| `order.signatoryPosition` | `signatoryPosition` | «Заступник директора з НР» |
| `order.signatoryName` | `signatoryName` | «Микола ПОЛІЩУК» |
| `general.collegeName` | `collegeName` | «Вінницький технічний фаховий коледж» |
| `accounting.hoursPerPair` | `hoursPerPair` (1..4) | `2` |
| `public.showTeacherFullNames` | `showTeacherFullNames` | `false` |
| `calendar.activeBellProfileId` | `activeBellProfileId` | profile «2 зміни, 60 хв» (migration) |

Migration `V1_802__setting_defaults.sql` inserts the rows (only missing keys); `SystemSettings.DEFAULTS`
holds the same values for keys without a row (`activeBellProfileId` → `null`). A stored value of the
wrong type is an error (500 + log), never silently replaced by the default.

## Endpoints

`GET/PUT /api/admin/settings` (ADMIN) — the `SettingsDto` of CONTRACTS §2; PUT replaces all values
(`activeBellProfileId` may be `null`); only changed keys get a new `updated_by`/`updated_at`.

## How to test

`PersistentSettingsServiceTest`, `AdminSettingsControllerTest`; defaults in the database are checked
by `CalendarApiIT.referenceDataIsSeededByMigrations`.
