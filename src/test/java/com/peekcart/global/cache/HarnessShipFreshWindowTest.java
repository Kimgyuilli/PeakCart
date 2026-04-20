package com.peekcart.global.cache;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HarnessShipFreshWindowTest {

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(HarnessShipFreshWindow.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    @Test
    void clampPositive_normal_returnsTtl() {
        Duration resolved = HarnessShipFreshWindow.clampPositive(Duration.ofMinutes(5), Duration.ofMinutes(10));
        assertEquals(Duration.ofMinutes(5), resolved);
        assertSingleInfoContains("ttl=PT5M", "maxWindow=PT10M", "resolved=PT5M");
    }

    @Test
    void clampPositive_ttlOverMaxWindow_returnsMaxWindow() {
        Duration resolved = HarnessShipFreshWindow.clampPositive(Duration.ofMinutes(30), Duration.ofMinutes(10));
        assertEquals(Duration.ofMinutes(10), resolved);
        assertSingleInfoContains("ttl=PT30M", "maxWindow=PT10M", "resolved=PT10M");
    }

    @Test
    void clampPositive_negativeTtl_returnsZero() {
        Duration resolved = HarnessShipFreshWindow.clampPositive(Duration.ofSeconds(-1), Duration.ofMinutes(10));
        assertEquals(Duration.ZERO, resolved);
        assertSingleInfoContains("ttl=PT-1S", "maxWindow=PT10M", "resolved=PT0S");
    }

    @Test
    void clampPositive_nullTtl_throwsWithMessage() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> HarnessShipFreshWindow.clampPositive(null, Duration.ofMinutes(10)));
        assertEquals("ttl", ex.getMessage());
        assertEquals(0, appender.list.size());
    }

    @Test
    void clampPositive_nullMaxWindow_throwsWithMessage() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> HarnessShipFreshWindow.clampPositive(Duration.ofMinutes(1), null));
        assertEquals("maxWindow", ex.getMessage());
        assertEquals(0, appender.list.size());
    }

    private void assertSingleInfoContains(String... fragments) {
        assertEquals(1, appender.list.size(), "expected exactly one log event");
        ILoggingEvent event = appender.list.get(0);
        assertEquals(Level.INFO, event.getLevel());
        String formatted = event.getFormattedMessage();
        for (String fragment : fragments) {
            assertTrue(formatted.contains(fragment),
                    "expected log to contain '" + fragment + "', actual: " + formatted);
        }
    }
}
