package io.weidongxu.webapp.imagecreator;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class JobStore {

    private final ConcurrentHashMap<String, JobStatus> jobs = new ConcurrentHashMap<>();

    public String createJob() {
        String jobId = UUID.randomUUID().toString();
        jobs.put(jobId, new JobStatus("pending", null, null));
        return jobId;
    }

    public void setRunning(String jobId) {
        jobs.put(jobId, new JobStatus("running", null, null));
    }

    public void setCompleted(String jobId, List<String> imageNames) {
        jobs.put(jobId, new JobStatus("completed", imageNames, null));
    }

    public void setFailed(String jobId, String error) {
        jobs.put(jobId, new JobStatus("failed", null, error));
    }

    public JobStatus getJob(String jobId) {
        return jobs.get(jobId);
    }

    public long activeJobCount() {
        return jobs.values().stream()
                .filter(job -> "pending".equals(job.status()) || "running".equals(job.status()))
                .count();
    }
}
