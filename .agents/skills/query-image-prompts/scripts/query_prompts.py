#!/usr/bin/env python3
"""Query the az-image-creator `imageprompts` Azure Table.

Auth: shells out to `az storage entity query --auth-mode login`, reusing your
current `az login` session and whatever data-plane RBAC role (e.g. "Storage
Table Data Reader") is granted on the storage account. No keys, connection
strings, or SAS tokens are used or stored.

Filtering:
  --since / --until   Server-side OData $filter on the CreatedAt property
                       (cheap, index-friendly). Must be UTC ISO-8601, e.g.
                       2026-07-01T00:00:00Z.
  --model / --provider / --operation
                       Server-side OData $filter equality clauses.
  --text / --regex     Free-text search on the Prompt field. Azure Table
                       Storage's OData filter has no "contains" or regex
                       operator, so these are applied client-side in Python
                       after fetching candidate rows. Since rows are already
                       pulled into memory for this script's own filtering,
                       supporting regex (via the stdlib `re` module) costs
                       negligible extra overhead over plain substring search.

Example:
  python query_prompts.py --regex "sunset|beach" --since 2026-07-01T00:00:00Z
"""
import argparse
import json
import re
import subprocess
import sys
from datetime import datetime, timezone

DEFAULT_ACCOUNT = "azimagecreator"
DEFAULT_TABLE = "imageprompts"


def parse_utc(value: str) -> datetime:
    """Parse a UTC ISO-8601 timestamp, e.g. 2026-07-01T00:00:00Z."""
    v = value.strip()
    if v.endswith("Z"):
        v = v[:-1] + "+00:00"
    dt = datetime.fromisoformat(v)
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc)


def odata_datetime(dt: datetime) -> str:
    return "datetime'" + dt.strftime("%Y-%m-%dT%H:%M:%SZ") + "'"


def odata_string(value: str) -> str:
    # Escape single quotes per OData string literal rules.
    return "'" + value.replace("'", "''") + "'"


def build_filter(args) -> "str | None":
    clauses = []
    if args.since:
        clauses.append(f"CreatedAt ge {odata_datetime(parse_utc(args.since))}")
    if args.until:
        clauses.append(f"CreatedAt le {odata_datetime(parse_utc(args.until))}")
    if args.model:
        clauses.append(f"Model eq {odata_string(args.model)}")
    if args.provider:
        clauses.append(f"Provider eq {odata_string(args.provider)}")
    if args.operation:
        clauses.append(f"Operation eq {odata_string(args.operation)}")
    return " and ".join(clauses) if clauses else None


def fetch_entities(account: str, table: str, odata_filter: "str | None") -> list:
    cmd = [
        "az", "storage", "entity", "query",
        "--account-name", account,
        "--table-name", table,
        "--auth-mode", "login",
        "-o", "json",
    ]
    if odata_filter:
        cmd += ["--filter", odata_filter]

    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        sys.stderr.write(result.stderr)
        if "AuthorizationPermissionMismatch" in result.stderr or "403" in result.stderr:
            sys.stderr.write(
                "\nHint: your Azure AD account likely lacks a data-plane role "
                "(e.g. 'Storage Table Data Reader') on this storage account. "
                "Ask an owner to grant it, then retry.\n"
            )
        sys.exit(result.returncode)

    data = json.loads(result.stdout or "[]")
    return data.get("items", []) if isinstance(data, dict) else data


def apply_text_filter(entities, text, regex, case_sensitive):
    if not text and not regex:
        return entities
    flags = 0 if case_sensitive else re.IGNORECASE
    if regex:
        pattern = re.compile(regex, flags)
        return [e for e in entities if pattern.search(e.get("Prompt") or "")]
    needle = text if case_sensitive else text.lower()
    return [
        e for e in entities
        if needle in ((e.get("Prompt") or "") if case_sensitive else (e.get("Prompt") or "").lower())
    ]


def main():
    parser = argparse.ArgumentParser(description="Query the imageprompts Azure Table")
    parser.add_argument("--account", default=DEFAULT_ACCOUNT, help="Storage account name")
    parser.add_argument("--table", default=DEFAULT_TABLE, help="Table name")
    parser.add_argument("--text", help="Case-insensitive substring match on Prompt")
    parser.add_argument("--regex", help="Regex match on Prompt (Python re syntax)")
    parser.add_argument("--case-sensitive", action="store_true", help="Make --text/--regex case-sensitive")
    parser.add_argument("--since", help="UTC ISO-8601 lower bound, e.g. 2026-07-01T00:00:00Z")
    parser.add_argument("--until", help="UTC ISO-8601 upper bound, e.g. 2026-08-01T00:00:00Z")
    parser.add_argument("--model", help="Exact match, e.g. gpt-image-2")
    parser.add_argument("--provider", choices=["OpenAIService", "FluxService"])
    parser.add_argument("--operation", choices=["generate", "edit"])
    parser.add_argument("--limit", type=int, default=50, help="Max rows to print (after filtering)")
    parser.add_argument("--sort", choices=["asc", "desc"], default="desc", help="Order by CreatedAt")
    parser.add_argument("--format", choices=["table", "json"], default="table")
    args = parser.parse_args()

    if args.text and args.regex:
        parser.error("--text and --regex are mutually exclusive")

    odata_filter = build_filter(args)
    entities = fetch_entities(args.account, args.table, odata_filter)
    entities = apply_text_filter(entities, args.text, args.regex, args.case_sensitive)
    entities.sort(key=lambda e: e.get("CreatedAt") or "", reverse=(args.sort == "desc"))
    total_matched = len(entities)
    entities = entities[: args.limit]

    if args.format == "json":
        print(json.dumps(entities, indent=2))
        return

    if not entities:
        print("No matching prompts found.")
        return

    header = f"{'CreatedAt':<26} {'Operation':<9} {'Model':<14} {'BlobName':<28} Prompt"
    print(header)
    print("-" * len(header))
    for e in entities:
        prompt = (e.get("Prompt") or "").replace("\n", " ")
        if len(prompt) > 80:
            prompt = prompt[:77] + "..."
        print(f"{(e.get('CreatedAt') or ''):<26} {(e.get('Operation') or ''):<9} "
              f"{(e.get('Model') or ''):<14} {(e.get('BlobName') or ''):<28} {prompt}")
    if total_matched > len(entities):
        print(f"\n... {total_matched - len(entities)} more (raise --limit to see them)")


if __name__ == "__main__":
    main()
