package studio.foliarc.admin.controller;

import java.util.Locale;

final class AdminQuery {

    private AdminQuery() {
    }

    static String limit(int limit) {
        return "LIMIT " + Math.max(0, limit);
    }

    static String normalizeStatus(String status) {
        return status == null || status.isBlank() ? null : status.strip().toUpperCase(Locale.ROOT);
    }
}
