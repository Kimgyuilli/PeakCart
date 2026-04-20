package com.peekcart.global.cache;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Objects;

@Slf4j
public final class HarnessShipFreshWindow {

    private HarnessShipFreshWindow() {
    }

    public static Duration clampPositive(Duration ttl, Duration maxWindow) {
        Objects.requireNonNull(ttl, "ttl");
        Objects.requireNonNull(maxWindow, "maxWindow");

        Duration resolved;
        if (ttl.isNegative()) {
            resolved = Duration.ZERO;
        } else if (ttl.compareTo(maxWindow) > 0) {
            resolved = maxWindow;
        } else {
            resolved = ttl;
        }

        log.info("clampPositive ttl={} maxWindow={} resolved={}", ttl, maxWindow, resolved);
        return resolved;
    }
}
