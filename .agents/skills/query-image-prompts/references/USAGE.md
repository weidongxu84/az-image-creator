# query_prompts.py — full reference

```
python .agents/skills/query-image-prompts/scripts/query_prompts.py [options]
```

## Options

| Option | Description |
|---|---|
| `--account NAME` | Storage account (default `azimagecreator`) |
| `--table NAME` | Table name (default `imageprompts`) |
| `--text WORD` | Case-insensitive substring match on `Prompt` (client-side) |
| `--regex PATTERN` | Python `re` regex match on `Prompt` (client-side, mutually exclusive with `--text`) |
| `--case-sensitive` | Makes `--text`/`--regex` case-sensitive |
| `--since UTC_ISO8601` | Lower bound on `CreatedAt`, e.g. `2026-07-01T00:00:00Z` (server-side) |
| `--until UTC_ISO8601` | Upper bound on `CreatedAt` (server-side) |
| `--model NAME` | Exact match, e.g. `gpt-image-2` (server-side) |
| `--provider {azure-openai,flux}` | Exact match (server-side) |
| `--operation {generate,edit}` | Exact match (server-side) |
| `--limit N` | Max rows printed after filtering (default 50) |
| `--sort {asc,desc}` | Order by `CreatedAt` (default `desc`, newest first) |
| `--format {table,json}` | Output format (default `json` — use `table` only if the user explicitly wants a quick plain-text scan) |

## How filtering works

- `--since`/`--until`/`--model`/`--provider`/`--operation` become an OData
  `$filter` sent to Azure Table Storage — cheap and scales to large tables.
- `--text`/`--regex` are **not** supported by Table Storage's OData filter
  (no `contains`/regex operator there), so they're applied in Python after
  the rows are fetched. Combine them with a date range to keep the
  server-side result set small on a big table.

## Examples

Prompts mentioning "sunset" or "beach" (regex), last 30 days:
```bash
python .agents/skills/query-image-prompts/scripts/query_prompts.py \
  --regex "sunset|beach" --since 2026-07-03T00:00:00Z
```

All `edit` operations using flux, as JSON:
```bash
python .agents/skills/query-image-prompts/scripts/query_prompts.py \
  --operation edit --provider flux --format json
```

Plain substring search, case-sensitive, oldest first:
```bash
python .agents/skills/query-image-prompts/scripts/query_prompts.py \
  --text "Tokyo" --case-sensitive --sort asc
```

## Auth / permissions

The script never touches keys, connection strings, or SAS tokens — it shells
out to `az storage entity query --auth-mode login`, which uses your current
`az login` identity. That identity needs a data-plane RBAC role on the
storage account (`Storage Table Data Reader` is enough for read-only use).
If missing, the script prints a 403 hint instead of failing silently.
