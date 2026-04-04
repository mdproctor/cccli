package dev.mproctor.cccli.pty;

import org.junit.jupiter.api.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class PtyProcessTest {

    private PtyProcess pty;

    @BeforeEach
    void setUp() {
        pty = new PtyProcess();
    }

    @AfterEach
    void tearDown() {
        pty.close();
    }

    @Test
    void openReturnsMasterFdAndSlavePath() {
        pty.open();
        assertTrue(pty.getMasterFd() >= 0, "master fd should be ≥ 0");
        String slavePath = pty.getSlavePath();
        assertNotNull(slavePath);
        assertTrue(slavePath.startsWith("/dev/"), "slave path should start with /dev/, got: " + slavePath);
    }

    @Test
    void spawnEchoSetsPid() {
        pty.open();
        pty.spawn(new String[]{"/bin/echo", "hello"});
        assertTrue(pty.getPid() > 0, "spawned process should have pid > 0");
        // echo exits immediately; waitpid in close() will reap it
    }

    @Test
    void catRoundtripEchosInput() throws Exception {
        pty.open();
        pty.spawn(new String[]{"/bin/cat"});

        CompletableFuture<String> received = new CompletableFuture<>();
        StringBuilder output = new StringBuilder();
        pty.startReader(text -> {
            output.append(text);
            if (output.toString().contains("hello")) {
                received.complete(output.toString());
            }
        });

        pty.write("hello\n");

        String result = received.get(5, TimeUnit.SECONDS);
        assertTrue(result.contains("hello"), "output should contain 'hello', got: " + result);
    }

    @Test
    void spawnEchoOutputReachesReader() throws Exception {
        pty.open();

        CompletableFuture<String> received = new CompletableFuture<>();
        StringBuilder output = new StringBuilder();
        pty.startReader(text -> {
            output.append(text);
            if (output.toString().contains("ping")) {
                received.complete(output.toString());
            }
        });

        pty.spawn(new String[]{"/bin/echo", "ping"});

        String result = received.get(2, TimeUnit.SECONDS);
        assertTrue(result.contains("ping"), "output should contain 'ping', got: " + result);
    }
}
