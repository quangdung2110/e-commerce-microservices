package com.ecommerce.inventory.command;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.sql.SQLException;

/** Classifies database failures into retryable contention versus store unavailability. */
public final class DatabaseFailures {

    private static final String SERIALIZATION_FAILURE = "40001";
    private static final String DEADLOCK = "40P01";
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    private DatabaseFailures() {
    }

    /** Deadlock, serialization failure or lock timeout: retried, then {@code INVENTORY_BUSY}. */
    public static boolean isContention(Throwable error) {
        String state = sqlState(error);
        return SERIALIZATION_FAILURE.equals(state) || DEADLOCK.equals(state) || LOCK_NOT_AVAILABLE.equals(state);
    }

    /** The authoritative store cannot be reached: {@code INVENTORY_UNAVAILABLE}. */
    public static boolean isUnavailable(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof CannotGetJdbcConnectionException || t instanceof CannotCreateTransactionException
                    || t instanceof DataAccessResourceFailureException || t instanceof QueryTimeoutException) {
                return true;
            }
            if (t instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith("08")) {
                return true;
            }
        }
        return false;
    }

    public static String sqlState(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
