package io.weidongxu.webapp.imagecreator;

import com.openai.core.JsonValue;
import com.openai.core.http.Headers;
import com.openai.errors.RateLimitException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class OpenAIErrorLoggingTests {

    @Test
    void logsRetryHeadersAndCompleteRetainedErrorBody(CapturedOutput output) {
        RateLimitException error = RateLimitException.builder()
                .headers(Headers.builder()
                        .put("Retry-After", "34")
                        .put("retry-after-ms", "34000")
                        .put("x-request-id", "request-123")
                        .put("x-ms-request-id", "azure-request-123")
                        .put("x-ratelimit-remaining-requests", "0")
                        .build())
                .error(JsonValue.from(Map.of(
                        "message", "Please retry after 34 seconds.",
                        "code", "RateLimitReached",
                        "details", List.of(Map.of("quota", "2 images per minute")))))
                .build();

        OpenAIErrorLogging.logResponse(LoggerFactory.getLogger(getClass()), "Job job-123", error);

        assertThat(output.getAll()).contains(
                "Job job-123: OpenAI error response HTTP 429",
                "Retry-After=[34]", "retry-after-ms=[34000]",
                "x-request-id=[request-123]", "x-ms-request-id=[azure-request-123]",
                "x-ratelimit-remaining-requests=[0]",
                "Please retry after 34 seconds.", "RateLimitReached", "2 images per minute");
    }

    @Test
    void redactsSensitiveHeadersCaseInsensitively(CapturedOutput output) {
        Headers.Builder headers = Headers.builder();
        List<String> names = List.of("AUTHORIZATION", "Proxy-Authorization", "API-Key",
                "X-Api-Key", "X-Amz-Security-Token", "Cookie", "Set-Cookie");
        for (int i = 0; i < names.size(); i++) {
            headers.put(names.get(i), "secret-value-" + i);
            headers.put(names.get(i), "second-secret-value-" + i);
        }
        RateLimitException error = RateLimitException.builder().headers(headers.build()).build();

        OpenAIErrorLogging.logResponse(LoggerFactory.getLogger(getClass()), "OpenAI API", error);

        for (String name : names) {
            assertThat(output.getAll()).contains(name + "=[[REDACTED]]");
        }
        assertThat(output.getAll()).doesNotContain("secret-value");
    }

    @Test
    void handlesMissingErrorBodyAndEmptyHeaders(CapturedOutput output) {
        RateLimitException error = RateLimitException.builder().headers(Headers.builder().build()).build();

        OpenAIErrorLogging.logResponse(LoggerFactory.getLogger(getClass()), "OpenAI API", error);

        assertThat(output.getAll()).contains("HTTP 429 headers={} body=");
    }

    @Test
    void controllerLogsErrorDetailsWithoutChangingPublicResponse(CapturedOutput output) {
        RateLimitException error = rateLimitError();

        var response = new ImageController().handleOpenAI(error);

        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(response.getBody()).containsExactly(
                Map.entry("error", "Rate limited by model endpoint. Please retry in a moment."));
        assertThat(output.getAll()).contains(
                "OpenAI API: OpenAI error response HTTP 429", "Retry-After=[15]", "quota-detail");
    }

    @Test
    void imageJobLogsErrorDetailsAndStillRecordsRateLimitFailure(CapturedOutput output) {
        OpenAIService openAI = mock(OpenAIService.class);
        when(openAI.generateImage("gpt-image-2", "prompt", "1024x1024", "png", 1))
                .thenThrow(rateLimitError());
        JobStore jobs = new JobStore();
        String jobId = jobs.createJob();
        ImageGenerationService service = new ImageGenerationService(
                openAI, mock(FluxService.class), mock(StorageService.class),
                mock(PromptStorageService.class), jobs, mock(AppConfig.class));

        service.generateImage(jobId, "gpt-image-2", "prompt", "1024x1024",
                null, null, null, "png", 1);

        assertThat(jobs.getJob(jobId).status()).isEqualTo("failed");
        assertThat(output.getAll()).contains(
                "Job " + jobId + ": OpenAI error response HTTP 429",
                "Retry-After=[15]", "quota-detail");
    }

    private RateLimitException rateLimitError() {
        return RateLimitException.builder()
                .headers(Headers.builder().put("Retry-After", "15").build())
                .error(JsonValue.from(Map.of(
                        "message", "Rate limit reached", "code", "RateLimitReached",
                        "details", "quota-detail")))
                .build();
    }
}
