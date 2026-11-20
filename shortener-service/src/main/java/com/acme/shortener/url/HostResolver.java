package com.acme.shortener.url;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/** DNS port, so URL validation is testable without the network. */
public interface HostResolver {

  /**
   * @return every address the host resolves to (never empty)
   * @throws UnknownHostException if the host does not resolve or resolution timed out
   */
  List<InetAddress> resolve(String host) throws UnknownHostException;
}
