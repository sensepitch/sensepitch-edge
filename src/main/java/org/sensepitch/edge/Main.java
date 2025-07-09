package org.sensepitch.edge;

/**
 * @author Jens Wilke
 */
public class Main {

  public static void main(String[] args) throws Exception {
    ProxyConfig.ProxyConfigBuilder builder = ProxyConfig.builder();
    EnvInjector.injectFromEnv("SENSEPITCH_EDGE_", System.getenv(), builder);
    new Proxy(builder.build()).start();
  }

}
