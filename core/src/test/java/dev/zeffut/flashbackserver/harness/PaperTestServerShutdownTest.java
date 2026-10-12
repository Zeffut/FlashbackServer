package dev.zeffut.flashbackserver.harness;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Process doubles only: these unit tests never launch a JVM or a server. */
class PaperTestServerShutdownTest {
    @Test void gracefulExitSendsStopWithoutKilling() throws Exception {
        StubProcess process = new StubProcess(0);
        PaperTestServer.stopCleanly(process);
        assertEquals("stop\n", process.input.toString(StandardCharsets.UTF_8));
        assertFalse(process.forced);
        assertFalse(process.isAlive());
    }

    @Test void nonzeroExitFailsTheSmoke() {
        StubProcess process = new StubProcess(1);
        IOException error = assertThrows(IOException.class, () -> PaperTestServer.stopCleanly(process));
        assertTrue(error.getMessage().contains("code 1"));
        assertFalse(process.forced);
    }

    @Test void timeoutFailsEvenWhenForcedCleanupSucceeds() {
        StubProcess process = new StubProcess(0);
        process.timeout = true;
        IOException error = assertThrows(IOException.class, () -> PaperTestServer.stopCleanly(process));
        assertTrue(error.getMessage().contains("60s"));
        assertTrue(process.forced);
        assertFalse(process.isAlive());
        assertEquals(2, process.waits);
    }

    @Test void interruptionStillCleansUpAndPropagates() {
        StubProcess process = new StubProcess(0);
        process.interrupt = true;
        assertThrows(InterruptedException.class, () -> PaperTestServer.stopCleanly(process));
        assertTrue(process.forced);
        assertFalse(process.isAlive());
    }

    @Test void brokenConsoleInputIsNotSilentlyIgnored() {
        StubProcess process = new StubProcess(0);
        process.brokenInput = true;
        assertThrows(IOException.class, () -> PaperTestServer.stopCleanly(process));
        assertTrue(process.forced);
        assertFalse(process.isAlive());
    }

    private static final class StubProcess extends Process {
        final ByteArrayOutputStream input = new ByteArrayOutputStream();
        final int exitCode;
        boolean alive = true;
        boolean forced;
        boolean timeout;
        boolean interrupt;
        boolean brokenInput;
        int waits;

        StubProcess(int exitCode) { this.exitCode = exitCode; }

        @Override public OutputStream getOutputStream() {
            if (brokenInput) return new OutputStream() {
                @Override public void write(int value) throws IOException { throw new IOException("Broken pipe"); }
            };
            return input;
        }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { alive = false; return exitCode; }
        @Override public boolean waitFor(long timeoutValue, TimeUnit unit) throws InterruptedException {
            waits++;
            if (!forced && interrupt) throw new InterruptedException("interrupted");
            if (!forced && timeout) return false;
            alive = false;
            return true;
        }
        @Override public int exitValue() {
            if (alive) throw new IllegalThreadStateException("Still alive");
            return exitCode;
        }
        @Override public void destroy() { alive = false; }
        @Override public Process destroyForcibly() { forced = true; return this; }
        @Override public boolean isAlive() { return alive; }
    }
}
