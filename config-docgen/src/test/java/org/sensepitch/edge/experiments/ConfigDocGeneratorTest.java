package org.sensepitch.edge.experiments;

import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

public class ConfigDocGeneratorTest {
    
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
        
        assertThat(output).contains(
"""
## Config
- Description: Configuration

This configuration..
- Parameters:
    - stringParam (String) : String parameter, very important (NotBlank)
    - listParam (List<NestedConfig>) : List<NestedConfig> parameter (Size(min = 1))
    - objectParam (NestedConfig) : NestedConfig parameter (NotNull)

## NestedConfig
- Description: Nested config
- Parameters:
    - booleanParam (boolean) : boolean parameter ()
    - intParam (int) : int parameter (Min(0), NonNegative)

"""
        );
    }
}
