# az-image-creator

A web application for generating images using Azure OpenAI, with a browser-based UI to generate and browse images.

## Features

- **Generate** images from a text prompt, with optional reference image uploads and configurable output size
- **Chat** with a multimodal assistant for critique, improvement actions, and better prompt suggestions (optional image per turn)
- **Browse** previously generated images, view their prompts, download, or delete them
- **Saved prompts** tab: add, edit, rename, delete, or use a prompt in Generate; quick Save/Load remains on Generate
- Asynchronous generation — the UI polls for completion so long-running requests don't time out in the browser
- Images are stored in Azure Blob Storage; prompt metadata is stored in Azure Table Storage

## Data Priority

The generated image is the primary asset. Prompt metadata is best-effort and must not block image creation, listing, download, display, or deletion. The API and UI must continue to expose a valid image when its prompt record is missing or Table Storage is unavailable. Legacy images fall back to prompt data in blob metadata.

## Tech Stack

- Java / Spring Boot
- Azure Blob Storage
- Azure Table Storage
- Azure OpenAI (`gpt-image-2` for image generation/edit, `gpt-5.6-sol` for chat)
- Static HTML + JavaScript (no frontend framework)
- Deployed on Azure App Service via GitHub Actions

## Alternate Image Endpoint

Set `AZURE_OPENAI_USE_ALTERNATE_IMAGE_ENDPOINT=true` to route image generation and editing
through `AZURE_OPENAI_ALT_IMAGE_ENDPOINT` using `AZURE_OPENAI_ALT_IMAGE_API_KEY`.
`AZURE_OPENAI_ALT_IMAGE_DEPLOYMENT` defaults to `gpt-image-2`, and
`AZURE_OPENAI_ALT_FLARE_DEPLOYMENT` defaults to `gpt-image-2.5-flare`.
`AZURE_OPENAI_ALT_SUNBURST_DEPLOYMENT` defaults to `gpt-image-2.5-sunburst`.
Chat remains on the primary endpoint.

## Model Error Logs

OpenAI failures log the SDK-retained error body and response headers, including
`Retry-After`, `Retry-After-Ms`, and request IDs. Authentication and cookie headers
are redacted. Error bodies may contain service-returned request details.
Logs cover final failures and authentication errors before API-key fallback,
not intermediate SDK retries or successful image responses.

OpenAI image generation and editing allow 5 retries (6 attempts per SDK request),
including alternate and API-key fallback image clients. Chat and validation retain
the SDK default of 2 retries. Server-provided retry delays are honored.

## Storage Authentication

Storage uses managed identity with `STORAGE_ACCOUNT_NAME` by default. Set
`STORAGE_ACCOUNT_KEY` to use shared-key authentication for Blob Storage,
Table Storage, and generated image SAS links instead.

## Saved Prompts

Prompt text is stored as JSON in the private `saved-prompts` container in
`STORAGE_ACCOUNT_NAME`. Override its name with `STORAGE_SAVED_PROMPT_CONTAINER_NAME`;
it must differ from the image container. The app creates it on first save or load.
The managed identity needs Storage Blob Data Contributor at account scope (or
provision the container and grant that role at container scope).

Names are case-insensitively unique and limited to 100 characters. Only leading
and trailing whitespace is trimmed; internal spaces are preserved. New names
reject control characters and `/ \ : * ? " < > |`. Existing names remain accessible.
Prompt text is limited to 100,000 characters. Add starts a draft; Save persists it.
Rename preserves unsaved editor text without saving it. Leaving the tab or changing
selection warns before discarding edits. Use in Generate changes only prompt text.

The authenticated `/api/saved-prompts` API supports GET (list), POST (create),
PUT (edit), DELETE (`?name=...`), and POST `/rename` (`name`, `newName`).
Responses include each prompt's Blob Storage `etag`; editing, renaming, and
deleting require that value in `If-Match`. Create, edit, and rename also return an
HTTP `ETag` header. Stale ETags return 412; duplicate names return 409.
Rename uses conditional create/delete, not an atomic blob move. An uncertain
storage failure preserves the destination and asks you to refresh before retrying.

## License

[MIT](LICENSE)
