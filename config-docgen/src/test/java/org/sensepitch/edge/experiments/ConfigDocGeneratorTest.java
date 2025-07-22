package org.sensepitch.edge.experiments;

import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

public class ConfigDocGeneratorTest {
    @Test
    @SneakyThrows
    void test() {
        // read config files from classpath
        ConfigDocGenerator.main(new String[] {"."});
    }
}
