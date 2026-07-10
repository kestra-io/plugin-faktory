package io.kestra.plugin.faktory;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Enqueue a job to a Faktory server",
    description = """
        Submits (pushes) a background job to a [Faktory](https://contribsys.com/faktory/) server over the \
        Faktory Work Protocol (FWP), a line-based TCP protocol - not HTTP. Faktory is most commonly paired \
        with Sidekiq / Ruby on Rails workers, so this task lets Kestra hand work off to existing workers \
        without touching them."""
)
@Plugin(
    examples = {
        @Example(
            title = "Enqueue a job for a Faktory/Sidekiq worker.",
            full = true,
            code = """
                id: faktory_push
                namespace: company.team

                inputs:
                  - id: email
                    type: STRING

                tasks:
                  - id: enqueue_welcome_email
                    type: io.kestra.plugin.faktory.Push
                    host: "{{ secret('FAKTORY_HOST') }}"
                    password: "{{ secret('FAKTORY_PASSWORD') }}"
                    jobType: SendWelcomeEmail
                    queue: mailers
                    args:
                      - "{{ inputs.email }}"
                """
        ),
        @Example(
            title = "Schedule a job for later and log the returned job id.",
            full = true,
            code = """
                id: faktory_scheduled_push
                namespace: company.team

                tasks:
                  - id: schedule_report
                    type: io.kestra.plugin.faktory.Push
                    host: "{{ secret('FAKTORY_HOST') }}"
                    password: "{{ secret('FAKTORY_PASSWORD') }}"
                    jobType: GenerateNightlyReport
                    args:
                      - "2026-07-10"
                    at: "{{ now() | dateAdd(1, 'DAYS') }}"
                    retries: 5
                    custom:
                      source: kestra

                  - id: log_jid
                    type: io.kestra.plugin.core.log.Log
                    message: "Enqueued Faktory job {{ outputs.schedule_report.jid }}"
                """
        )
    }
)
public class Push extends AbstractFaktoryConnection implements RunnableTask<Push.Output> {

    private static final String DEFAULT_QUEUE = "default";
    private static final int DEFAULT_RETRIES = 25;
    private static final Duration DEFAULT_RESERVE_FOR = Duration.ofMinutes(30);
    private static final Duration MIN_RESERVE_FOR = Duration.ofSeconds(60);

    @Schema(
        title = "Job type",
        description = """
            The Faktory `jobtype` identifying which worker handles this job."""
    )
    @PluginProperty(group = "main")
    @NotNull
    private Property<String> jobType;

    @Schema(
        title = "Job arguments",
        description = """
            JSON-native arguments passed to the worker (strings, numbers, booleans, objects, arrays). Defaults to an empty list."""
    )
    @PluginProperty(group = "main")
    private Property<List<Object>> args;

    @Schema(
        title = "Queue",
        description = """
            The Faktory queue the job is pushed to. Defaults to `default`."""
    )
    @PluginProperty(group = "processing")
    @Builder.Default
    private Property<String> queue = Property.ofValue(DEFAULT_QUEUE);

    @Schema(
        title = "Schedule for later",
        description = """
            If set, the job is not eligible for pickup by a worker until this instant; Faktory holds the job until then."""
    )
    @PluginProperty(group = "processing")
    private Property<Instant> at;

    @Schema(
        title = "Retry count",
        description = """
            Number of times Faktory retries this job before moving it to the dead queue, between -1 and 1000. \
            A negative value discards the job immediately on failure instead of retrying it or moving it to the dead queue. \
            Defaults to `25`."""
    )
    @PluginProperty(group = "reliability")
    @Builder.Default
    private Property<@Min(-1) @Max(1000) Integer> retries = Property.ofValue(DEFAULT_RETRIES);

    @Schema(
        title = "Reservation timeout",
        description = """
            How long a worker may hold this job before Faktory considers it timed out and reschedules it. \
            Must be at least `PT60S` (60 seconds). Defaults to `PT30M` (30 minutes)."""
    )
    @PluginProperty(group = "reliability")
    @Builder.Default
    private Property<Duration> reserveFor = Property.ofValue(DEFAULT_RESERVE_FOR);

    @Schema(
        title = "Job id",
        description = """
            A custom identifier for the enqueued job (Faktory `jid`), must be unique. \
            If not set, a random UUID is generated and returned as the `jid` output."""
    )
    @PluginProperty(group = "advanced")
    private Property<String> jobId;

    @Schema(
        title = "Custom metadata",
        description = """
            Arbitrary metadata attached to the job payload's `custom` field, made available to the worker at fetch time."""
    )
    @PluginProperty(group = "advanced")
    private Property<Map<String, Object>> custom;

    @Override
    public Push.Output run(RunContext runContext) throws Exception {
        var logger = runContext.logger();

        var rJobType = runContext.render(this.jobType).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("`jobType` is required to submit a Faktory job"));
        var rArgs = runContext.render(this.args).asList(Object.class);
        var rQueue = runContext.render(this.queue).as(String.class).orElse(DEFAULT_QUEUE);
        var rRetries = runContext.render(this.retries).as(Integer.class).orElse(DEFAULT_RETRIES);
        var rReserveFor = runContext.render(this.reserveFor).as(Duration.class).orElse(DEFAULT_RESERVE_FOR);
        var rAt = runContext.render(this.at).as(Instant.class).orElse(null);
        var rJobId = runContext.render(this.jobId).as(String.class).orElse(null);
        var rCustom = runContext.render(this.custom).asMap(String.class, Object.class);

        if (rReserveFor.compareTo(MIN_RESERVE_FOR) < 0) {
            throw new IllegalArgumentException(
                "`reserveFor` must be at least PT60S (60 seconds), got " + rReserveFor + "; increase it or remove the property to use the default of PT30M"
            );
        }

        var jid = (rJobId == null || rJobId.isBlank()) ? UUID.randomUUID().toString() : rJobId;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jid", jid);
        payload.put("jobtype", rJobType);
        payload.put("args", rArgs);
        payload.put("queue", rQueue);
        payload.put("retry", rRetries);
        payload.put("reserve_for", rReserveFor.getSeconds());
        if (rAt != null) {
            payload.put("at", rAt.toString());
        }
        if (!rCustom.isEmpty()) {
            payload.put("custom", rCustom);
        }

        var json = JacksonMapper.ofJson().writeValueAsString(payload);

        logger.debug("Submitting Faktory job of type '{}' to queue '{}' with jid '{}'", rJobType, rQueue, jid);

        try (var connection = connect(runContext)) {
            assertOk(connection.command("PUSH " + json), "PUSH");
        }

        logger.info("Enqueued Faktory job '{}' (jid={}) on queue '{}'", rJobType, jid, rQueue);

        return Output.builder().jid(jid).build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Job id",
            description = """
                The Faktory job id (`jid`) of the enqueued job - either the provided `jobId` or an auto-generated UUID."""
        )
        private final String jid;
    }
}
