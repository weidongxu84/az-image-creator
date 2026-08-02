---
name: query-image-prompts
description: "Query the imageprompts Azure Table used by az-image-creator to find prompts by text/regex, date range, model, provider, or operation. TRIGGERS: query image prompts, search prompts, find prompt containing, prompt history, imageprompts table, storage table query, prompts since last month, list generated prompts."
---

# Query Image Prompts

Queries the `imageprompts` Azure Table (account `azimagecreator`) that
`PromptStorageService` writes to. Uses `az storage entity query --auth-mode
login` under the hood — no keys, just your own `az login` session and the
`Storage Table Data Reader` (or `Contributor`) RBAC role on the storage
account.

## When invoked

1. If the user gives a relative date (e.g. "since last month", "last 7
   days"), convert it to UTC ISO-8601 yourself using the current date/time
   before calling the script — the script only accepts explicit UTC
   timestamps (`--since` / `--until`).
2. Run the script from the repo root:

```bash
python .agents/skills/query-image-prompts/scripts/query_prompts.py \
  --regex "sunset|beach" \
  --since 2026-07-01T00:00:00Z
```

3. If it fails with an authorization/403 error, tell the user their Azure AD
   account likely lacks the `Storage Table Data Reader` role on
   `azimagecreator` and ask before granting it (don't change RBAC silently).

## Key options

- `--text WORD` — plain case-insensitive substring match on `Prompt`.
- `--regex PATTERN` — Python `re` regex match on `Prompt` (mutually exclusive
  with `--text`). Both are applied **client-side** after fetching rows —
  Azure Table's OData filter has no contains/regex operator, so regex costs
  no real extra overhead over substring search.
- `--since` / `--until` — UTC ISO-8601 bounds, filtered server-side on
  `CreatedAt`.
- `--model`, `--provider` (`azure-openai`|`flux`), `--operation`
  (`generate`|`edit`) — server-side equality filters.
- `--format` — defaults to `json` (full untruncated rows, needed since
  prompts are typically the whole point of the query). Only use `--format
  table` if the user explicitly wants a quick truncated plain-text scan.

## Presenting results

The script defaults to `--format json`. Render the results yourself as a
markdown table in your reply (don't just paste the script's raw stdout) —
prompt text is itself markdown-ish (headings, bold), so keep each cell on
one line (strip newlines) and truncate long prompts unless the user asked
to see one in full.

Full option reference and examples: [USAGE.md](references/USAGE.md)
