package com.cleanroommc.bouncepad.test.classloader;

import com.cleanroommc.bouncepad.test.util.TestClassLoader;
import com.cleanroommc.test.Main;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;

public class CodeSourceLocationTest {

    @Test
    public void test() throws IOException, URISyntaxException {
        try (var testLoader = new TestClassLoader()) {
            testLoader.appendTestJar();

            Main.main(new String[0]);
            Assertions.assertNotNull(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath());
        }
    }

}
