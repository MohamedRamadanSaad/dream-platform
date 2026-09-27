package com.saadat.config.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.CoreConstants;
import ch.qos.logback.core.LayoutBase;
import java.time.Instant;
import java.util.Map;

/**
 * Minimal dependency-free JSON-lines layout for prod/dev logs:
 * {@code {"ts","level","logger","thread","msg","requestId","userId",...mdc,"exception"}}.
 */
public class JsonLogLayout extends LayoutBase<ILoggingEvent> {

    @Override
    public String doLayout(ILoggingEvent event) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        field(sb, "ts", Instant.ofEpochMilli(event.getTimeStamp()).toString(), true);
        field(sb, "level", String.valueOf(event.getLevel()), false);
        field(sb, "logger", event.getLoggerName(), false);
        field(sb, "thread", event.getThreadName(), false);
        field(sb, "msg", event.getFormattedMessage(), false);
        Map<String, String> mdc = event.getMDCPropertyMap();
        if (mdc != null) {
            for (Map.Entry<String, String> e : mdc.entrySet()) {
                field(sb, e.getKey(), e.getValue(), false);
            }
        }
        IThrowableProxy throwable = event.getThrowableProxy();
        if (throwable != null) {
            field(sb, "exception", ThrowableProxyUtil.asString(throwable), false);
        }
        sb.append('}').append(CoreConstants.LINE_SEPARATOR);
        return sb.toString();
    }

    private static void field(StringBuilder sb, String name, String value, boolean first) {
        if (!first) {
            sb.append(',');
        }
        sb.append('"');
        escape(sb, name);
        sb.append("\":");
        if (value == null) {
            sb.append("null");
        } else {
            sb.append('"');
            escape(sb, value);
            sb.append('"');
        }
    }

    private static void escape(StringBuilder sb, String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
    }
}
