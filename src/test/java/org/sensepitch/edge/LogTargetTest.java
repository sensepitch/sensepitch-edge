package org.sensepitch.edge;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

/**
 * @author Jens Wilke
 */
public class LogTargetTest {

  @Test
  public void logTest() {
    PrintStream out = new PrintStream(new ByteArrayOutputStream());
    LogTarget logTarget = new LogTarget.StreamOutput(out);
    logTarget.log("org.sensepitch.LoggingSource", LogInfo.builder()
        .message("org.sensepitch.LoggingSource")
        .error(new Throwable())
      .build());
  }

}
