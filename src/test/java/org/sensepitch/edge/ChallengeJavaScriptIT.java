package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.sensepitch.edge.TestingParameters.PROCESS_START_TIMEOUT;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Runs the browser challenge suite with Node.js as part of the integration tests.
class ChallengeJavaScriptIT {

  @Test
  void challengeJavaScript(@TempDir Path temporaryDirectory) throws Exception {
    Path output = temporaryDirectory.resolve("node-test-output.txt");
    Process process =
        new ProcessBuilder("node", "--test", "src/test/javascript/challenge.test.cjs")
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
    try {
      assertThat(process.waitFor(PROCESS_START_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
          .as("challenge JavaScript test completed")
          .isTrue();
      assertThat(process.exitValue()).as(Files.readString(output)).isZero();
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
      }
    }
  }
}
