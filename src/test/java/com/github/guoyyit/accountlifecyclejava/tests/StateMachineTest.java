package com.github.guoyyit.accountlifecyclejava.tests;

import com.github.guoyyit.accountlifecyclejava.engine.StateMachine;
import com.github.guoyyit.accountlifecyclejava.lifecycle.InvalidTransitionException;
import com.github.guoyyit.accountlifecyclejava.model.AccountState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateMachineTest {

    private final StateMachine stateMachine = new StateMachine();

    @Test
    void allowsDefinedLifecycleMoves() {
        assertTrue(stateMachine.canTransition(AccountState.ACTIVE, AccountState.DORMANT));
        assertTrue(stateMachine.canTransition(AccountState.ACTIVE, AccountState.GRACE));
        assertTrue(stateMachine.canTransition(AccountState.ACTIVE, AccountState.DISABLED));
        assertTrue(stateMachine.canTransition(AccountState.DORMANT, AccountState.ACTIVE));
        assertTrue(stateMachine.canTransition(AccountState.DORMANT, AccountState.RECYCLED));
        assertTrue(stateMachine.canTransition(AccountState.GRACE, AccountState.ACTIVE));
        assertTrue(stateMachine.canTransition(AccountState.GRACE, AccountState.RECYCLED));
        assertTrue(stateMachine.canTransition(AccountState.RECYCLED, AccountState.ACTIVE));
        assertTrue(stateMachine.canTransition(AccountState.DISABLED, AccountState.ACTIVE));
    }

    @Test
    void rejectsIllegalMoves() {
        assertFalse(stateMachine.canTransition(AccountState.ACTIVE, AccountState.RECYCLED));
        assertFalse(stateMachine.canTransition(AccountState.GRACE, AccountState.DORMANT));
        assertFalse(stateMachine.canTransition(AccountState.RECYCLED, AccountState.DISABLED));
        assertThrows(InvalidTransitionException.class,
                () -> stateMachine.checkTransition(AccountState.ACTIVE, AccountState.RECYCLED));
    }

    @Test
    void sameStateIsIdempotent() {
        assertDoesNotThrow(
                () -> stateMachine.checkTransition(AccountState.ACTIVE, AccountState.ACTIVE));
    }
}
