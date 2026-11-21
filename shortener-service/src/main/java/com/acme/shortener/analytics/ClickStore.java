package com.acme.shortener.analytics;

import java.util.List;

/** Port: persists one batch of clicks. Throwing loses the batch (at-most-once). */
public interface ClickStore {

  void write(List<ClickEvent> batch);
}
