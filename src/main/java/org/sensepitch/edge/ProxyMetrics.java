package org.sensepitch.edge;

import java.util.concurrent.atomic.LongAdder;

/**
 * @author Jens Wilke
 */
public class ProxyMetrics implements HasMetrics {

  private LongAdder connectionResetCounter = new LongAdder();
  private LongAdder downstreamHandshakeErrorCounter = new LongAdder();

  public long getDownstreamHandshakeErrorCount() {
    return downstreamHandshakeErrorCounter.sum();
  }

  public void incrementDownstreamHandshakeErrorCount() {
    downstreamHandshakeErrorCounter.increment();
  }

  public long getConnectionResetCount() {
    return connectionResetCounter.sum();
  }

  public void incrementConnectionResetCount() {
    connectionResetCounter.increment();
  }

  @Override
  public Object getMetrics() { return this; }

}
