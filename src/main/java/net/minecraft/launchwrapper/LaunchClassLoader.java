package net.minecraft.launchwrapper;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Attributes;
import java.util.jar.Attributes.Name;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

public abstract class LaunchClassLoader extends URLClassLoader {

    public static final int BUFFER_SIZE = 1 << 12;

    private List<URL> sources;
    private ClassLoader parent = getClass().getClassLoader();
    private final List<ClassLoader> children = new ArrayList<>();
    private ClassLoader from = null;
    private static final Method MD_FIND_CLASS;
    public static boolean childLoadingEnabled = false;

    static {
        Method mdFind;
        try {
            mdFind = ClassLoader.class.getDeclaredMethod("findClass", String.class);
            mdFind.trySetAccessible();
        } catch (Throwable e) {
            mdFind = null;
        }
        MD_FIND_CLASS = mdFind;
    }

    private List<IClassTransformer> transformers = new ArrayList<>(2);
    private Set<IClassTransformer> superTransformers = ConcurrentHashMap.newKeySet();
    private Map<String, Class<?>> cachedClasses = new ConcurrentHashMap<>();
    private Set<String> invalidClasses = new HashSet<>(1000);

    private Set<String> classLoaderExceptions = new HashSet<>();
    private Set<String> transformerExceptions = new HashSet<>();
    private Map<String,byte[]> resourceCache = new ConcurrentHashMap<>(1000);
    private Set<String> negativeResourceCache = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private IClassNameTransformer renameTransformer;

    private final ThreadLocal<byte[]> loadBuffer = new ThreadLocal<>();

    private static final String[] RESERVED_NAMES = {"CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3",
                                                    "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"};

    private static final boolean DEBUG = Boolean.parseBoolean(System.getProperty("legacy.debugClassLoading", "false"));
    private static final boolean DEBUG_FINER = DEBUG && Boolean.parseBoolean(System.getProperty("legacy.debugClassLoadingFiner", "false"));
    private static final boolean DEBUG_SAVE = DEBUG && Boolean.parseBoolean(System.getProperty("legacy.debugClassLoadingSave", "false"));
    private static File tempFolder = null;

    public LaunchClassLoader(URL[] sources) {
        super(sources, null);
        this.sources = new ArrayList<>(List.of(sources));

        // classloader exclusions
        addClassLoaderExclusion("java.");
        addClassLoaderExclusion("javax."); // Scripting Module
        addClassLoaderExclusion("jdk.dynalink."); // Nashorn
        addClassLoaderExclusion("sun.");
        addClassLoaderExclusion("org.lwjgl.");
        addClassLoaderExclusion("org.lwjgl3.");
        addClassLoaderExclusion("org.apache.logging.");
        addClassLoaderExclusion("com.sun.");
        addClassLoaderExclusion("it.unimi.dsi.fastutil.");
        addClassLoaderExclusion("net.minecraft.launchwrapper.");

        // transformer exclusions
        addTransformerExclusion("javax.");
        addTransformerExclusion("org.objectweb.asm.");
        addTransformerExclusion("com.google.common.");
        
        registerSuperTransformer("net.minecraft.launchwrapper.ASMVersionUpper");

        if (DEBUG_SAVE) {
            int x = 1;
            tempFolder = new File(Launch.minecraftHome, "CLASSLOADER_TEMP");
            while (tempFolder.exists() && x <= 10) {
                tempFolder = new File(Launch.minecraftHome, "CLASSLOADER_TEMP" + x++);
            }

            if (tempFolder.exists()) {
                LogWrapper.info("DEBUG_SAVE enabled, but 10 temp directories already exist, clean them and try again.");
                tempFolder = null;
            } else {
                LogWrapper.info("DEBUG_SAVE Enabled, saving all classes to \"%s\"", tempFolder.getAbsolutePath().replace('\\', '/'));
                tempFolder.mkdirs();
            }
        }
    }

    private static final Map<String, File> PLUGIN_CLASS_MAP = new ConcurrentHashMap<>();
    private static volatile boolean pluginsScanned = false;

    private void scanPlugins() {
        if (pluginsScanned) return;
        synchronized (PLUGIN_CLASS_MAP) {
            if (pluginsScanned) return;
            File pluginDir = new File(Launch.minecraftHome, "plugins");
            if (pluginDir.exists() && pluginDir.isDirectory()) {
                File[] jars = pluginDir.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".jar"));
                if (jars != null) {
                    for (File jar : jars) {
                        try (JarFile jf = new JarFile(jar)) {
                            if (jf.getEntry("plugin.yml") == null) {
                                continue;
                            }
                            jf.stream().forEach(entry -> {
                                if (entry.getName().endsWith(".class")) {
                                    String path = entry.getName();
                                    String className = path.substring(0, path.length() - 6).replace('/', '.');
                                    PLUGIN_CLASS_MAP.putIfAbsent(className, jar);
                                }
                            });
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
            pluginsScanned = true;
        }
    }

    public void registerTransformer(String transformerClassName) {
        try {
            IClassTransformer transformer = (IClassTransformer) loadClass(transformerClassName).newInstance();
            transformers.add(transformer);
            if (transformer instanceof IClassNameTransformer && renameTransformer == null) {
                renameTransformer = (IClassNameTransformer) transformer;
            }
        } catch (Exception e) {
            LogWrapper.log(Level.ERROR, e, "A critical problem occurred registering the ASM transformer class %s", transformerClassName);
        }
    }

    public void registerSuperTransformer(String transformerClassName) {
        try {
            IClassTransformer transformer = (IClassTransformer) loadClass(transformerClassName).newInstance();
            superTransformers.add(transformer);
            if (transformer instanceof IClassNameTransformer && renameTransformer == null) {
                renameTransformer = (IClassNameTransformer) transformer;
            }
        } catch (Exception e) {
            LogWrapper.log(Level.ERROR, e, "A critical problem occurred registering the super ASM transformer class %s", transformerClassName);
        }
    }

    public void unRegisterSuperTransformer(IClassTransformer transformer) {
        try {
            superTransformers.remove(transformer);
        } catch (Exception e) {
            LogWrapper.log(Level.ERROR, e, "A critical problem occurred unregistering the super ASM transformer class %s", transformer.getClass().getName());
        }
    }

    public void addChild(ClassLoader child) {
        children.add(child);
    }

    @Override
    public Class<?> findClass(final String name) throws ClassNotFoundException {
        if (this.equals(from)) return null;
        if (invalidClasses.contains(name)) {
            throw new ClassNotFoundException(name);
        }

        for (final String exception : classLoaderExceptions) {
            if (name.startsWith(exception)) {
                return parent.loadClass(name);
            }
        }

        if (cachedClasses.containsKey(name)) {
            return cachedClasses.get(name);
        }

        try {
            final String transformedName = transformName(name);
            if (cachedClasses.containsKey(transformedName)) {
                return cachedClasses.get(transformedName);
            }

            final String untransformedName = untransformName(name);

            final int lastDot = untransformedName.lastIndexOf('.');
            final String packageName = lastDot == -1 ? "" : untransformedName.substring(0, lastDot);
            final String fileName = untransformedName.replace('.', '/').concat(".class");
            URLConnection urlConnection = findCodeSourceConnectionFor(fileName);

            CodeSigner[] signers = null;

            if (urlConnection == null) {
                scanPlugins();
                if (PLUGIN_CLASS_MAP.containsKey(untransformedName)) {
                    throw new ClassNotFoundException(name);
                }
            }

            if (lastDot > -1 && !untransformedName.startsWith("net.minecraft.")) {
                if (urlConnection instanceof JarURLConnection jarURLConnection) {
                    final JarFile jarFile = jarURLConnection.getJarFile();

                    if (jarFile != null && jarFile.getManifest() != null) {
                        final Manifest manifest = jarFile.getManifest();
                        final JarEntry entry = jarFile.getJarEntry(fileName);

                        Package pkg = getPackage(packageName);
                        getClassBytes(untransformedName);
                        signers = entry.getCodeSigners();
                        if (pkg == null) {
                            pkg = definePackage(packageName, manifest, jarURLConnection.getJarFileURL());
                        } else {
                            if (pkg.isSealed() && !pkg.isSealed(jarURLConnection.getJarFileURL())) {
                                LogWrapper.severe("The jar file %s is trying to seal already secured path %s", jarFile.getName(), packageName);
                            } else if (isSealed(packageName, manifest)) {
                                LogWrapper.severe("The jar file %s has a security seal for path %s, but that path is defined and not secure", jarFile.getName(), packageName);
                            }
                        }
                    }
                } else {
                    Package pkg = getPackage(packageName);
                    if (pkg == null) {
                        pkg = definePackage(packageName, null, null, null, null, null, null, null);
                    } else if (pkg.isSealed()) {
                        LogWrapper.severe("The URL %s is defining elements for sealed path %s", urlConnection.getURL(), packageName);
                    }
                }
            }

            byte[] transformedClass;

            for (final String exception : transformerExceptions) {
                if (name.startsWith(exception)) {
                    transformedClass = getClassBytes(name);
                    for (final IClassTransformer transformer : superTransformers) {
                        transformedClass = transformer.transform(name, transformedName, transformedClass);
                    }
                    final CodeSource codeSource = urlConnection == null ? null : new CodeSource(urlConnection.getURL(), signers);
                    final Class<?> clazz = super.defineClass(name, transformedClass, 0, transformedClass.length, codeSource);
                    cachedClasses.put(name, clazz);
                    if (DEBUG_SAVE) {
                        saveTransformedClass(transformedClass, transformedName);
                    }
                    return clazz;
                }
            }

            transformedClass = runTransformers(untransformedName, transformedName, getClassBytes(untransformedName));
            if (DEBUG_SAVE) {
                saveTransformedClass(transformedClass, transformedName);
            }

            final CodeSource codeSource = urlConnection == null ? null : new CodeSource(urlConnection.getURL(), signers);
            final Class<?> clazz = defineClass(transformedName, transformedClass, 0, transformedClass.length, codeSource);
            cachedClasses.put(transformedName, clazz);
            return clazz;
        } catch (Throwable e) {
            boolean hasChildren = !children.isEmpty();
            if (childLoadingEnabled && hasChildren) {
                from = this;
                for (ClassLoader child : children) {
                    final String transformedName = transformName(name);

                    try {
                        Class<?> classe = (Class<?>) MD_FIND_CLASS.invoke(child, transformedName);
                        if (classe != null) {
                            cachedClasses.put(name, classe);
                            from = null;
                            return classe;
                        }
                    } catch (Exception e1) {
                        from = null;
                    }

                }
                from = null;
            }
            if (childLoadingEnabled || !hasChildren) invalidClasses.add(name);
            if (DEBUG) {
                LogWrapper.log(Level.ERROR, "Exception encountered attempting classloading of %s", name, e);
                if (DEBUG_FINER) {
                    LogManager.getLogger("LaunchWrapper").log(Level.ERROR, e.getStackTrace());
                }
            }
            throw new ClassNotFoundException(name, e);
        }
    }

    private void saveTransformedClass(final byte[] data, final String transformedName) {
        if (tempFolder == null || data == null) {
            return;
        }

        final File outFile = new File(tempFolder, transformedName.replace('.', File.separatorChar) + ".class");
        final File outDir = outFile.getParentFile();

        if (!outDir.exists()) {
            outDir.mkdirs();
        }

        if (outFile.exists()) {
            outFile.delete();
        }

        try {
            LogWrapper.fine("Saving transformed class \"%s\" to \"%s\"", transformedName, outFile.getAbsolutePath().replace('\\', '/'));

            final OutputStream output = new FileOutputStream(outFile);
            output.write(data);
            output.close();
        } catch (IOException ex) {
            LogWrapper.log(Level.WARN, ex, "Could not save transformed class \"%s\"", transformedName);
        }
    }

    private String untransformName(final String name) {
        if (renameTransformer != null) {
            return renameTransformer.unmapClassName(name);
        }

        return name;
    }

    private String transformName(final String name) {
        if (renameTransformer != null) {
            return renameTransformer.remapClassName(name);
        }

        return name;
    }

    private boolean isSealed(final String path, final Manifest manifest) {
        Attributes attributes = manifest.getAttributes(path);
        String sealed = null;
        if (attributes != null) {
            sealed = attributes.getValue(Name.SEALED);
        }

        if (sealed == null) {
            attributes = manifest.getMainAttributes();
            if (attributes != null) {
                sealed = attributes.getValue(Name.SEALED);
            }
        }
        return "true".equalsIgnoreCase(sealed);
    }

    private URLConnection findCodeSourceConnectionFor(final String name) {
        final URL resource = findResource(name);
        if (resource != null) {
            try {
                return resource.openConnection();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        return null;
    }

    private byte[] runTransformers(final String name, final String transformedName, byte[] basicClass) {
        if (DEBUG_FINER) {
            LogWrapper.finest("Beginning transform of {%s (%s)} Start Length: %d", name, transformedName, (basicClass == null ? 0 : basicClass.length));
            for (final IClassTransformer transformer : transformers) {
                final String transName = transformer.getClass().getName();
                LogWrapper.finest("Before Transformer {%s (%s)} %s: %d", name, transformedName, transName, (basicClass == null ? 0 : basicClass.length));
                basicClass = transformer.transform(name, transformedName, basicClass);
                LogWrapper.finest("After  Transformer {%s (%s)} %s: %d", name, transformedName, transName, (basicClass == null ? 0 : basicClass.length));
            }
            for (final IClassTransformer transformer : superTransformers) {
                final String transName = transformer.getClass().getName();
                LogWrapper.finest("Before super Transformer {%s (%s)} %s: %d", name, transformedName, transName, (basicClass == null ? 0 : basicClass.length));
                basicClass = transformer.transform(name, transformedName, basicClass);
                LogWrapper.finest("After  super Transformer {%s (%s)} %s: %d", name, transformedName, transName, (basicClass == null ? 0 : basicClass.length));
            }
            LogWrapper.finest("Ending transform of {%s (%s)} Start Length: %d", name, transformedName, (basicClass == null ? 0 : basicClass.length));
        } else {
            for (final IClassTransformer transformer : transformers) {
                basicClass = transformer.transform(name, transformedName, basicClass);
            }
            for (final IClassTransformer transformer : superTransformers) {
                basicClass = transformer.transform(name, transformedName, basicClass);
            }
        }
        basicClass = new ASMVersionUpper().transform(name, transformedName, basicClass);
        return basicClass;
    }

    @Override
    public void addURL(final URL url) {
        super.addURL(url);
        sources.add(url);
    }

    public List<URL> getSources() {
        return sources;
    }

    private byte[] readFully(InputStream stream) {
        try {
            byte[] buffer = getOrCreateBuffer();

            int read;
            int totalLength = 0;
            while ((read = stream.read(buffer, totalLength, buffer.length - totalLength)) != -1) {
                totalLength += read;

                // Extend our buffer
                if (totalLength >= buffer.length - 1) {
                    byte[] newBuffer = new byte[buffer.length + BUFFER_SIZE];
                    System.arraycopy(buffer, 0, newBuffer, 0, buffer.length);
                    buffer = newBuffer;
                }
            }

            final byte[] result = new byte[totalLength];
            System.arraycopy(buffer, 0, result, 0, totalLength);
            return result;
        } catch (Throwable t) {
            LogWrapper.log(Level.WARN, t, "Problem loading class");
            return new byte[0];
        }
    }

    private byte[] getOrCreateBuffer() {
        byte[] buffer = loadBuffer.get();
        if (buffer == null) {
            loadBuffer.set(new byte[BUFFER_SIZE]);
            buffer = loadBuffer.get();
        }
        return buffer;
    }

    public List<IClassTransformer> getTransformers() {
        return Collections.unmodifiableList(transformers);
    }

    public List<IClassTransformer> getSuperTransformers() {
        return superTransformers.stream().toList();
    }

    public void addClassLoaderExclusion(String toExclude) {
        classLoaderExceptions.add(toExclude);
    }

    public void addTransformerExclusion(String toExclude) {
        transformerExceptions.add(toExclude);
    }
    


    public byte[] getClassBytes(String name) throws IOException {
        if (negativeResourceCache.contains(name)) {
            return null;
        } else if (resourceCache.containsKey(name)) {
            return resourceCache.get(name);
        }
        if (name.indexOf('.') == -1) {
            for (final String reservedName : RESERVED_NAMES) {
                if (name.toUpperCase(Locale.ENGLISH).startsWith(reservedName)) {
                    final byte[] data = getClassBytes("_" + name);
                    if (data != null) {
                        resourceCache.put(name, data);
                        return data;
                    }
                }
            }
        }

        InputStream classStream = null;
        try {
            final String resourcePath = name.replace('.', '/').concat(".class");
            final URL classResource = findResource(resourcePath);

            if (classResource == null) {
                scanPlugins();
                File pluginJar = PLUGIN_CLASS_MAP.get(name);
                if (pluginJar != null) {
                    InputStream jarStream = null;
                    try (JarFile jf = new JarFile(pluginJar)) {
                        JarEntry entry = jf.getJarEntry(name.replace('.', '/') + ".class");
                        if (entry != null) {
                            jarStream = jf.getInputStream(entry);
                            final byte[] data = readFully(jarStream);
                            resourceCache.put(name, data);
                            if (DEBUG) LogWrapper.log(Level.DEBUG, "Loaded plugin bytes for Mixin: %s from %s", name, pluginJar.getName());
                            return data;
                        }
                    } catch (Exception ignored) {
                    } finally {
                        closeSilently(jarStream);
                    }
                }
                if (DEBUG) LogWrapper.log(Level.DEBUG,"Failed to find class resource %s", resourcePath);
                negativeResourceCache.add(name);
                return null;
            }
            classStream = classResource.openStream();

            if (DEBUG) LogWrapper.log(Level.DEBUG, "Loading class %s from resource %s", name, classResource.toString());
            final byte[] data = readFully(classStream);
            resourceCache.put(name, data);
            return data;
        } finally {
            closeSilently(classStream);
        }
    }

    private static void closeSilently(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
            }
        }
    }

    public boolean isClassLoaded(String name) {
        return findLoadedClass(name) != null;
    }

    public void clearNegativeEntries(Set<String> entriesToClear) {
        negativeResourceCache.removeAll(entriesToClear);
    }

    public Class<?> defineClass(String name, byte[] data) {
        return this.defineClass(name, data, 0, data.length);
    }
}
