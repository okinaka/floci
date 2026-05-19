package io.github.hectorvent.floci.services.codebuild.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BuildPhase {
    public BuildPhase() {}

    private String phaseType;
    private volatile String phaseStatus;
    private Double startTime;
    private volatile Double endTime;
    private volatile Long durationInSeconds;
    private volatile List<Map<String, String>> contexts;

    public BuildPhase snapshot() {
        BuildPhase copy = new BuildPhase();
        copy.phaseType = this.phaseType;
        copy.phaseStatus = this.phaseStatus;
        copy.startTime = this.startTime;
        copy.endTime = this.endTime;
        copy.durationInSeconds = this.durationInSeconds;
        copy.contexts = this.contexts;
        return copy;
    }

    public String getPhaseType() { return phaseType; }
    public void setPhaseType(String phaseType) { this.phaseType = phaseType; }

    public String getPhaseStatus() { return phaseStatus; }
    public void setPhaseStatus(String phaseStatus) { this.phaseStatus = phaseStatus; }

    public Double getStartTime() { return startTime; }
    public void setStartTime(Double startTime) { this.startTime = startTime; }

    public Double getEndTime() { return endTime; }
    public void setEndTime(Double endTime) { this.endTime = endTime; }

    public Long getDurationInSeconds() { return durationInSeconds; }
    public void setDurationInSeconds(Long durationInSeconds) { this.durationInSeconds = durationInSeconds; }

    public List<Map<String, String>> getContexts() { return contexts; }
    public void setContexts(List<Map<String, String>> contexts) { this.contexts = contexts; }
}
