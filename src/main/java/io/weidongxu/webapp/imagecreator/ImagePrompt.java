package io.weidongxu.webapp.imagecreator;

import java.time.OffsetDateTime;

public record ImagePrompt(
        String blobName,
        String prompt,
        OffsetDateTime createdAt,
        String model,
        String provider,
        String outputFormat,
        String operation,
        String jobId,
        int referenceImageCount) {
}
