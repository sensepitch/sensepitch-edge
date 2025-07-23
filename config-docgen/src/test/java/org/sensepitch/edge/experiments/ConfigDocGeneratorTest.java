package org.sensepitch.edge.experiments;

import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

public class ConfigDocGeneratorTest {

    // FIXME: flaky due to list order changes
    @Test
    @SneakyThrows
    void test() {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.io.PrintStream originalOut = System.out;
        System.setOut(new java.io.PrintStream(out));
        try {
            ConfigDocGenerator.main(new String[] {"."});
        } finally {
            System.setOut(originalOut);
        }
        String output = out.toString();

        String heading = "# Config";
        String javadocContent = "Configuration\n\nThis configuration..";
        String parameters = """
## Parameters:

### stringParam `String`

very important

### listParam `List<NestedConfig>`

List of nested configs

### objectParam `NestedConfig`

Nested config

""";
        String nestedConfigToMarkdown = """
# NestedConfig

Nested config

## Parameters:

### booleanParam `boolean`

### intParam `int`

""";
        assertThat(output).contains(
"""
%s
%s
%s
%s
""".formatted(heading, javadocContent, parameters, nestedConfigToMarkdown)
        );
    }
}
