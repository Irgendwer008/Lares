# Localization

Languages v1: English (default, `values/`) and German (`values-de/`). Docs are English.

## Rules
- **No hardcoded user-visible text** in Kotlin/Compose – always `stringResource(R.string.…)`.
  Also applies to notification texts, block screen, guardian alert messages.
- **No string concatenation** – use format args with positional placeholders
  (`%1$s`, `%2$d`), since word order differs between languages.
- Use `<plurals>` for anything with counts ("%d minutes").
- Format dates, times and durations with locale-aware APIs, never manual formatting.
- Leave room for longer texts (German is ~30 % longer); avoid fixed-width text containers.
- Use `translatable="false"` for non-translatable strings (URLs, brand names).
- Keep `strings.xml` grouped by feature with comments for translators.
- Support **per-app language** (`android:localeConfig` + AppCompat `setApplicationLocales`,
  works back to API 26 via AppCompat).
- Guardian alerts: language chosen per channel (guardian may speak another language),
  built with `createConfigurationContext` for that locale.
- Adding a language = add `values-xx/strings.xml` + entry in `locales_config.xml`. Nothing else.
- Lint: enable `MissingTranslation` as error.
