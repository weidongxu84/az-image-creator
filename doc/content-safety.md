# Content Safety, Moderation & Filters

Two independent layers govern what content the image/chat models will produce.
Both must allow a request for it to succeed.

## Layer 1 — Model-side moderation (in code)

Set per request in application code.

| Model | Parameter | Location | Range / options |
|---|---|---|---|
| gpt-image-2 (Azure OpenAI) | `moderation` | `OpenAIService` | `low`, `auto` (default) |
| FLUX (BFL) | `safety_tolerance` | `FluxService` | `0`–`6`; **higher = more permissive**, default `2` |

Notes:
- `moderation` only affects the model's own moderation, not the Azure platform filter.
- These parameters loosen/tighten the model's built-in checks; they cannot override Layer 2.

## Layer 2 — Azure platform content filter (RAI policy)

Applied by Azure AI Foundry to every **deployment**, independent of code. This is
usually the binding constraint.

- Configured as **RAI policies** in **Azure AI Foundry** (`https://ai.azure.com`) — **not**
  the classic `portal.azure.com` Cognitive Services blade.
- UI path: project → **Guardrails + controls** / **Content filters** → create a custom
  filter → assign to a deployment (or set it under **Models + endpoints**).
- Every deployment always has a policy. The Microsoft-managed defaults
  (`Microsoft.Default`, `Microsoft.DefaultV2`) do **not** appear in the custom-filter
  list, so a deployment can look "unfiltered" while still using a default. Assigning a
  custom filter prompts to **replace** the existing (default) one — expected.

### Threshold direction (counter-intuitive)

Per category (Hate, Sexual, Violence, Self-harm), the severity **threshold** sets what
gets blocked:

| UI label | Threshold | Blocks |
|---|---|---|
| Highest blocking | **Low** | Low + Medium + High severity (strictest) |
| — | Medium | Medium + High |
| **Lowest blocking** | **High** | Only High severity (most permissive) |

- **Lowest blocking / High threshold = fewest blocks.** To loosen filtering, move toward
  "Lowest blocking", not "Low".
- "Lowest blocking" is the most permissive **standard** option. Going further (annotate-only
  / no blocking) requires the gated **Limited Access: Modified Content Filters** approval on
  the subscription; otherwise those options are greyed out.
- Custom filters apply immediately — no app redeploy needed.

## Keeping layers consistent

If the Azure filter is loosened, keep the code parameters equally permissive (and vice
versa) so behavior is predictable. Both layers should express the same intent.

## Useful CLI checks

List deployments and the RAI policy each uses:

```bash
az cognitiveservices account deployment list \
  --name <account> --resource-group <rg> -o json \
  | ConvertFrom-Json | Select-Object name, @{n='raiPolicy';e={$_.properties.raiPolicyName}}
```

Inspect a policy's thresholds:

```bash
az rest --method get --url "https://management.azure.com/subscriptions/<sub>/resourceGroups/<rg>/providers/Microsoft.CognitiveServices/accounts/<account>/raiPolicies/<policy>?api-version=2024-10-01"
```
