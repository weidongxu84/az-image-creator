# az-image-creator

A web application for generating images using Azure OpenAI, with a browser-based UI to generate and browse images.

## Features

- **Generate** images from a text prompt, with optional reference image uploads and configurable output size
- **Chat** with a multimodal assistant for critique, improvement actions, and better prompt suggestions (optional image per turn)
- **Browse** previously generated images, view their prompts, download, or delete them
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
`AZURE_OPENAI_ALT_IMAGE_DEPLOYMENT` defaults to `gpt-image-2`. Chat remains on the primary endpoint.

## License

[MIT](LICENSE)
