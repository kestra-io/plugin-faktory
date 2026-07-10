package io.kestra.plugin.faktory;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
class PushTest {
    private static final Pattern UUID_PATTERN = Pattern.compile(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
    );

    @Container
    static final GenericContainer<?> faktory = new GenericContainer<>(DockerImageName.parse("contribsys/faktory:latest"))
        .withExposedPorts(7419, 7420)
        .waitingFor(Wait.forListeningPort());

    @Container
    static final GenericContainer<?> faktoryWithAuth = new GenericContainer<>(DockerImageName.parse("contribsys/faktory:latest"))
        .withEnv("FAKTORY_PASSWORD", "s3cr3t")
        .withExposedPorts(7419, 7420)
        .waitingFor(Wait.forListeningPort());

    @Inject
    private RunContextFactory runContextFactory;

    private Push.PushBuilder<?, ?> task() {
        return Push.builder()
            .id("push_" + UUID.randomUUID())
            .type(Push.class.getName())
            .host(Property.ofValue(faktory.getHost()))
            .port(Property.ofValue(faktory.getMappedPort(7419)));
    }

    private RunContext runContext(Push task) {
        return runContextFactory.of(task, Map.of());
    }

    @Test
    void run_enqueuesJobAndReturnsGeneratedJid() throws Exception {
        var task = task()
            .jobType(Property.ofValue("SendWelcomeEmail"))
            .args(Property.ofValue(List.of("user@example.com")))
            .queue(Property.ofValue("mailers"))
            .build();

        var output = task.run(runContext(task));

        assertThat(output.getJid(), is(notNullValue()));
        assertThat(UUID_PATTERN.matcher(output.getJid()).matches(), is(true));
    }

    @Test
    void run_withCustomJobId_returnsIt() throws Exception {
        var jobId = "custom-job-" + UUID.randomUUID();
        var task = task()
            .jobType(Property.ofValue("GenerateReport"))
            .jobId(Property.ofValue(jobId))
            .build();

        var output = task.run(runContext(task));

        assertThat(output.getJid(), is(jobId));
    }

    @Test
    void run_withScheduleRetriesAndCustomMetadata_succeeds() throws Exception {
        var task = task()
            .jobType(Property.ofValue("GenerateNightlyReport"))
            .args(Property.ofValue(List.of("2026-07-10")))
            .at(Property.ofValue(Instant.now().plusSeconds(3600)))
            .retries(Property.ofValue(5))
            .reserveFor(Property.ofValue(Duration.ofMinutes(5)))
            .custom(Property.ofValue(Map.of("source", "kestra")))
            .build();

        var output = task.run(runContext(task));

        assertThat(output.getJid(), is(notNullValue()));
    }

    @Test
    void run_missingJobType_throws() {
        var task = task().build();

        assertThrows(Exception.class, () -> task.run(runContext(task)));
    }

    @Test
    void run_reserveForBelowMinimum_throws() {
        var task = task()
            .jobType(Property.ofValue("SendWelcomeEmail"))
            .reserveFor(Property.ofValue(Duration.ofSeconds(10)))
            .build();

        var exception = assertThrows(IllegalArgumentException.class, () -> task.run(runContext(task)));
        assertThat(exception.getMessage(), containsString("PT60S"));
    }

    @Test
    void run_portOutOfRange_throws() {
        var task = task()
            .jobType(Property.ofValue("SendWelcomeEmail"))
            .port(Property.ofValue(99999))
            .build();

        assertThrows(Exception.class, () -> task.run(runContext(task)));
    }

    @Test
    void run_withPassword_authenticatesSuccessfully() throws Exception {
        var task = Push.builder()
            .id("push_" + UUID.randomUUID())
            .type(Push.class.getName())
            .host(Property.ofValue(faktoryWithAuth.getHost()))
            .port(Property.ofValue(faktoryWithAuth.getMappedPort(7419)))
            .password(Property.ofValue("s3cr3t"))
            .jobType(Property.ofValue("SendWelcomeEmail"))
            .build();

        var output = task.run(runContext(task));

        assertThat(output.getJid(), is(notNullValue()));
    }

    @Test
    void run_withWrongPassword_throws() {
        var task = Push.builder()
            .id("push_" + UUID.randomUUID())
            .type(Push.class.getName())
            .host(Property.ofValue(faktoryWithAuth.getHost()))
            .port(Property.ofValue(faktoryWithAuth.getMappedPort(7419)))
            .password(Property.ofValue("wrong-password"))
            .jobType(Property.ofValue("SendWelcomeEmail"))
            .build();

        assertThrows(Exception.class, () -> task.run(runContext(task)));
    }
}
