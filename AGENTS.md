# Agent Instructions

This document describes the workflow agents must follow when making code changes to this project.

## Change Workflow

### 1. Plan

Before writing any code, clearly understand the goal. For non-trivial changes:
- Read relevant source files to understand the current implementation.
- Identify all files that need to change.
- Check for any side effects (e.g., API contract changes that affect the UI, security config changes, new env vars needed).

### 2. Code

Make precise, targeted changes:
- Follow the existing code style and patterns in the project.
- Do not modify unrelated code.
- If adding new environment variables, add them to `application.properties` with a sensible default and document them.
- If changing the REST API, update both the backend controller and the frontend `index.html` accordingly.

### 3. Test

Run tests locally before pushing:

```bash
mvn test
```

All tests must pass. If a test fails, fix the root cause — do not skip or suppress tests.

### 4. Push

Before **every push**, query the deployed app's authenticated
`GET /api/jobs/active` endpoint. Push only when it returns:

```json
{"activeJobs":0}
```

If the endpoint is unavailable, returns a nonzero count, or cannot be checked,
do not push. Wait and retry, or ask the user to confirm when it is safe.
App Service jobs are stored in memory, so a deployment restart interrupts
active jobs and loses their status.

Commit and push to the `main` branch:

```bash
git add -A
git commit -m "<concise description of change>"
git push origin main
```

The GitHub Actions workflow (`.github/workflows/deploy.yml`) triggers on push to `main`, but **only** when files matching its `paths` allowlist change (currently `src/**`, `pom.xml`, and the workflow file). Docs-only and other non-app pushes are skipped.

When adding any new **app-related** file that must be built or deployed (e.g. a new build input, `Dockerfile`, or config outside `src/`), add its path to the `paths` allowlist in `deploy.yml` — otherwise pushes touching only that file will not deploy. Use `workflow_dispatch` for a manual deploy.

### Documentation

When writing docs or markdown (including this file, `doc/`, and commit messages), always be concise. Prefer short sentences, tables, and bullet points over prose. Omit filler and repetition.

### 5. Wait for Deploy

Monitor the GitHub Actions run at:
**https://github.com/weidongxu84/az-image-creator/actions**

Wait for the workflow to complete successfully before proceeding. Do not assume the deploy succeeded without confirming the Actions run status.

### 6. Verify App Health

App Service commonly needs 2–5 minutes after the workflow succeeds to restart
and warm up. Wait before checking health, then retry for several minutes:

```bash
curl -s https://az-image-creator.azurewebsites.net/actuator/health
```

Expected response: `{"status":"UP",...}`

The health endpoint is public (`permitAll()`) — no credentials needed. Initial
401/500 responses can occur during restart and dependency warm-up; do not treat
them as final until retries have continued for at least 5 minutes.

If the health check keeps failing, inspect the App Service logs:

```bash
az webapp log tail --name az-image-creator --resource-group appservice
```

## Project Structure

```
src/main/java/        - Spring Boot application code
src/main/resources/
  static/index.html   - Single-page UI
  application.properties
src/test/             - Unit tests
pom.xml               - Maven build
.github/workflows/    - CI/CD pipeline
```

## Azure Resources

- **App Service**: `az-image-creator` in resource group `appservice`
- **Storage Account**: `azimagecreator` (blob container: `images`)
- **OpenAI**: endpoint configured via app setting `AZURE_OPENAI_ENDPOINT`
- **Identity**: System-assigned managed identity (no credentials in code)

## Important Constraints

- The generated image is the primary asset. Metadata failures must not block image creation, listing, download, UI display, or deletion.
- The UI must display valid images even when prompt metadata is missing or unavailable.
- Do **not** hardcode credentials, API keys, or secrets in source code.
- Do **not** change the managed identity RBAC assignments without documenting the change.
- All communication with Azure services (Blob Storage, OpenAI) must go through managed identity in production.
- The blob container is private — do not change its access level.
- Image prompts use the `imageprompts` table in `azimagecreator`. The App Service identity has `Storage Table Data Contributor` on that account.
