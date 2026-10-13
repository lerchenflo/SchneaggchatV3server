# CLAUDE.md

## Website i18n (`src/main/resources/static/i18n/`)

- When adding new `data-i18n` strings, only translate them into `strings-de.xml` and `strings-en.xml`. Skip `strings-de-at.xml` (Vorarlbergerisch) for new strings — the loader already falls back to the inline German text in the HTML when a key is missing from a locale file, so this is safe.
- Only touch `strings-de-at.xml` when a key is being deleted from `strings-de.xml`/`strings-en.xml` — remove its `de-at` entry too, so no orphaned keys are left behind.

## Time values

- Always use `kotlin.time.Instant` / `kotlin.time.Clock` (or plain `Long` epoch millis, e.g. in API responses). Never `java.time.*`, `java.util.Date`, BSON Date or time strings.
- Spring Data stores `kotlin.time.Instant` as an `{epochSeconds, nanosecondsOfSecond}` sub-document. Mongo indexes, range filters, sorts and aggregations on time fields therefore use `<field>.epochSeconds` (e.g. `timestamp.epochSeconds`), not the field itself. Do not register a global Instant↔Date converter — it would break reading all existing data.
