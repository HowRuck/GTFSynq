package org.example.gtfsynq.store.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class SinkFailureClassifierTest {

    @Test
    void treatsConnectionAndSerializationStatesAsTransient() {
        assertTrue(
                SinkFailureClassifier.isTransient(new RuntimeException(new SQLException("connection lost", "08006"))));
        assertTrue(SinkFailureClassifier.isTransient(new RuntimeException(new SQLException("deadlock", "40P01"))));
        assertTrue(SinkFailureClassifier.isTransient(new RuntimeException(new SQLException("shutting down", "57P01"))));
    }

    @Test
    void treatsConstraintAndDataStatesAsDeterministic() {
        assertFalse(SinkFailureClassifier.isTransient(new RuntimeException(new SQLException("foreign key", "23503"))));
        assertFalse(
                SinkFailureClassifier.isTransient(new RuntimeException(new SQLException("invalid input", "22P02"))));
    }

    @Test
    void treatsUnknownFailuresAsDeterministic() {
        assertFalse(SinkFailureClassifier.isTransient(new RuntimeException("no sql state")));
        assertFalse(SinkFailureClassifier.isTransient(new RuntimeException(new SQLException("no state"))));
    }

    @Test
    void walksWrappedCausesButTerminatesOnCycles() {
        var transientCause = new RuntimeException(new SQLException("connection lost", "08006"));
        var wrapped = new RuntimeException(new IllegalStateException(transientCause));

        assertTrue(SinkFailureClassifier.isTransient(wrapped));

        var first = new RuntimeException("cycle-a");
        var second = new RuntimeException("cycle-b");
        first.initCause(second);
        second.initCause(first);
        assertFalse(SinkFailureClassifier.isTransient(first));
    }
}
