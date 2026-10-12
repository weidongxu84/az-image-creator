# az-image-creator

A web application for generating images using Azure OpenAI, with a browser-based UI to generate and browse images.

## Features

- **Generate** images from a text prompt, with optional reference image uploads and configurable output size
- **Chat** with a multimodal assistant for critique, improvement actions, and better prompt suggestions (optional image per turn)
- **Browse** previously generated images, view their prompts, download, or delete them
- **Saved prompts** on Generate: save prompt text under a unique name, load it, or delete it with confirmation
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

Names are trimmed, case-insensitive, and limited to 100 characters. Duplicate names
never overwrite existing prompts. Prompt text is limited to 100,000 characters.
Loading changes only the prompt, not generation settings or reference images.

## License

[MIT](LICENSE)
