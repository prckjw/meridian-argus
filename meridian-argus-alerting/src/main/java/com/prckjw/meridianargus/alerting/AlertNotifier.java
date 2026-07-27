package com.prckjw.meridianargus.alerting;

/**
 * Delivers an {@link AlertEvent} somewhere — a chat webhook, email,
 * PagerDuty, or anything else. Implementations should handle their own
 * failures internally (log and return) rather than throw:
 * {@link RollingWindowAlertEvaluator} already isolates one notifier's
 * failure from the others and from the caller's business logic, but a
 * well-behaved notifier shouldn't rely on that as its only safety net.
 */
public interface AlertNotifier {

    void notify(AlertEvent event);
}
