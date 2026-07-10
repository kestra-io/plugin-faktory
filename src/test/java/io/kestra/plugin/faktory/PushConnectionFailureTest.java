package io.kestra.plugin.faktory;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Exercises the FWP handshake failure paths against a local {@link ServerSocket} instead of a
 * Faktory container, since these are protocol-level edge cases that don't need a real server.
 */
@KestraTest
@Execution(ExecutionMode.SAME_THREAD)
class PushConnectionFailureTest {

    @Inject
    private RunContextFactory runContextFactory;

    private RunContext runContext(Push task) {
        return runContextFactory.of(task, Map.of());
    }

    private Push.PushBuilder<?, ?> task(int port) {
        return Push.builder()
            .id("push_" + UUID.randomUUID())
            .type(Push.class.getName())
            .host(Property.ofValue("localhost"))
            .port(Property.ofValue(port));
    }

    @Test
    void run_malformedGreetingLine_throwsClearError() throws Exception {
        try (var serverSocket = fakeServer(client -> writeLine(client, "+NOPE not-a-greeting"))) {
            var task = task(serverSocket.getLocalPort())
                .jobType(Property.ofValue("SendWelcomeEmail"))
                .build();

            var exception = assertThrows(Exception.class, () -> task.run(runContext(task)));
            assertThat(exception.getMessage(), containsString("expected 'HI'"));
        }
    }

    @Test
    void run_invalidJsonInGreetingPayload_throwsClearError() throws Exception {
        try (var serverSocket = fakeServer(client -> writeLine(client, "+HI {not-json}"))) {
            var task = task(serverSocket.getLocalPort())
                .jobType(Property.ofValue("SendWelcomeEmail"))
                .build();

            var exception = assertThrows(Exception.class, () -> task.run(runContext(task)));
            assertThat(exception.getMessage(), containsString("handshake"));
        }
    }

    @Test
    void run_tlsHandshakeFailure_throwsClearError() throws Exception {
        // a plain TCP server that closes right after accepting is enough to fail the client's TLS handshake fast
        try (var serverSocket = fakeServer(client -> { })) {
            var task = task(serverSocket.getLocalPort())
                .tls(Property.ofValue(true))
                .jobType(Property.ofValue("SendWelcomeEmail"))
                .build();

            var exception = assertThrows(Exception.class, () -> task.run(runContext(task)));
            assertThat(exception.getMessage(), containsString("Unable to connect"));
        }
    }

    private static ServerSocket fakeServer(Consumer<Socket> onAccept) throws IOException {
        var serverSocket = new ServerSocket(0);
        var thread = new Thread(() -> {
            try (var client = serverSocket.accept()) {
                onAccept.accept(client);
            } catch (IOException ignored) {
                // test server, connection errors are expected once the client-side assertion has run
            }
        });
        thread.setDaemon(true);
        thread.start();
        return serverSocket;
    }

    private static void writeLine(Socket socket, String line) {
        try {
            var writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            writer.print(line + "\r\n");
            writer.flush();
        } catch (IOException ignored) {
            // test server, nothing to do if the client has already given up
        }
    }
}
