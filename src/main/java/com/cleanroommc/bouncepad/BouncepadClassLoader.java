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

    /**
     * <ol>
     *     <li>Default ClassLoader Exclusions:<ul>
     *         <li>java.</li>
     *         <li>sun.</li>
     *         <li>org.lwjgl.</li>
     *         <li>org.apache.logging.</li>
     *         <li>net.minecraft.launchwrapper.</li>
     *     </ul></li>
     *     <li>Bouncepad ClassLoader Exclusions:<ul>
     *         <li>com.cleanroommc.bouncepad.</li>
     *         <li>zone.rong.imaginebreaker.</li>
     *     </ul></li>
     *     <li>Default Transformer Exclusions:<ul>
     *         <li>javax.</li>
     *         <li>argo.</li>
     *         <li>org.objectweb.asm.</li>
     *         <li>com.google.common.</li>
     *         <li>org.bouncycastle.</li>
     *         <li><s>net.minecraft.launchwrapper.injector.</s></li>
     *     </ul></li>
     * </ol>
     */
    @Override
    protected void configureDefaultExclusions() {
        this.addClassLoaderExclusion("com.cleanroommc.bouncepad.");
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
