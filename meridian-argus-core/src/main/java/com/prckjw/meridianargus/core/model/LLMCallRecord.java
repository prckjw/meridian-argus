package com.prckjw.meridianargus.core.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * A single, provider-agnostic record of one LLM call. This is the unit that
 * flows from an interceptor into the pricing engine and out to exporters.
 * <p>
 * Instances are immutable; build with {@link Builder}.
 */
public final class LLMCallRecord {

    private final ProviderModel providerModel;
    private final TokenUsage tokenUsage;
    private final Instant startedAt;
    private final Duration latency;
    private final boolean success;
    private final String errorType;   // null when success == true
    private final String callerId;    // optional: service/app name, for multi-tenant attribution

    private LLMCallRecord(Builder b) {
        this.providerModel = Objects.requireNonNull(b.providerModel, "providerModel is required");
        this.tokenUsage = b.tokenUsage == null ? TokenUsage.empty() : b.tokenUsage;
        this.startedAt = Objects.requireNonNull(b.startedAt, "startedAt is required");
        this.latency = Objects.requireNonNull(b.latency, "latency is required");
        this.success = b.success;
        this.errorType = b.errorType;
        this.callerId = b.callerId;
    }

    public static Builder builder() {
        return new Builder();
    }

    public ProviderModel getProviderModel() {
        return providerModel;
    }

    public TokenUsage getTokenUsage() {
        return tokenUsage;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Duration getLatency() {
        return latency;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getErrorType() {
        return errorType;
    }

    public String getCallerId() {
        return callerId;
    }

    public static final class Builder {
        private ProviderModel providerModel;
        private TokenUsage tokenUsage;
        private Instant startedAt = Instant.now();
        private Duration latency = Duration.ZERO;
        private boolean success = true;
        private String errorType;
        private String callerId;

        public Builder providerModel(ProviderModel providerModel) {
            this.providerModel = providerModel;
            return this;
        }

        public Builder tokenUsage(TokenUsage tokenUsage) {
            this.tokenUsage = tokenUsage;
            return this;
        }

        public Builder startedAt(Instant startedAt) {
            this.startedAt = startedAt;
            return this;
        }

        public Builder latency(Duration latency) {
            this.latency = latency;
            return this;
        }

        public Builder success(boolean success) {
            this.success = success;
            return this;
        }

        public Builder errorType(String errorType) {
            this.errorType = errorType;
            this.success = false;
            return this;
        }

        public Builder callerId(String callerId) {
            this.callerId = callerId;
            return this;
        }

        public LLMCallRecord build() {
            return new LLMCallRecord(this);
        }
    }
}
