package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/// Exercises the built OCI image through Docker when the oci-image Maven profile is enabled.
@EnabledIfSystemProperty(named = "ociImage.enabled", matches = "true")
class OciImageIT {

  /// Bounds the whole smoke script: the container starts, serves traffic and stops on
  /// `SIGTERM`, three steps that each may take up to [TestingParameters#PROCESS_START_TIMEOUT].
  static final Duration SMOKE_TIMEOUT = TestingParameters.PROCESS_START_TIMEOUT.multipliedBy(3);

  @TempDir Path dir;

  @Test
  void servesTrafficAndShutsDownOnSigterm() throws Exception {
    Path output = dir.resolve("oci-smoke.log");
    Process smoke =
        new ProcessBuilder(
                "bash", "src/test/resources/oci-smoke.sh", System.getProperty("ociImage"))
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
    try {
      assertThat(smoke.waitFor(SMOKE_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
          .as("OCI smoke test timed out; output:%n%s", Files.readString(output))
          .isTrue();
      assertThat(smoke.exitValue())
          .as("OCI smoke test output:%n%s", Files.readString(output))
          .isZero();
    } finally {
      if (smoke.isAlive()) {
        smoke.destroy();
        if (!smoke.waitFor(TestingParameters.TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
          smoke.destroyForcibly();
        }
      }
    }
  }
}
