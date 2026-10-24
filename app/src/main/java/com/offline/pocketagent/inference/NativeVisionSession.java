package com.offline.pocketagent.inference;

import com.geniex.sdk.bean.LLMTokenCallback;
import com.geniex.sdk.bean.GenerationConfig;
import com.geniex.sdk.bean.LlmApplyChatTemplateOutput;
import com.geniex.sdk.bean.VlmCapabilities;
import com.geniex.sdk.bean.VlmChatMessage;
import com.geniex.sdk.bean.VlmCreateInput;
import com.geniex.sdk.jni.Vlm;
import java.io.Closeable;

/**
 * Adapter for the existing JNI API in the pinned GenieX 0.9.3 AAR. The SDK's
 * Kotlin wrapper does not expose the native capability query and can report a
 * loaded VLM even when its projector failed. Java can call the published JVM
 * methods directly, with no reflection or second copy of the language model.
 * LocalInference serializes use and releases the handle after generation stops.
 */
public final class NativeVisionSession implements Closeable {
    private final Backend bridge;
    private volatile long handle;
    private final boolean supportsVision;

    public NativeVisionSession(VlmCreateInput input) {
        this(input, new SdkBackend());
    }

    NativeVisionSession(VlmCreateInput input, Backend bridge) {
        this.bridge = bridge;
        handle = bridge.create(input);
        if (handle == 0L) {
            throw new IllegalStateException("The local multimodal model returned an invalid native handle.");
        }
        try {
            VlmCapabilities capabilities = bridge.getCapabilities(handle);
            if (capabilities == null) {
                throw new IllegalStateException("The local runtime did not report its image capabilities.");
            }
            supportsVision = capabilities.getSupportsVision();
        } catch (RuntimeException | Error error) {
            close();
            throw error;
        }
    }

    public boolean getSupportsVision() {
        return supportsVision;
    }

    public int reset() {
        return bridge.reset(requireHandle());
    }

    public LlmApplyChatTemplateOutput applyChatTemplate(VlmChatMessage[] messages) {
        return bridge.applyChatTemplate(requireHandle(), messages, null, false);
    }

    public void generate(String prompt, GenerationConfig config, LLMTokenCallback callback) {
        bridge.generate(requireHandle(), prompt, config, callback);
    }

    // Keep the short stop/destroy calls on the same monitor. Generation itself
    // stays unlocked so a concurrent cancellation can still reach stopStream.
    public synchronized void stopStream() {
        long current = handle;
        if (current != 0L) bridge.stopStream(current);
    }

    private long requireHandle() {
        long current = handle;
        if (current == 0L) throw new IllegalStateException("The local multimodal session is closed.");
        return current;
    }

    @Override public synchronized void close() {
        long current = handle;
        handle = 0L;
        if (current != 0L) bridge.destroy(current);
    }

    interface Backend {
        long create(VlmCreateInput input);
        VlmCapabilities getCapabilities(long handle);
        int reset(long handle);
        LlmApplyChatTemplateOutput applyChatTemplate(long handle, VlmChatMessage[] messages, String tools, boolean thinking);
        void generate(long handle, String prompt, GenerationConfig config, LLMTokenCallback callback);
        void stopStream(long handle);
        void destroy(long handle);
    }

    private static final class SdkBackend implements Backend {
        private final Vlm sdk = new Vlm();
        @Override public long create(VlmCreateInput input) { return sdk.create(input); }
        @Override public VlmCapabilities getCapabilities(long handle) { return sdk.getCapabilities(handle); }
        @Override public int reset(long handle) { return sdk.reset(handle); }
        @Override public LlmApplyChatTemplateOutput applyChatTemplate(long handle, VlmChatMessage[] messages, String tools, boolean thinking) {
            return sdk.applyChatTemplate(handle, messages, tools, thinking);
        }
        @Override public void generate(long handle, String prompt, GenerationConfig config, LLMTokenCallback callback) {
            sdk.generate(handle, prompt, config, callback);
        }
        @Override public void stopStream(long handle) { sdk.stopStream(handle); }
        @Override public void destroy(long handle) { sdk.destroy(handle); }
    }
}
