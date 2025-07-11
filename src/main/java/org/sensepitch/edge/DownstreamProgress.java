package org.sensepitch.edge;

import io.netty.channel.Channel;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * @author Jens Wilke
 */
public class DownstreamProgress {

  static Map<String, String> MAP = new ConcurrentHashMap<>();

  static {
    ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    Runnable task = () -> {
      print();
    };
    scheduler.scheduleAtFixedRate(task, 0, 10, TimeUnit.SECONDS);
  }

  public static void progress(Channel channel, String txt) {
    MAP.put(LogTarget.localChannelId(channel), txt);
  }

  public static void complete(Channel channel) {
    MAP.remove(LogTarget.localChannelId(channel));
  }

  private static void print() {
    System.err.println("Requests in flight: " + MAP.size());
    int count = 10;
    for (Map.Entry<String, String> entry : MAP.entrySet()) {
      System.err.println(entry.getKey() + ": " + entry.getValue());
      if (count-- <= 0) {
        System.err.println("--- truncated ---");
        break;
      }
    }
  }

}
