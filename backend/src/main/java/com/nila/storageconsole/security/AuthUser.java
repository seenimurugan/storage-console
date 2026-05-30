package com.nila.storageconsole.security;

import com.nila.storageconsole.user.User;

public record AuthUser(Long id, String username, String displayName, User.Role role) {
    public static AuthUser from(User u) {
        return new AuthUser(u.getId(), u.getUsername(), u.getDisplayName(), u.getRole());
    }
}
