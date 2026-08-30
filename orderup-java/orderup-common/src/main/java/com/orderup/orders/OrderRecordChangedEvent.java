package com.orderup.orders;

/**
 * Spring application event fired by {@link OrderRecordListener} on every
 * insert / update of an {@link OrderRecord}. Subscribers use it to
 * invalidate caches, refresh in-memory views or (as in the chartink
 * dashboard) push an SSE message to connected browsers.
 */
public record OrderRecordChangedEvent(String indicator, Long id) {}

