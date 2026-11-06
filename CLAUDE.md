# CLAUDE.md

## Repository scope

- Change code only in this repository (the server). Never edit code in the client repository `SchneaggchatV3` (usually `~/Desktop/SchneaggchatV3`). This covers subagents too.
- Reading the client repository is allowed: research may search it, and `flow-stability` may trace a flow across both repositories.
- When a server change needs a client change, add it to the client repository's `plans/Todo.md`. That is the only client file agents may write to.

## Website i18n (`src/main/resources/static/i18n/`)

- When adding new `data-i18n` strings, only translate them into `strings-de.xml` and `strings-en.xml`. Skip `strings-de-at.xml` (Vorarlbergerisch) for new strings — the loader already falls back to the inline German text in the HTML when a key is missing from a locale file, so this is safe.
- Only touch `strings-de-at.xml` when a key is being deleted from `strings-de.xml`/`strings-en.xml` — remove its `de-at` entry too, so no orphaned keys are left behind.

## Time values

- Always use `kotlin.time.Instant` / `kotlin.time.Clock` (or plain `Long` epoch millis, e.g. in API responses). Never `java.time.*`, `java.util.Date`, BSON Date or time strings.
- Spring Data stores `kotlin.time.Instant` as an `{epochSeconds, nanosecondsOfSecond}` sub-document. Mongo indexes, range filters, sorts and aggregations on time fields therefore use `<field>.epochSeconds` (e.g. `timestamp.epochSeconds`), not the field itself. Do not register a global Instant↔Date converter — it would break reading all existing data.

## Local Docker test

- `scripts/docker-test/run.sh` runs `./gradlew test` and an end-to-end delivery test (`e2e.py`: real server, sockets, Mongo, Redis) against throwaway containers. `run.sh unit` and `run.sh e2e` run one part. It never touches `mongodb_data` or the compose stack.
- `contextLoads` only passes with Mongo running, so use this script instead of a plain `./gradlew test` when the Spring wiring changed.
- Firebase and APNs are not configured there, so `e2e.py` proves that a push was attempted, not that it arrived.
