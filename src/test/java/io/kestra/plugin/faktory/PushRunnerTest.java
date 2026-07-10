package io.kestra.plugin.faktory;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.junit.annotations.LoadFlows;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.flows.State;
import io.kestra.core.runners.FlowInputOutput;
import io.kestra.core.runners.TestRunnerUtils;
import io.kestra.core.tenant.TenantService;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Executes the {@code sanity-*.yml} flows end-to-end through the Kestra runner (YAML parsing,
 * {@code Property<T>} binding, and the {@link Push} task itself) against a live, password-protected
 * Faktory server, complementing the programmatic unit tests in {@link PushTest}.
 */
@KestraTest(startRunner = true)
@Testcontainers
@org.junit.jupiter.api.parallel.Execution(ExecutionMode.SAME_THREAD)
class PushRunnerTest {

    private static final String PASSWORD = "s3cr3t";

    @Container
    static final GenericContainer<?> faktory = new GenericContainer<>(DockerImageName.parse("contribsys/faktory:1.9.4"))
        .withEnv("FAKTORY_PASSWORD", PASSWORD)
        .withExposedPorts(7419, 7420)
        .waitingFor(Wait.forListeningPort());

    @Inject
    private TestRunnerUtils testRunnerUtils;

    @Inject
    private FlowInputOutput flowIO;

    private Map<String, Object> connectionInputs() {
        return Map.of(
            "host", faktory.getHost(),
            "port", faktory.getMappedPort(7419),
            "password", PASSWORD
        );
    }

    @Test
    @LoadFlows({"flows/sanity-push.yml"})
    void push_enqueuesJobThroughFlow() throws Exception {
        var inputs = connectionInputs();

        Execution execution = testRunnerUtils.runOne(
            TenantService.MAIN_TENANT,
            "io.kestra.tests",
            "sanity_push",
            null,
            (flow, exec) -> flowIO.readExecutionInputs(flow, exec, inputs)
        );

        assertThat(execution.getState().getCurrent(), is(State.Type.SUCCESS));

        var jid = (String) execution.findTaskRunsByTaskId("enqueue_welcome_email").getFirst().getOutputs().get("jid");
        assertThat(jid, is(not(emptyString())));
    }

    @Test
    @LoadFlows({"flows/sanity-scheduled-push.yml"})
    void scheduledPush_enqueuesJobForLaterThroughFlow() throws Exception {
        var inputs = connectionInputs();

        Execution execution = testRunnerUtils.runOne(
            TenantService.MAIN_TENANT,
            "io.kestra.tests",
            "sanity_scheduled_push",
            null,
            (flow, exec) -> flowIO.readExecutionInputs(flow, exec, inputs)
        );

        assertThat(execution.getState().getCurrent(), is(State.Type.SUCCESS));

        var jid = (String) execution.findTaskRunsByTaskId("schedule_report").getFirst().getOutputs().get("jid");
        assertThat(jid, is(not(emptyString())));
    }
}
