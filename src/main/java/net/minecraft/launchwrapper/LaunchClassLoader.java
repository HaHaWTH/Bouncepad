package net.minecraft.launchwrapper;

import com.cleanroommc.bouncepad.Bouncepad;
import com.cleanroommc.bouncepad.api.transformer.FailedClassTransformationException;
import com.cleanroommc.bouncepad.debug.DebugOption;
import jdk.internal.access.SharedSecrets;

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

public class LaunchClassLoader extends URLClassLoader {

    static {
        ClassLoader.registerAsParallelCapable();
    }

    protected final List<IClassTransformer> legacyTransformers = new ArrayList<>();

    private final ClassLoader parent = getClass().getClassLoader();
    private final Set<String> classLoaderExclusions = new HashSet<>();
    private final Set<String> transformationExclusions = new HashSet<>();
    private final boolean transformClasses = DebugOption.DO_NOT_TRANSFORM_CLASSES.isOff();

    protected IClassNameTransformer classNameTransformer;

    protected LaunchClassLoader(String name) {
        super(name, new URL[0], null);
        this.configureDefaultExclusions();
    }

    /**
     * <ol>
     *     <li>If a class name starts with any classloader exception, delegate to {@link LaunchClassLoader#parent#loadClass(String)}</li>
     *     <li>If a class name starts with any transformer exception, delegate to {@link URLClassLoader#findClass}</li>
     *     <li>transformName with {@link IClassNameTransformer} if present, and check for an existing loaded class</li>
     *     <li>untransformName, find the last separator and use to determine the package name and file path of the .class file</li>
     *     <li>Open a URLConnection using findCodeSourceConnectionFor on the determined filename</li>
     *     <li>Check package sealing for the given URLConnection, unless the untransformed name starts with "net.minecraft.", giving a severe warning if the package is already sealed. Otherwise, create and register a new Package.</li>
     *     <li>runTransformers on getClassBytes</li>
     *     <li>Save the debug class if enabled</li>
     *     <li>defineClass with the appropriate CodeSigners</li>
     *     <li>Cache and return the class</li>
     *     <li>Throw a ClassNotFoundException if any exception happens during the transforming process</li>
     * </ol>
     */
    @Override
    public Class<?> findClass(String name) throws ClassNotFoundException {
        // TODO: profile against a trie
        for (var classLoaderExclusion : this.classLoaderExclusions) {
            if (name.startsWith(classLoaderExclusion)) {
                return this.parent.loadClass(name);
            }
        }
        boolean transformClass = this.transformClasses;
        for (var transformerExclusion : this.transformationExclusions) {
            if (name.startsWith(transformerExclusion)) {
                transformClass = false;
                break;
            }
        }
        try {
            var clazz = this.findLoadedClass(name);
            if (clazz == null) {
                var transformedName = this.remapClassName(name);
                clazz = this.findLoadedClass(transformedName);
                if (clazz == null) {
                    var untransformedName = transformClass ? this.unmapClassName(name) : name;
                    int lastDot = untransformedName.lastIndexOf('.');
                    var packageName = (lastDot == -1) ? "" : untransformedName.substring(0, lastDot);
                    var classPath = untransformedName.replace('.', '/') + ".class";
                    var connection = findCodeSourceConnectionFor(classPath);
                    CodeSource codeSource;
                    byte[] classBytes = null;
                    if (!packageName.isEmpty()) {
                        if (!untransformedName.startsWith("net.minecraft.") && connection instanceof JarURLConnection jarConnection) {
                            var packageUrl = jarConnection.getJarFileURL();
                            CodeSigner[] codeSigners = null;
                            try {
                                this.getAndVerifyPackage(packageName, jarConnection.getManifest(), packageUrl);
                                classBytes = transformClass ? this.getClassBytes(untransformedName) : this.getJavaClassBytes(untransformedName);
                                codeSigners = jarConnection.getJarEntry().getCodeSigners();
                            } catch (IOException ignore) { }
                            // LaunchClassLoader: uses nested jar!file URL instead of the jar URL
                            // However the jar URL is used when the transformer exclusions is applied.
                            var classSourceUrl = transformClass ? jarConnection.getURL() : jarConnection.getJarFileURL();
                            codeSource = new CodeSource(classSourceUrl, codeSigners);
                        } else {
                            this.getAndVerifyPackage(packageName, null, null);
                            codeSource = connection == null ? null : new CodeSource(connection.getURL(), (CodeSigner[]) null);
                        }
                    } else {
                        var url = connection == null ? null : connection.getURL();
                        codeSource = url == null ? null : new CodeSource(url, (CodeSigner[]) null);
                    }
                    if (classBytes == null) {
                        try {
                            classBytes = transformClass ? this.getClassBytes(untransformedName) : this.getJavaClassBytes(untransformedName);
                        } catch (IOException ignore) { }
                    }
                    if (transformClass) {
                        try {
                            classBytes = this.runTransformers(untransformedName, transformedName, classBytes);
                        } catch (Throwable t) {
                            throw new ClassNotFoundException("Exception caught while transforming class " + name, t);
                        }
                    }
                    if (classBytes == null) {
                        throw new ClassNotFoundException("Class bytes are null for " + name);
                    }
                    return this.defineClass(transformedName, classBytes, 0, classBytes.length, codeSource);
                }
            }
            return clazz;
        } catch (Throwable e) {
            throw new ClassNotFoundException("Failed to find class " + name, e);
        }
    }

    /**
     * Registers a legacy transformer, it is advised to use {@link com.cleanroommc.bouncepad.BouncepadClassLoader}
     * and its new transformers via the service method rather than this method.
     * This is kept here for backwards compatibility.
     *
     * @param transformerName the binary name of the transformer class (a.b.c)
     */
    @Deprecated
    public void registerTransformer(String transformerName) {
        try {
            Class<?> transformerClass = Class.forName(transformerName, true, this);
            if (!IClassTransformer.class.isAssignableFrom(transformerClass)) {
                Bouncepad.logger().fatal("Attempted to register legacy-style transformer {} that isn't of IClassTransformer type", transformerClass);
                return;
            }
            IClassTransformer transformer = (IClassTransformer) transformerClass.getConstructor().newInstance();
            this.legacyTransformers.add(transformer);
            Bouncepad.logger().warn("Legacy-style transformer [{}] registered.", transformerName);
            if (this.classNameTransformer == null && transformer instanceof IClassNameTransformer) {
                this.classNameTransformer = (IClassNameTransformer) transformer;
            }
        } catch (Exception e) {
            Bouncepad.logger().error("Legacy-style transformer [{}] registration failed.", transformerName, e);
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
    protected void configureDefaultExclusions() {
        this.addClassLoaderExclusion("java.");
        this.addClassLoaderExclusion("sun.");
        this.addClassLoaderExclusion("org.lwjgl.");
        this.addClassLoaderExclusion("org.apache.logging.");
        this.addClassLoaderExclusion("net.minecraft.launchwrapper.");

        this.addTransformerExclusion("javax.");
        this.addTransformerExclusion("argo.");
        this.addTransformerExclusion("org.objectweb.asm.");
        this.addTransformerExclusion("com.google.common.");
        this.addTransformerExclusion("org.bouncycastle.");
    }

    protected byte[] runTransformers(String untransformedName, String transformedName, byte[] classBytes) throws FailedClassTransformationException {
        byte[] transformedBytes = classBytes;
        IClassTransformer currentTransformer = null;
        try {
            List<IClassTransformer> transformers = this.legacyTransformers;
            for (int i = 0; i < transformers.size(); i++) {
                currentTransformer = transformers.get(i);
                transformedBytes = currentTransformer.transform(untransformedName, transformedName, transformedBytes);
            }
        } catch (Throwable t) {
            // TODO: give transformation stack context in exception
            throw new FailedClassTransformationException("Failed to transform class " + transformedName + " after transformer " + currentTransformer.getClass(), t);
        }

        return transformedBytes;
    }

    public List<IClassTransformer> getTransformers() {
        return this.legacyTransformers;
    }

    public void addClassLoaderExclusion(String exclude) {
        this.classLoaderExclusions.add(exclude);
    }

    public void addTransformerExclusion(String exclude) {
        this.transformationExclusions.add(exclude);
    }

    /**
     * Lifted visibility of {@link URLClassLoader#addURL(URL)}
     */
    @Override
    public void addURL(URL url) {
        super.addURL(url);
    }

    public List<URL> getSources() {
        return new ArrayList<>(List.of(this.getURLs()));
    }

    public Class<?> getLoadedClass(String name) {
        return this.findLoadedClass(name);
    }

    public String remapClassName(String name) {
        return this.classNameTransformer == null ? name : this.classNameTransformer.remapClassName(name);
    }

    public String unmapClassName(String name) {
        return this.classNameTransformer == null ? name : this.classNameTransformer.unmapClassName(name);
    }

    public URLConnection findCodeSourceConnectionFor(final String name) {
        try {
            final URL url = this.findResource(name);
            if (url == null) {
                return null;
            }
            return url.openConnection();
        } catch (Exception e) {
            // De-escalated from old LCL, this used to throw a RuntimeException wrapping an IOException
            Bouncepad.logger().warn("Couldn't find CodeSource connection for {}: {}", name, e.getMessage());
            return null;
        }
    }

    /**
     * @see URLClassLoader#getAndVerifyPackage(String, Manifest, URL)
     */
    public Package getAndVerifyPackage(String packageName, Manifest manifest, URL codeSourceURL) {
        Package pkg = this.getDefinedPackage(packageName);
        if (pkg == null) {
            pkg = this.parent.getDefinedPackage(packageName);
        }
        if (pkg != null) {
            if (pkg.isSealed()) {
                if (!pkg.isSealed(codeSourceURL)) {
                    throw new SecurityException("Sealing violation: package " + packageName + " is sealed.");
                }
            } else if (manifest != null && isSealed(packageName, manifest)) {
                throw new SecurityException("Sealing violation: package " + packageName + " already loaded.");
            }
        } else {
            return this.definePackage(packageName, manifest != null ? manifest : new Manifest(), codeSourceURL);
        }
        return pkg;
    }

    /**
     * @see URLClassLoader#isSealed(String, Manifest) 
     */
    public boolean isSealed(String packageName, Manifest manifest) {
        var path = packageName.replace('.', '/').concat("/");
        var attr = SharedSecrets.javaUtilJarAccess().getTrustedAttributes(manifest, path);
        String sealed = null;
        if (attr != null) {
            sealed = attr.getValue(Attributes.Name.SEALED);
        }
        if (sealed == null) {
            if ((attr = manifest.getMainAttributes()) != null) {
                sealed = attr.getValue(Attributes.Name.SEALED);
            }
        }
        return Boolean.parseBoolean(sealed);
    }

    // Keep binary compatibility
    public byte[] getClassBytes(String name) {
        throw new UnsupportedOperationException("LaunchClassLoader no longer offers class to bytes helper.");
    }

    // Keep binary compatibility
    public void clearNegativeEntries(Set<String> entries) {
        throw new UnsupportedOperationException("LaunchClassLoader no longer offers negative entries.");
    }

    private byte[] getJavaClassBytes(String name) throws IOException {
        var classPath = name.replace('.', '/') + ".class";
        var resourceUrl = findResource(classPath);
        var connection = resourceUrl == null ? null : resourceUrl.openConnection();
        if (connection == null) {
            ClassLoader platform = Bouncepad.platformClassLoader();
            if (platform != null) {
                final URL platformUrl = platform.getResource(classPath);
                if (platformUrl != null) {
                    connection = platformUrl.openConnection();
                }
            } else {
                final URL parentUrl = this.getResource(classPath);
                if (parentUrl != null) {
                    connection = parentUrl.openConnection();
                }
            }
        }
        if (connection == null) {
            return null;
        }
        byte[] contents;
        try (var is = connection.getInputStream()) {
            contents = is.readAllBytes();
        }
        return contents;
    }

}
