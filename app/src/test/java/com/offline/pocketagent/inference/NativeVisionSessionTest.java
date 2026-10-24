package com.offline.pocketagent.inference;

import com.geniex.sdk.bean.GenerationConfig;
import com.geniex.sdk.bean.LLMTokenCallback;
import com.geniex.sdk.bean.LlmApplyChatTemplateOutput;
import com.geniex.sdk.bean.VlmCapabilities;
import com.geniex.sdk.bean.VlmChatMessage;
import com.geniex.sdk.bean.VlmCreateInput;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.Test;
import static org.junit.Assert.*;

public class NativeVisionSessionTest {
    @Test public void missingVisionCapabilityKeepsTextHandleWithoutClaimingVision() {
        FakeBackend backend = new FakeBackend();
        backend.supportsVision = false;
        NativeVisionSession session = new NativeVisionSession(null, backend);
        assertFalse(session.getSupportsVision());
        assertEquals(0, session.reset());
        assertEquals(1, backend.resets);
        assertEquals(0, backend.destroyed);
        session.close();
        assertEquals(1, backend.destroyed);
    }

    @Test public void capabilityQueryFailureReleasesCreatedHandle() {
        FakeBackend backend = new FakeBackend();
        backend.capabilityError = new IllegalStateException("Capability query failed");
        try {
            new NativeVisionSession(null, backend);
            fail("Expected capability failure");
        } catch (IllegalStateException expected) {
            assertEquals("Capability query failed", expected.getMessage());
        }
        assertEquals(1, backend.destroyed);
    }

    @Test public void invalidCreateHandleIsNeverAdvertisedAsReady() {
        FakeBackend backend = new FakeBackend();
        backend.handle = 0;
        try {
            new NativeVisionSession(null, backend);
            fail("Expected invalid handle failure");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("invalid native handle"));
        }
        assertEquals(0, backend.capabilityQueries);
        assertEquals(0, backend.destroyed);
    }

    @Test public void closeReleasesNativeHandleExactlyOnceAndBlocksLaterUse() {
        FakeBackend backend = new FakeBackend();
        NativeVisionSession session = new NativeVisionSession(null, backend);
        assertTrue(session.getSupportsVision());
        session.close();
        session.close();
        session.stopStream();
        assertEquals(1, backend.destroyed);
        assertEquals(0, backend.stops);
        try {
            session.reset();
            fail("Expected closed-session failure");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("closed"));
        }
    }

    @Test public void concurrentClosesWaitForInFlightStopAndDestroyOnlyOnce() throws Exception {
        FakeBackend backend = new FakeBackend();
        backend.stopEntered = new CountDownLatch(1);
        backend.releaseStop = new CountDownLatch(1);
        NativeVisionSession session = new NativeVisionSession(null, backend);
        ExecutorService workers = Executors.newFixedThreadPool(3);
        try {
            Future<?> stopping = workers.submit(session::stopStream);
            assertTrue("stopStream did not enter the backend", backend.stopEntered.await(2, TimeUnit.SECONDS));
            CountDownLatch closeAttempts = new CountDownLatch(2);
            Runnable close = () -> {
                closeAttempts.countDown();
                session.close();
            };
            Future<?> firstClose = workers.submit(close);
            Future<?> secondClose = workers.submit(close);
            assertTrue("Concurrent close calls did not start", closeAttempts.await(2, TimeUnit.SECONDS));
            try {
                firstClose.get(150, TimeUnit.MILLISECONDS);
                fail("Destroy must wait until the in-flight native stop returns");
            } catch (TimeoutException expected) {
                // The stop call still owns the session's lifecycle monitor.
            }
            assertEquals("The native handle was destroyed while stop was in flight", 0, backend.destroyed);
            backend.releaseStop.countDown();
            stopping.get(2, TimeUnit.SECONDS);
            firstClose.get(2, TimeUnit.SECONDS);
            secondClose.get(2, TimeUnit.SECONDS);
            assertEquals(1, backend.destroyed);
            session.stopStream();
            assertEquals("A closed handle must never reach native stop", 1, backend.stops);
        } finally {
            backend.releaseStop.countDown();
            workers.shutdownNow();
        }
    }

    private static final class FakeBackend implements NativeVisionSession.Backend {
        long handle = 7;
        boolean supportsVision = true;
        RuntimeException capabilityError;
        volatile int capabilityQueries, destroyed, resets, stops;
        CountDownLatch stopEntered, releaseStop;
        @Override public long create(VlmCreateInput input) { return handle; }
        @Override public VlmCapabilities getCapabilities(long value) {
            capabilityQueries++;
            if (capabilityError != null) throw capabilityError;
            return new VlmCapabilities(supportsVision, false);
        }
        @Override public int reset(long value) { resets++; return 0; }
        @Override public LlmApplyChatTemplateOutput applyChatTemplate(long value, VlmChatMessage[] messages, String tools, boolean thinking) {
            return new LlmApplyChatTemplateOutput("prompt");
        }
        @Override public void generate(long value, String prompt, GenerationConfig config, LLMTokenCallback callback) { }
        @Override public void stopStream(long value) {
            stops++;
            if (stopEntered != null) {
                stopEntered.countDown();
                try {
                    if (!releaseStop.await(3, TimeUnit.SECONDS)) throw new AssertionError("Test did not release native stop");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Test stop was interrupted", interrupted);
                }
            }
        }
        @Override public synchronized void destroy(long value) { destroyed++; }
    }
}
