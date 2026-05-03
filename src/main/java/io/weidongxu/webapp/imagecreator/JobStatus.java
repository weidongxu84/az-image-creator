package io.weidongxu.webapp.imagecreator;

/**
 * Immutable snapshot of a generation job's state.
 * status: "pending" | "running" | "completed" | "failed"
 */
public record JobStatus(String status, String imageName, String error) {
}
