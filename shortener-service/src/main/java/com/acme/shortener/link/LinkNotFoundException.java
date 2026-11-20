package com.acme.shortener.link;

/** Unknown code, a deleted link on redirect, or a link owned by another API key. Maps to 404. */
public class LinkNotFoundException extends RuntimeException {

  public LinkNotFoundException(String code) {
    super("no link with code '" + code + "'");
  }
}
