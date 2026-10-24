package com.cloudprocure.common.http;

import java.util.List;

/** 不附带total，避免每次列表查询都触发全表COUNT。 */
public record CursorPage<T>(List<T> items, String nextCursor, boolean hasMore) {

    public CursorPage {
        items = List.copyOf(items);
        if (hasMore ? nextCursor == null || nextCursor.isBlank() : nextCursor != null) {
            throw new IllegalArgumentException("hasMore与nextCursor不一致");
        }
    }
}
