package com.spotify.helios.client;

import java.net.InetAddress;
import java.net.URI;

public interface Endpoint {

  InetAddress inetAddress();

  URI uri();

}
