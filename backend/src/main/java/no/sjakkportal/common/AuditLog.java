package no.sjakkportal.common;

import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Append-only record of privileged actions. Never write secrets (passwords, tokens) into it. */
@Component
public class AuditLog {

    private final JdbcClient jdbc;

    public AuditLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(long actorId, String action, String target, String detail) {
        jdbc.sql("INSERT INTO audit_log (actor_id, action, target, detail, trace_id) VALUES (?, ?, ?, ?, ?)")
                .params(actorId, action, target, detail, MDC.get("traceId")).update();
    }
}
