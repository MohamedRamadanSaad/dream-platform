package com.saadat.common.tx;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs side effects (notifications, e-mails) only after the surrounding transaction commits, so a
 * failure in a side effect can never roll back a business change (payment, submit) and a rolled-back
 * change never notifies anyone.
 *
 * <p>The action runs in its own {@code REQUIRES_NEW} transaction: inside {@code afterCommit} the original
 * transaction's resources are still bound, and a plain {@code REQUIRED} call would silently join the
 * already-committed transaction (its writes would never be committed). Outside a transaction the action
 * runs immediately (also in a new transaction). Exceptions are logged, never propagated.
 */
@Slf4j
@Component
public class AfterCommit {

    private final TransactionTemplate requiresNew;

    public AfterCommit(PlatformTransactionManager transactionManager) {
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void run(String description, Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safeRun(description, action);
                }
            });
        } else {
            safeRun(description, action);
        }
    }

    private void safeRun(String description, Runnable action) {
        try {
            requiresNew.executeWithoutResult(status -> action.run());
        } catch (RuntimeException e) {
            log.warn("After-commit action '{}' failed: {}", description, e.toString());
        }
    }
}
