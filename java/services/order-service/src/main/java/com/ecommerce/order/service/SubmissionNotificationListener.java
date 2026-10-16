package com.ecommerce.order.service;

import com.ecommerce.order.config.SubmissionWaiterProperties;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** One session-bound LISTEN connection per replica, outside the request/activity Hikari pool. */
@Component
public class SubmissionNotificationListener {
    private static final Logger log = LoggerFactory.getLogger(SubmissionNotificationListener.class);
    private final DataSourceProperties database;
    private final SubmissionWaiters waiters;
    private final SubmissionWaiterProperties config;
    private final AtomicBoolean connected = new AtomicBoolean();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private volatile Connection connection;
    private Thread thread;

    public SubmissionNotificationListener(DataSourceProperties database, SubmissionWaiters waiters,
                                          SubmissionWaiterProperties config, MeterRegistry meters) {
        this.database = database;
        this.waiters = waiters;
        this.config = config;
        meters.gauge("order.checkout.notification.connected", connected, value -> value.get() ? 1 : 0);
    }

    @PostConstruct
    public void start() {
        thread = Thread.ofPlatform().daemon().name("order-submission-listener").start(this::listen);
    }

    private void listen() {
        while (stopped.getCount() != 0) {
            try (Connection session = open()) {
                connection = session;
                session.setAutoCommit(true);
                try (Statement statement = session.createStatement()) {
                    statement.execute("LISTEN order_submission_changed");
                }
                connected.set(true);
                // LISTEN has committed before the recovery read (PostgreSQL LISTEN startup rule).
                waiters.reconcile();
                PGConnection pg = session.unwrap(PGConnection.class);
                while (stopped.getCount() != 0) {
                    PGNotification[] notifications = pg.getNotifications(1000);
                    if (notifications == null) continue;
                    for (PGNotification notification : notifications) {
                        try {
                            waiters.signal(UUID.fromString(notification.getParameter()));
                        } catch (IllegalArgumentException malformed) {
                            log.warn("Ignoring invalid submission notification token");
                        }
                    }
                }
            } catch (SQLException error) {
                if (stopped.getCount() != 0) {
                    log.warn("Submission listener disconnected; batch recovery remains active (SQLState={})",
                            error.getSQLState());
                }
            } finally {
                connected.set(false);
                connection = null;
            }
            try {
                if (stopped.await(config.reconnectDelay().toMillis(), TimeUnit.MILLISECONDS)) return;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private Connection open() throws SQLException {
        Properties properties = new Properties();
        if (database.determineUsername() != null) properties.setProperty("user", database.determineUsername());
        if (database.determinePassword() != null) properties.setProperty("password", database.determinePassword());
        properties.setProperty("ApplicationName", "order-submission-listener");
        properties.setProperty("connectTimeout", "3");
        properties.setProperty("socketTimeout", "5");
        properties.setProperty("tcpKeepAlive", "true");
        return DriverManager.getConnection(database.determineUrl(), properties);
    }

    @PreDestroy
    public void close() throws InterruptedException {
        stopped.countDown();
        Connection current = connection;
        if (current != null) {
            try { current.close(); } catch (SQLException ignored) { /* Listener is already stopping. */ }
        }
        if (thread != null) {
            thread.interrupt();
            thread.join(4000);
        }
    }
}
