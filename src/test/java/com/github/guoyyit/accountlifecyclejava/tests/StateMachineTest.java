package com.github.guoyyit.accountlifecyclejava.tests;

import com.github.guoyyit.accountlifecyclejava.domain.AccountStatus;
import com.github.guoyyit.accountlifecyclejava.error.IllegalTransitionException;
import com.github.guoyyit.accountlifecyclejava.service.StateMachine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateMachineTest {

    private final StateMachine stateMachine = new StateMachine();

    @Test
    void allowsDocumentedLifecycleTransitions() {
        assertTrue(stateMachine.canTransition(AccountStatus.ACTIVE, AccountStatus.DORMANT));
        assertTrue(stateMachine.canTransition(AccountStatus.ACTIVE, AccountStatus.GRACE));
        assertTrue(stateMachine.canTransition(AccountStatus.DORMANT, AccountStatus.ACTIVE));
        assertTrue(stateMachine.canTransition(AccountStatus.GRACE, AccountStatus.ACTIVE));
        assertTrue(stateMachine.canTransition(AccountStatus.GRACE, AccountStatus.RECYCLED));
        assertTrue(stateMachine.canTransition(AccountStatus.DORMANT, AccountStatus.RECYCLED));
        assertTrue(stateMachine.canTransition(AccountStatus.RECYCLED, AccountStatus.ACTIVE));
        assertTrue(stateMachine.canTransition(AccountStatus.ACTIVE, AccountStatus.DISABLED));
    }

    @Test
    void rejectsIllegalTransitions() {
        assertFalse(stateMachine.canTransition(
                AccountStatus.DISABLED, AccountStatus.ACTIVE));
        assertFalse(stateMachine.canTransition(
                AccountStatus.RECYCLED, AccountStatus.DORMANT));
        assertFalse(stateMachine.canTransition(
                AccountStatus.ACTIVE, AccountStatus.RECYCLED));
        assertThrows(IllegalTransitionException.class, () ->
                stateMachine.assertTransition(
                        AccountStatus.DISABLED, AccountStatus.ACTIVE));
    }
}
