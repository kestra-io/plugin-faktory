package io.kestra.plugin.faktory;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.*;
import lombok.experimental.SuperBuilder;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Owns the Faktory Work Protocol (FWP) socket lifecycle: connect (TCP or TLS), perform the
 * {@code HI}/{@code HELLO} handshake with optional password authentication, and expose a
 * {@link FaktoryConnection} for subclasses to issue commands over. Written once and shared by
 * every Faktory task.
 */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractFaktoryConnection extends Task {

    private static final int DEFAULT_PORT = 7419;
    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int READ_TIMEOUT_MILLIS = 10_000;

    @Schema(
        title = "Faktory server host",
        description = "Hostname or IP address of the Faktory server. Defaults to `localhost`."
    )
    @PluginProperty(group = "connection")
    @Builder.Default
    private Property<String> host = Property.ofValue("localhost");

    @Schema(
        title = "Faktory server port",
        description = "TCP port the Faktory server listens on, between 1 and 65535. Defaults to `7419`."
    )
    @PluginProperty(group = "connection")
    @Builder.Default
    private Property<@Min(1) @Max(65535) Integer> port = Property.ofValue(DEFAULT_PORT);

    @Schema(
        title = "Faktory password",
        description = "Password used to authenticate with a password-protected Faktory server. Leave empty to connect without credentials."
    )
    @PluginProperty(group = "connection", secret = true)
    private Property<String> password;

    @Schema(
        title = "Use TLS",
        description = "Whether to establish the connection over TLS (`tcp+tls://`). Defaults to `false`."
    )
    @PluginProperty(group = "connection")
    @Builder.Default
    private Property<Boolean> tls = Property.ofValue(false);

    protected FaktoryConnection connect(RunContext runContext) throws Exception {
        var logger = runContext.logger();

        var rHost = runContext.render(this.host).as(String.class).orElse("localhost");
        var rPort = runContext.render(this.port).as(Integer.class).orElse(DEFAULT_PORT);
        var rTls = runContext.render(this.tls).as(Boolean.class).orElse(false);
        var rPassword = runContext.render(this.password).as(String.class).orElse(null);

        logger.debug("Connecting to Faktory server at {}:{} (tls={})", rHost, rPort, rTls);

        Socket socket = rTls ? SSLSocketFactory.getDefault().createSocket() : new Socket();
        try {
            socket.connect(new InetSocketAddress(rHost, rPort), CONNECT_TIMEOUT_MILLIS);
            socket.setSoTimeout(READ_TIMEOUT_MILLIS);
            if (socket instanceof SSLSocket sslSocket) {
                sslSocket.startHandshake();
            }
        } catch (IOException e) {
            closeQuietly(socket);
            throw new IOException("Unable to connect to Faktory server at " + rHost + ":" + rPort + " (tls=" + rTls + "): " + e.getMessage(), e);
        }

        var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        var writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

        try {
            handshake(reader, writer, rPassword);
        } catch (Exception e) {
            closeQuietly(socket);
            throw new IOException("Faktory handshake with " + rHost + ":" + rPort + " (tls=" + rTls + ") failed: " + e.getMessage(), e);
        }

        return new FaktoryConnection(socket, reader, writer);
    }

    private void handshake(BufferedReader reader, BufferedWriter writer, String password) throws IOException {
        var greeting = reader.readLine();
        if (greeting == null || !greeting.startsWith("+HI")) {
            throw new IOException("unexpected greeting from the server, expected 'HI' but got: " + greeting);
        }

        // greeting is "+HI {...}", the JSON payload starts right after the 3-char "+HI" marker
        var greetingJson = greeting.length() > 3 ? greeting.substring(3).trim() : "";
        Map<String, Object> hi = greetingJson.isEmpty()
            ? Map.of()
            : JacksonMapper.ofJson().readValue(greetingJson, JacksonMapper.MAP_TYPE_REFERENCE);

        Map<String, Object> hello = new LinkedHashMap<>();
        hello.put("hostname", "kestra");
        hello.put("pid", ProcessHandle.current().pid());
        if (hi.get("v") != null) {
            hello.put("v", hi.get("v"));
        }

        var salt = hi.get("s");
        if (password != null && !password.isBlank()) {
            if (salt == null) {
                throw new IOException("a password was configured but the server did not request authentication (no salt received); either remove the password or check the server configuration");
            }
            var iterations = hi.get("i") instanceof Number number ? number.intValue() : 1;
            hello.put("pwdhash", hashPassword(password, salt.toString(), iterations));
        }

        var reply = sendAndReceive(writer, reader, "HELLO " + JacksonMapper.ofJson().writeValueAsString(hello));
        assertOk(reply, "HELLO");
    }

    // Faktory's iterated-SHA256 password scheme: hash(password + salt), then re-hash the raw
    // digest bytes (i - 1) more times, hex-encoding only the final result.
    static String hashPassword(String password, String salt, int iterations) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hash = digest.digest((password + salt).getBytes(StandardCharsets.UTF_8));
            for (var i = 1; i < iterations; i++) {
                hash = digest.digest(hash);
            }
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is not available on this JVM", e);
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // best-effort cleanup after a failed connection attempt, nothing actionable left to do
        }
    }

    protected static String sendAndReceive(BufferedWriter writer, BufferedReader reader, String command) throws IOException {
        writer.write(command);
        writer.write("\r\n");
        writer.flush();

        var reply = reader.readLine();
        if (reply == null) {
            throw new IOException("Faktory server closed the connection unexpectedly while waiting for a reply to '" + command.split(" ", 2)[0] + "'");
        }
        return reply;
    }

    protected static void assertOk(String reply, String command) throws IOException {
        if (!reply.startsWith("+OK")) {
            var message = reply.startsWith("-") ? reply.substring(1) : reply;
            throw new IOException("Faktory server rejected the " + command + " command: " + message);
        }
    }

    protected static final class FaktoryConnection implements Closeable {
        private final Socket socket;
        private final BufferedReader reader;
        private final BufferedWriter writer;

        private FaktoryConnection(Socket socket, BufferedReader reader, BufferedWriter writer) {
            this.socket = socket;
            this.reader = reader;
            this.writer = writer;
        }

        protected String command(String command) throws IOException {
            return sendAndReceive(writer, reader, command);
        }

        @Override
        public void close() {
            try {
                writer.write("END\r\n");
                writer.flush();
            } catch (IOException ignored) {
                // best-effort graceful shutdown, the job has already been submitted at this point
            }
            try {
                socket.close();
            } catch (IOException ignored) {
                // nothing actionable left to do once the socket is being discarded
            }
        }
    }
}
