# CLAUDE.md

## Website i18n (`src/main/resources/static/i18n/`)

- When adding new `data-i18n` strings, only translate them into `strings-de.xml` and `strings-en.xml`. Skip `strings-de-at.xml` (Vorarlbergerisch) for new strings — the loader already falls back to the inline German text in the HTML when a key is missing from a locale file, so this is safe.
- Only touch `strings-de-at.xml` when a key is being deleted from `strings-de.xml`/`strings-en.xml` — remove its `de-at` entry too, so no orphaned keys are left behind.
