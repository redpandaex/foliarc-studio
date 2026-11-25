package studio.foliarc.biz.common;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class TransactionCallbacks {

    private TransactionCallbacks() {
    }

    public static void afterCommit(Runnable callback) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            callback.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                callback.run();
            }
        });
    }

    public static void afterRollback(Runnable callback) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        callback.run();
                    }
                }
            });
        }
    }
}
