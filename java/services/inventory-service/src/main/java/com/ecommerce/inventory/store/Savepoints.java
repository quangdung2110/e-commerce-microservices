package com.ecommerce.inventory.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.regex.Pattern;

/**
 * Named SQL savepoints inside the command transaction, for a handler that must undo its own
 * writes and continue (Reserve's {@code REJECT_ALL} after a failed guarded update, ADR-033).
 * They nest inside the executor's savepoint and are released by the transaction's commit.
 */
@Repository
public class Savepoints {

    private static final Pattern NAME = Pattern.compile("[a-z_]{1,40}");

    private final JdbcTemplate jdbc;

    public Savepoints(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void set(String name) {
        jdbc.execute("SAVEPOINT " + checked(name));
    }

    public void rollbackTo(String name) {
        jdbc.execute("ROLLBACK TO SAVEPOINT " + checked(name));
    }

    private static String checked(String name) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("invalid savepoint name");
        }
        return name;
    }
}
