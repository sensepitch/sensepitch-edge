package org.sensepitch.edge;

import java.util.concurrent.atomic.LongAdder;

/**
 * @author Jens Wilke
 */
public class ProxyMetrics implements HasMetrics {

  private LongAdder ingressRequestReceiveTimeout = new LongAdder();
  public long getIngressRequestReceiveTimeout() { return ingressRequestReceiveTimeout.longValue(); }
  public void incrementIngressRequestReceiveTimeout() { ingressRequestReceiveTimeout.increment(); }

  private LongAdder ingressKeepAliveTimeout = new LongAdder();
  public long getIngressKeepAliveTimeout() { return ingressKeepAliveTimeout.longValue(); }
  public void incrementIngressKeepAliveTimeout() { ingressKeepAliveTimeout.increment(); }

  private LongAdder downstreamHandshakeErrorCounter = new LongAdder();

  public long getDownstreamHandshakeErrorCount() {
    return downstreamHandshakeErrorCounter.sum();
  }

  public void incrementDownstreamHandshakeErrorCount() {
    downstreamHandshakeErrorCounter.increment();
  }

  private LongAdder connectionResetCounter = new LongAdder();
  public long getConnectionResetCount() {
    return connectionResetCounter.sum();
  }

  public void incrementConnectionResetCount() {
    connectionResetCounter.increment();
  }

  @Override
  public Object getMetrics() { return this; }

}
