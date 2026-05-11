# az-image-creator

A web application for generating images using Azure OpenAI, with a browser-based UI to generate and browse images.

## Features

- **Generate** images from a text prompt, with optional reference image uploads and configurable output size
- **Chat** with a multimodal assistant for critique, improvement actions, and better prompt suggestions (optional image per turn)
- **Browse** previously generated images, view their prompts, download, or delete them
- Asynchronous generation — the UI polls for completion so long-running requests don't time out in the browser
- Images are stored in Azure Blob Storage

## Tech Stack

- Java / Spring Boot
- Azure Blob Storage
- Azure OpenAI (`gpt-image-2` for image generation/edit, `gpt-5.4` for chat)
- Static HTML + JavaScript (no frontend framework)
- Deployed on Azure App Service via GitHub Actions

## License

[MIT](LICENSE)
