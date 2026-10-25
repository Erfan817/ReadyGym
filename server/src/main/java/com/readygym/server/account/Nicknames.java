package com.readygym.server.account;

import com.readygym.server.common.ApiException;
import org.springframework.http.HttpStatus;

final class Nicknames {
    private Nicknames() {}

    /** 1–12 visible characters, no control characters; the name is shown on a public leaderboard. */
    static String clean(String raw) {
        String name = raw == null ? "" : raw.strip().replaceAll("\\p{Cntrl}", "");
        if (name.isEmpty() || name.codePointCount(0, name.length()) > 12) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_nickname", "昵称需要 1–12 个字");
        }
        return name;
    }
}
