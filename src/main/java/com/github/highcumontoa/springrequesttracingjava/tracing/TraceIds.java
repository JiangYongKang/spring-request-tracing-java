package com.github.highcumontoa.springrequesttracingjava.tracing;

import java.util.UUID;
import java.util.regex.Pattern;

/** Generation / validation rules for trace identifiers. */
public final class TraceIds {

    /** HTTP header carrying the trace id inbound and outbound. */
    public static final String HEADER = "X-Trace-Id";
    /** SLF4J MDC key under which the current trace id is published. */
    public static final String MDC_KEY = "traceId";

    /**
     * Inbound ids are honoured only when they are short and contain only
     * URL-/log-safe characters, so a caller cannot inject junk into our logs.
     */
    private static final Pattern ACCEPTED = Pattern.compile("[A-Za-z0-9._-]{8,64}");

    private TraceIds() {
    }

    /** Generate a fresh trace identifier: 32 lowercase hex characters. */
    public static String generate() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** Validate an inbound trace identifier supplied by the caller. */
    public static boolean isValid(String value) {
        return value != null && ACCEPTED.matcher(value).matches();
    }

    /** Use the inbound id when valid, otherwise generate a new one. */
    public static String resolve(String inbound) {
        return isValid(inbound) ? inbound : generate();
    }
}
