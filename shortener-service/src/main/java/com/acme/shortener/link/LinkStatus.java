package com.acme.shortener.link;

/** Lifecycle of a short link. Deletion is soft: the row stays, the redirect stops. */
public enum LinkStatus {
  ACTIVE,
  DELETED
}
