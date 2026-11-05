package com.banking.otp.observability;   // change the package for each service

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.event.Level;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Records every call to another service through a client class (CbsClientImpl, OtpClientImpl, ...):
 * which service, which operation, how long, and whether it worked. The target is the class name in kebab case.
 */
@Aspect
@Component
public class ClientCallAspect {

    private static final int MAX_ERROR_LENGTH = 300;

    private final PayloadSanitizer sanitizer;
    private final TrafficLog traffic;
    private final MeterRegistry meters;
    private final BoundedTags tags;

    public ClientCallAspect(PayloadSanitizer sanitizer, TrafficLog traffic, MeterRegistry meters, BoundedTags tags) {
        this.sanitizer = sanitizer;
        this.traffic = traffic;
        this.meters = meters;
        this.tags = tags;
    }

    @Around("execution(public * com.banking..client.*ClientImpl.*(..))")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        long start = System.nanoTime();
        Object result = null;
        Throwable failure = null;
        try {
            result = pjp.proceed();
            return result;
        } catch (Throwable ex) {
            failure = ex;
            throw ex;
        } finally {
            report(pjp, result, failure, Duration.ofNanos(System.nanoTime() - start));
        }
    }

    private void report(ProceedingJoinPoint pjp, Object result, Throwable failure, Duration elapsed) {
        try {
            MethodSignature signature = (MethodSignature) pjp.getSignature();
            String target = targetOf(signature.getDeclaringType().getSimpleName());
            String operation = signature.getName();
            String outcome = failure == null ? "ok" : "error";
            String exception = failure == null ? "none" : tags.limit("exception", failure.getClass().getSimpleName());

            Timer.builder("client.calls")
                    .tag("target", target)
                    .tag("operation", operation)
                    .tag("outcome", outcome)
                    .tag("exception", exception)
                    .register(meters)
                    .record(elapsed);

            Level level = failure != null ? Level.WARN : isRead(operation) ? Level.DEBUG : Level.INFO;
            if (!traffic.enabled(level)) {
                return;
            }

            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("event", "client.call");
            fields.put("target", target);
            fields.put("op", operation);
            fields.put("outcome", outcome);
            fields.put("durationMs", String.valueOf(elapsed.toMillis()));
            fields.put("request", sanitizer.describeArguments(signature.getParameterNames(), pjp.getArgs()));
            fields.put("response", failure != null
                    ? "error: " + failure.getClass().getSimpleName() + ": " + limit(failure.getMessage(), MAX_ERROR_LENGTH)
                    : sanitizer.describe(result));

            traffic.log(level, "Call to " + target + "." + operation + " " + (failure == null ? "answered" : "failed")
                    + " in " + elapsed.toMillis() + " ms", fields);
        } catch (RuntimeException ex) {
            // Recording must never change the result of a call.
        }
    }

    /** "ChargeFeeClientImpl" becomes "charge-fee". */
    private String targetOf(String className) {
        String name = className.endsWith("ClientImpl")
                ? className.substring(0, className.length() - "ClientImpl".length())
                : className;
        return name.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }

    private boolean isRead(String operation) {
        return operation.startsWith("get") || operation.startsWith("find")
                || operation.startsWith("list") || operation.startsWith("check");
    }

    private String limit(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}