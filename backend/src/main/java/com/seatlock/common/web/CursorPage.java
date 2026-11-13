package com.seatlock.common.web;

import java.util.List;

/**
 * One page of a keyset-paginated list. {@code nextCursor} is opaque to clients:
 * pass it back unchanged to get the next page; it is null on the last page.
 */
public record CursorPage<T>(List<T> items, String nextCursor) {

    public CursorPage {
        items = List.copyOf(items);
    }

    public boolean hasMore() {
        return nextCursor != null;
    }
}