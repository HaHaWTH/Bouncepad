package com.cleanroommc.bouncepad.test.util;

import com.cleanroommc.bouncepad.BouncepadClassLoader;

import java.io.File;
import java.net.MalformedURLException;

public class TestClassLoader extends BouncepadClassLoader {

    public TestClassLoader() {
        super(TestClassLoader.class.getClassLoader());
    }

    public void appendTestJar() {
        try {
            this.addURL(new File(".", "test/build/libs/test-1.0.jar").toURI().toURL());
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

}
