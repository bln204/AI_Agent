package com.aiagent.util;

import java.util.Map;

public class RoleConstants {
    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_DIRECTOR = "DIRECTOR";
    public static final String ROLE_MANAGER = "MANAGER";
    public static final String ROLE_EMPLOYEE = "EMPLOYEE";
    public static final String ROLE_GUEST = "GUEST";

    private static final Map<String, String> VI_TO_CODE = Map.of(
        "Admin", ROLE_ADMIN,
        "Giám đốc", ROLE_DIRECTOR,
        "Trưởng phòng", ROLE_MANAGER,
        "Nhân viên", ROLE_EMPLOYEE
    );

    /**
     * Map a potentially fragile Vietnamese role label to a stable code.
     * If no match, returns ROLE_GUEST.
     */
    public static String fromVietnamese(String viRole) {
        if (viRole == null) return ROLE_GUEST;
        return VI_TO_CODE.getOrDefault(viRole.trim(), ROLE_GUEST);
    }

    public static boolean isHighLevel(String roleCode) {
        return ROLE_ADMIN.equals(roleCode) || ROLE_DIRECTOR.equals(roleCode);
    }

    public static boolean isManagerOrAbove(String roleCode) {
        return ROLE_ADMIN.equals(roleCode) || ROLE_DIRECTOR.equals(roleCode) || ROLE_MANAGER.equals(roleCode);
    }
}
