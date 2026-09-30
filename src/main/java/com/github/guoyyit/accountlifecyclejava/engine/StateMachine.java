package com.github.guoyyit.accountlifecyclejava.engine;

import com.github.guoyyit.accountlifecyclejava.lifecycle.InvalidTransitionException;
import com.github.guoyyit.accountlifecyclejava.model.AccountState;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class StateMachine {

    private static final Map<AccountState, Set<AccountState>> ALLOWED = buildAllowed();

    public StateMachine() {
    }

    public boolean canTransition(AccountState from, AccountState to) {
        return from == to || ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    public void checkTransition(AccountState from, AccountState to) {
        if (!canTransition(from, to)) {
            throw new InvalidTransitionException(
                    "非法状态迁移: " + from + " -> " + to + "，" + from + " 仅允许迁移至 "
                            + ALLOWED.getOrDefault(from, Set.of()));
        }
    }

    public Set<AccountState> allowedTargets(AccountState from) {
        return ALLOWED.getOrDefault(from, Set.of());
    }

    private static Map<AccountState, Set<AccountState>> buildAllowed() {
        Map<AccountState, Set<AccountState>> map = new LinkedHashMap<>();
        map.put(AccountState.ACTIVE,
                Set.of(AccountState.DORMANT, AccountState.GRACE, AccountState.DISABLED));
        map.put(AccountState.DORMANT,
                Set.of(AccountState.ACTIVE, AccountState.GRACE, AccountState.DISABLED,
                        AccountState.RECYCLED));
        map.put(AccountState.GRACE,
                Set.of(AccountState.ACTIVE, AccountState.DISABLED, AccountState.RECYCLED));
        map.put(AccountState.DISABLED, Set.of(AccountState.ACTIVE));
        map.put(AccountState.RECYCLED, Set.of(AccountState.ACTIVE));
        return Map.copyOf(map);
    }
}
