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
            ConfigDocGenerator.main(new String[] { "." });
        } finally {
            System.setOut(originalOut);
        }
        String output = out.toString();

        String expected = """
                # Config

                Configuration

                This configuration..


                ## Parameters:


                ### stringParam `String`

                very important


                ### listParam `List<NestedConfig>`

                list of nested configs


                ### objectParam `NestedConfig`

                nested config

                # NestedConfig

                Nested config


                ## Parameters:


                ### booleanParam `boolean`

                boolean parameter


                ### intParam `int`

                int parameter

                """;

        try {
            assertThat(output).contains(expected);
        } catch (AssertionError e) {
            String[] expectedLines = expected.split("\\r?\\n");
            String[] actualLines = output.split("\\r?\\n");
            int max = Math.max(expectedLines.length, actualLines.length);
            System.err.println("======= LINE BY LINE DIFF =======");
            for (int i = 0; i < max; i++) {
                String exp = i < expectedLines.length ? expectedLines[i] : "<missing>";
                String act = i < actualLines.length ? actualLines[i] : "<missing>";
                String mark = exp.equals(act) ? " " : "!";
                System.err.printf("%s [%03d] E: '%s'%n", mark, i+1, exp);
                System.err.printf("%s [%03d] A: '%s'%n", mark, i+1, act);
            }
            System.err.println("======= END DIFF =======");
            throw e;
        }
    }
}
