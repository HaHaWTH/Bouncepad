package com.cleanroommc.bouncepad;

import com.cleanroommc.bouncepad.api.transformer.Transformer;
import net.minecraft.launchwrapper.LaunchClassLoader;
import net.minecraft.launchwrapper.IClassTransformer;

import java.io.File;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.List;

public class BouncepadClassLoader extends LaunchClassLoader {

    static {
        ClassLoader.registerAsParallelCapable();
    }

    private final List<Transformer> transformers = new ArrayList<>();

    // Called by VM via -Djava.system.class.loader
    @SuppressWarnings("unused")
    public BouncepadClassLoader(ClassLoader parent) {
        this("BouncepadClassLoader");
    }

    protected BouncepadClassLoader(String name) {
        super(name);
        this.configureDefaultExclusions();
    }

    public <T> Class<T> getClass(String name) {
        return (Class<T>) this.findLoadedClass(name);
    }

    public boolean isClassLoaded(String name) {
        return this.getClass(name) != null;
    }

    @Override
    public void registerTransformer(String transformerName) {
        throw new UnsupportedOperationException("BouncepadClassLoader only allows registration of transformers through services.");
    }

    @Override
    public List<IClassTransformer> getTransformers() {
        return super.getTransformers();
    }

    @Override
    public byte[] getClassBytes(String name) {
        return super.getClassBytes(name);
    }

    /**
     * Required for Java Agents to work on HotSpot
     * @param path The file path added to the classpath
     */
    @SuppressWarnings("unused")
    public void appendToClassPathForInstrumentation(String path) {
        try {
            this.addURL(new File(path).toURI().toURL());
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    protected void configureDefaultExclusions() {
        this.addClassLoaderExclusion("com.cleanroommc.bouncepad.");
        this.addClassLoaderExclusion("net.minecraft.launchwrapper.");
        this.addClassLoaderExclusion("zone.rong.imaginebreaker.");
    }

    void configureClasspath() {
        for (var classpath : System.getProperty("java.class.path").split(File.pathSeparator)) {
            try {
                this.addURL(new File(classpath).toURI().toURL());
            } catch (MalformedURLException e2) {
                Bouncepad.logger().error("Unable to parse {} as an URL to be added to the classpath.", classpath, e2);
            }
        }
    }

}
