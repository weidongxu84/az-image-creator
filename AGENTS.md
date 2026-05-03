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

Commit and push to the `main` branch:

```bash
git add -A
git commit -m "<concise description of change>"
git push origin main
```

The GitHub Actions workflow (`.github/workflows/deploy.yml`) will trigger automatically on push to `main`.

### 5. Wait for Deploy

Monitor the GitHub Actions run at:
**https://github.com/weidongxu84/az-image-creator/actions**

Wait for the workflow to complete successfully before proceeding. Do not assume the deploy succeeded without confirming the Actions run status.

### 6. Verify App Health

After a successful deploy, confirm the app is running:

```bash
curl -s https://az-image-creator.azurewebsites.net/actuator/health
```

Expected response: `{"status":"UP",...}`

If health check fails, inspect the App Service logs:

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

- Do **not** hardcode credentials, API keys, or secrets in source code.
- Do **not** change the managed identity RBAC assignments without documenting the change.
- All communication with Azure services (Blob Storage, OpenAI) must go through managed identity in production.
- The blob container is private — do not change its access level.
