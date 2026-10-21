package online.rizzlers.proxylogger.access;

/**
 * Result of an access evaluation check.
 */
public record AccessDecision(
        boolean allowed,
        String result,
        String reason,
        String messageKey
) {
    public static final String RESULT_ALLOWED = "ALLOWED";
    public static final String RESULT_BLACKLIST_DENY = "BLACKLIST_DENY";
    public static final String RESULT_MISSING_ROLE = "MISSING_ROLE";
    public static final String RESULT_UNLINKED = "UNLINKED";
    public static final String RESULT_ERROR_DENY = "ERROR_DENY";

    public static AccessDecision allow(String reason) {
        return new AccessDecision(true, RESULT_ALLOWED, reason, null);
    }

    public static AccessDecision blacklistDeny(String reason) {
        return new AccessDecision(false, RESULT_BLACKLIST_DENY, reason, "blacklisted");
    }

    public static AccessDecision missingRole(String reason) {
        return new AccessDecision(false, RESULT_MISSING_ROLE, reason, "missing-access-role");
    }

    public static AccessDecision unlinked(String reason) {
        return new AccessDecision(false, RESULT_UNLINKED, reason, "unlinked");
    }

    public static AccessDecision errorDeny(String reason) {
        return new AccessDecision(false, RESULT_ERROR_DENY, reason, "verification-unavailable");
    }
}
