package com.github.guoyyit.flashloanjava.tests;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end crash recovery test. Launches the packaged application in a forked
 * JVM against a file-backed H2 database, forces a hard JVM halt mid-flight, and
 * starts a second process on the same data directory to verify that committed
 * units survive while uncommitted units are fully rolled back.
 */
@DisabledOnOs(OS.WINDOWS)
class CrashRecoveryTests {

    private static final Path JAR = Path.of("target/flashloan-java-1.0-SNAPSHOT.jar");

    private static final String BODY = """
            {
              "requestId": "crash-loan",
              "poolId": 1,
              "amount": 20000,
              "operations": [
                {"type":"TRANSFER","toPoolId":2,"amount":20000,"currency":"USDC"},
                {"type":"TRANSFER","fromPoolId":2,"amount":20020,"currency":"USDC"},
                {"type":"TRANSFER","toPoolId":1,"amount":20020,"currency":"USDC"}
              ]
            }
            """;

    @Test
    void committedUnitSurvivesHardCrashAndRestart() throws Exception {
        Path dataDir = Files.createTempDirectory("flashloan-crash-committed-");
        int port = 19091;

        Process first = startApp(port, dataDir, true);
        try {
            waitForStartup(first, port);
            post(port, "/api/pools", "{\"currency\":\"USDC\",\"initialDeposit\":100000}");
            post(port, "/api/pools", "{\"currency\":\"USDC\",\"initialDeposit\":1000}");

            // crashAfterCommit halts the JVM immediately after the transaction
            // has successfully committed (after-commit synchronization callback).
            int status = post(port, "/api/loans",
                    BODY.replace("\"operations\":", "\"crashAfterCommit\":true,\"operations\":"));
            assertThat(status).isEqualTo(0); // connection dropped by the hard halt
            assertThat(waitForExit(first, Duration.ofSeconds(10))).isTrue();
        } finally {
            first.destroyForcibly();
        }

        Process second = startApp(port, dataDir, false);
        try {
            waitForStartup(second, port);
            String pools = get(port, "/api/pools");
            assertThat(pools).contains("\"balance\":100020");
            assertThat(pools).contains("\"balance\":980");

            String loan = get(port, "/api/loans/crash-loan");
            assertThat(loan).contains("\"status\":\"SUCCESS\"");
            assertThat(loan).contains("\"fee\":20");

            String recovery = get(port, "/api/config/recovery");
            assertThat(recovery).contains("\"consistent\":true");
            assertThat(recovery).contains("\"successLoanCount\":1");
        } finally {
            second.destroy();
            second.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    void uncommittedUnitIsRolledBackAfterHardCrash() throws Exception {
        Path dataDir = Files.createTempDirectory("flashloan-crash-open-tx-");
        int port = 19092;

        Process first = startApp(port, dataDir, true);
        try {
            waitForStartup(first, port);
            post(port, "/api/pools", "{\"currency\":\"USDC\",\"initialDeposit\":100000}");
            post(port, "/api/pools", "{\"currency\":\"USDC\",\"initialDeposit\":1000}");

            // crashBeforeCommit halts the JVM while the loan transaction is open.
            int status = post(port, "/api/loans",
                    BODY.replace("\"operations\":", "\"crashBeforeCommit\":true,\"operations\":"));
            assertThat(status).isEqualTo(0);
            assertThat(waitForExit(first, Duration.ofSeconds(10))).isTrue();
        } finally {
            first.destroyForcibly();
        }

        Process second = startApp(port, dataDir, false);
        try {
            waitForStartup(second, port);
            String pools = get(port, "/api/pools");
            assertThat(pools).contains("\"balance\":100000");
            assertThat(pools).contains("\"balance\":1000");
            assertThat(get(port, "/api/loans")).isEqualTo("[]");

            // The rolled-back requestId can be executed anew after restart.
            int replay = post(port, "/api/loans", BODY);
            assertThat(replay).isEqualTo(200);
            assertThat(get(port, "/api/pools")).contains("\"balance\":100020");
        } finally {
            second.destroy();
            second.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private Process startApp(int port, Path dataDir, boolean crashHookEnabled) throws IOException {
        Assumptions.assumeTrue(Files.isRegularFile(JAR),
                "packaged jar required for crash recovery test (run with mvn package)");
        List<String> command = new ArrayList<>(List.of(
                javaCommand(), "-Dflashloan.port=" + port,
                "-Dflashloan.data-dir=" + dataDir));
        if (crashHookEnabled) {
            command.add("-Dflashloan.crash-hook-enabled=true");
        }
        command.add("-jar");
        command.add(JAR.toString());
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        return builder.start();
    }

    private String javaCommand() {
        String home = System.getProperty("java.home");
        Path java = Path.of(home, "bin", "java");
        return Files.isExecutable(java) ? java.toString() : "java";
    }

    private void waitForStartup(Process process, int port) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/config"))
                .timeout(Duration.ofSeconds(2)).GET().build();
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpResponse<String> response = client.send(request,
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (IOException ignored) {
                // server not ready yet
            }
            if (!process.isAlive()) {
                String log = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                throw new IllegalStateException("application exited early:\n" + log);
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("application did not start in time");
    }

    private int post(int port, String path, String json) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
        } catch (IOException connectionDropped) {
            return 0;
        }
    }

    private String get(int port, String path) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(5)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    private boolean waitForExit(Process process, Duration timeout) throws InterruptedException {
        return process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                && !process.isAlive();
    }
}
