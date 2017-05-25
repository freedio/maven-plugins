package com.coradec.maven;

import static java.util.stream.Collectors.*;

import com.coradec.coracore.annotation.Component;
import com.coradec.coracore.annotation.Implementation;
import com.coradec.coracore.annotation.Inject;
import com.coradec.coracore.annotation.Nullable;
import com.coradec.coracore.model.Tuple;
import com.coradec.coracore.trouble.MultiException;
import com.coradec.coracore.util.ClassUtil;
import com.coradec.coracore.util.StringUtil;
import com.coradec.corajet.cldr.CarClassLoader;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactNotFoundException;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Attributes.Name;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * ​​The comprehensive archive (CAR) packager maven plugin
 */
@SuppressWarnings("ClassHasNoToStringMethod")
@Mojo(name = "car")
@Execute(phase = LifecyclePhase.PACKAGE, goal = "car")
public class Carchiver extends AbstractMojo {

    /** Manifest name of the dependencies attribute. */
    private static final Name NAME_DEPENDENCIES = new Name("Dependencies");
    /** Manifest name of the archiver version attribute. */
    private static final Name NAME_ARCHIVER = new Name("Archiver-Version");

    /** The class signature of the @Inject annotation. */
    private static final String INJECT_DESC = ClassUtil.signatureOf(Inject.class);

    /** The current Maven session. */
    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    /** The current Maven project. */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /** Whether test dependencies should be included. */
    @Parameter(defaultValue = "false", readonly = true)
    private boolean includeTestDependencies;

    /** Directory containing the classes and resource files that should be packaged into the JAR. */
    @Parameter(defaultValue = "${project.build.outputDirectory}")
    private File classesDirectory;

    /** Directory containing the generated JAR. */
    @Parameter(defaultValue = "${project.build.directory}", required = true)
    private File outputDirectory;

    /** Name of the generated JAR. */
    @Parameter(defaultValue = "${project.build.finalName}", readonly = true, required = true)
    private String outputFile;

    /** The main class of the application. */
    @Parameter
    private String mainClass;

    /** The CAR loader package.  Everything in this package will be copied into the target JAR. */
    @Parameter(defaultValue = "com.coradec.corajet.cldr", readonly = true)
    private String carLoaderPackage;

    /** The CAR loader class (the actual main class as per manifest). */
    @Parameter(defaultValue = "com.coradec.corajet.cldr.CarLoader", readonly = true)
    private String carLoaderClass;

    /** The CAR loader artifact to use. */
    @Parameter
    private Map<String, String> carLoader;

    /** Whether dependencies should be included also in non-executable JARs. */
    @Parameter(defaultValue = "false", required = true)
    private boolean alwaysIncludeDependencies;

    /** Location of the local repository. */
    @Parameter(defaultValue = "${localRepository}", required = true, readonly = true)
    private ArtifactRepository localRepository;

    /** A list of repositories in which to look for artifacts and dependencies. */
    @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
    @Parameter(defaultValue = "${project.remoteArtifactRepositories}", required = true, readonly
            = true)
    private List<ArtifactRepository> remoteRepositories;

    /** Temporary directory for collecting resources. */
    private final String tempDirectory = System.getProperty("java.io.tmpdir");

    /** The name of the output file as a JAR. */
    private String outputJar;

    /** The boot loader manager. */
    private BootLoaderManager bootLoader;

    /* The list of all repositories, local first. */
    private ArrayList<ArtifactRepository> repos = new ArrayList<>();

    private final Set<String> implementations = new HashSet<>();
    private final Set<String> components = new HashSet<>();

    /**
     * Executes the mojo which creates the comprehensive archive.
     */
    @Override public void execute() throws MojoExecutionException, MojoFailureException {
        if (remoteRepositories != null) repos.addAll(remoteRepositories);
        repos.add(0, localRepository);
        final Log log = getLog();
        outputJar = outputFile + ".jar";
        try {
            if (isExecutable()) {
                Artifact carLoader = createCarLoader();
                bootLoader = createBootLoader(carLoader);
            }
            final Set<Artifact> dependencies = collectDependencies();
            final String classPath = createClassPath(dependencies);
            final String dependencyList = formatDependencies(dependencies);
            final Manifest manifest = createManifest(classPath, dependencyList);
            createCar(manifest, dependencies);
        }
        catch (ArtifactNotFoundException e) {
            throw new MojoExecutionException("Failed to find an artifact required in the build", e);
        }
        catch (MalformedURLException e) {
            throw new MojoExecutionException("Internal mojo failure", e);
        }
        catch (IOException e) {
            throw new MojoExecutionException("Unrecoverable input/output problem occurred", e);
        }
    }

    /**
     * Creates the final CAR file using the specified manifest and dependencies.
     *
     * @param manifest     the JAR manifest base.
     * @param dependencies the dependencies to add to the archive.
     * @throws IOException if either the archive failed to be written, or any dependencies could not
     *                     be read.
     */
    private void createCar(final Manifest manifest, final Set<Artifact> dependencies)
            throws IOException {
        try (JarOutputStream out = new JarOutputStream(
                new FileOutputStream(new File(tempDirectory, outputJar)), manifest)) {
            if (isExecutable()) {
                bootLoader.insert(out);
                addPackedProjectArtifact(out);
                for (final Artifact dependency : dependencies) {
                    addDependency(out, dependency);
                }
            } else addProjectArtifact(out);
        }
        final String outputDir = outputDirectory.getPath();
        finalizeJar(Paths.get(tempDirectory, outputJar), Paths.get(outputDir, outputJar));
    }

    /**
     * Moves the JAR from its temporary location to its final location.  Reads the original manifest
     * and registers the implementation and component classes as well as the injection points.
     *
     * @param from the temporary location.
     * @param to   the final location.
     */
    private void finalizeJar(final Path from, final Path to) throws IOException {
        try (JarInputStream in = new JarInputStream(
                Files.newInputStream(from, StandardOpenOption.READ))) {
            final Manifest manifest = in.getManifest();
            final Attributes mainAttributes = manifest.getMainAttributes();
            String x = mainAttributes.getValue(CarClassLoader.PROP_COMPONENT_CLASSES);
            final StringBuilder manifestComponents = new StringBuilder();
            if (x != null) manifestComponents.append(x);
            if (!components.isEmpty()) {
                for (final String component : components)
                    manifestComponents.append(' ').append(component);
                mainAttributes.put(new Name(CarClassLoader.PROP_COMPONENT_CLASSES),
                        manifestComponents.toString().trim());
            }
            x = mainAttributes.getValue(CarClassLoader.PROP_IMPLEMENTATION_CLASSES);
            final StringBuilder manifestImplementations = new StringBuilder();
            if (x != null) manifestImplementations.append(x);
            if (!implementations.isEmpty()) {
                for (final String implementation : implementations)
                    manifestImplementations.append(' ').append(implementation);
                mainAttributes.put(new Name(CarClassLoader.PROP_IMPLEMENTATION_CLASSES),
                        manifestImplementations.toString().trim());
            }
            getLog().info(String.format("Final manifest: %s", StringUtil.toString(manifest)));
            try (JarOutputStream out = new JarOutputStream(
                    Files.newOutputStream(to, StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING), manifest)) {
                for (JarEntry entry = in.getNextJarEntry();
                     entry != null;
                     entry = in.getNextJarEntry()) {
                    out.putNextEntry(entry);
                    copy(in, out);
                }
            }
        }
    }

    /**
     * Adds the classes of the current project, wrapped into its own JAR archive, as a
     * pseudo-dependency to the specified archive.
     *
     * @param out the archive.
     * @throws IOException if the archive failed to be written.
     */
    private void addPackedProjectArtifact(final JarOutputStream out) throws IOException {
        out.putNextEntry(new JarEntry(archiveFor(project.getArtifact())));
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(65536);
        Manifest manifest = new Manifest();
        try (JarOutputStream jar = new JarOutputStream(buffer, manifest)) {
            addProjectArtifact(jar);
        }
        out.write(buffer.toByteArray());
    }

    /**
     * Adds the classes of the current project to the archive.
     *
     * @param out the archive.
     * @throws IOException if the archive failed to be written.
     */
    private void addProjectArtifact(final JarOutputStream out) throws IOException {
        if (classesDirectory != null) {
            final List<Exception> problems = new ArrayList<>();
            final Log log = getLog();
            final String base = classesDirectory.getPath();
            final int baseLength = base.length() + 1;
            Files.walk(Paths.get(base))
                 .map(path -> new Tuple(path, path.toString()))
                 .filter(tuple -> ((String)tuple.get(1)).length() >= baseLength &&
                                  !Files.isDirectory((Path)tuple.get(0)))
                 .forEach(tuple -> {
                     try {
                         addDependency(out, ((String)tuple.get(1)).substring(baseLength),
                                 (Path)tuple.get(0));
                     }
                     catch (IOException e) {
                         problems.add(e);
                     }
                 });
            switch (problems.size()) {
                case 0:
                    return;
                case 1:
                    if (problems.get(0) instanceof IOException) throw (IOException)problems.get(0);
                    throw new IOException(problems.get(0));
                default:
                    throw new IOException(new MultiException(problems));
            }
        }
    }

    /**
     * Adds the dependency at the specified path as a JAR entry with the specified name to the
     * archive.
     *
     * @param out    the archive.
     * @param name   the name of the entry.
     * @param source the source of the entry.
     * @throws IOException if the archive failed to be written.
     */
    private void addDependency(final JarOutputStream out, final String name, final Path source)
            throws IOException {
        out.putNextEntry(new JarEntry(name));
        analyzeClass(source);
        Files.copy(source, out);
    }

    /**
     * Analyzes the specified file (only if it is a class) for injection points and a possible
     * implementation, updating the respective sections in the JAR manifest.
     *
     * @param source the path of the soruce file.
     */
    private void analyzeClass(final Path source) throws IOException {
        if (source.toString().endsWith(".class")) {
            ClassReader reader = new ClassReader(Files.readAllBytes(source));
            reader.accept(new ClassInspector(), 0);
        }
    }

    /**
     * Adds the specified artifact to the specified JAR output stream.
     *
     * @param out      the JAR output stream.
     * @param artifact the artifact to add.
     * @throws IOException if the output stream failed to be written.
     */
    private void addDependency(final JarOutputStream out, final Artifact artifact)
            throws IOException {
        getLog().info(
                String.format("Adding dependency \"%s\" (%s) to final JAR", referenceFor(artifact),
                        archiveFor(artifact)));
        final String downloadUrl = artifact.getDownloadUrl();
        final URL artifactURL = new URL(downloadUrl);
        if (downloadUrl.endsWith(".jar")) scanInjections(artifactURL);
        try (InputStream in = artifactURL.openStream()) {
            out.putNextEntry(new JarEntry(archiveFor(artifact)));
            copy(in, out);
        }
    }

    /**
     * Opens the manifest of the specified jar and extracts the components and implementations.
     *
     * @param jarURL the URL of the jar.
     */
    private void scanInjections(final URL jarURL) throws IOException {
        try (JarInputStream in = new JarInputStream(jarURL.openStream())) {
            final Manifest manifest = in.getManifest();
            final Attributes mainAttributes = manifest.getMainAttributes();
            final String impls =
                    mainAttributes.getValue(CarClassLoader.PROP_IMPLEMENTATION_CLASSES);
            if (impls != null && !impls.isEmpty()) //
                for (String impl : impls.split("\\s+"))
                    implementations.add(impl);
            final String comps = mainAttributes.getValue(CarClassLoader.PROP_COMPONENT_CLASSES);
            if (comps != null && !comps.isEmpty()) //
                for (final String comp : comps.split("\\s+"))
                    components.add(comp);
        }
    }

    /**
     * Copies the contents of the specified input stream to the specified output stream.
     *
     * @param in  the input stream.
     * @param out the output stream.
     * @throws IOException if something went wrong while reading or writing.
     */
    private void copy(final InputStream in, final OutputStream out) throws IOException {
        final ReadableByteChannel source = Channels.newChannel(in);
        final WritableByteChannel sink = Channels.newChannel(out);
        final ByteBuffer buffer = ByteBuffer.allocate(65536);
        buffer.clear();
        while (source.read(buffer) != -1) {
            buffer.flip();
            sink.write(buffer);
            buffer.compact();
        }
        buffer.flip();
        while (buffer.hasRemaining()) {
            sink.write(buffer);
            if (buffer.hasRemaining()) Thread.yield();
        }
    }

    /**
     * Creates the class path based on the specified set of dependencies.
     *
     * @param dependencies the dependencies.
     * @return the class path.
     */
    private String createClassPath(final Set<Artifact> dependencies) {
        String cp = dependencies.stream().map(this::archiveFor).collect(joining(" "));
        if (isExecutable()) cp += ' ' + archiveFor(project.getArtifact());
        return cp;
    }

    /**
     * Formats the specified list of dependencies into a String.
     *
     * @param dependencies the list of dependencies.
     * @return its String repreentation.
     */
    private String formatDependencies(final Set<Artifact> dependencies) {
        return dependencies.stream().map(this::referenceFor).collect(joining(" "));
    }

    /**
     * Creates the reference key for the specified artifact.
     *
     * @param a the artifact.
     * @return its reference key.
     */
    private String referenceFor(final Artifact a) {
        return Stream.of(a.getGroupId(), a.getArtifactId(), a.getType(), a.getClassifier(),
                a.getVersion()).filter(Objects::nonNull).collect(joining(":"));
    }

    /**
     * Returns the archive name of the specified artifact.
     *
     * @param a the artifact.
     * @return its archive name.
     */
    private String archiveFor(final Artifact a) {
        return String.format("%s-%s.%s", a.getArtifactId(), a.getVersion(), a.getType());
    }

    /**
     * Creates a JAR manifest with the specified class path and dependency section.
     *
     * @param classPath    the class path.
     * @param dependencies the dependency section.
     * @return a new JAR manifest.
     */
    private Manifest createManifest(final String classPath, final String dependencies) {
        Manifest result = new Manifest();
        final Attributes attributes = result.getMainAttributes();
        attributes.put(Name.MANIFEST_VERSION, "1.0");
        if (mainClass != null) {
            attributes.put(Name.MAIN_CLASS, carLoaderClass);
            attributes.put(new Name("Application"), mainClass);
            attributes.put(Name.CLASS_PATH, classPath);
        } else attributes.put(NAME_DEPENDENCIES, dependencies);
        attributes.put(NAME_ARCHIVER, "Coradec Carchiver");
        return result;
    }

    /**
     * Creates the CAR loader artifact.
     *
     * @return the CAR loader as an artifact.
     * @throws ArtifactNotFoundException if the CAR loader was not found.
     * @throws MalformedURLException     if the CAR loader has a weird URL.
     */
    private Artifact createCarLoader() throws ArtifactNotFoundException, MalformedURLException {
        final String groupId, artifactId, version, scope, type;
        if (carLoader == null) {
            groupId = "com.coradec.coradeck";
            artifactId = "corajet";
            version = "0.1";
            type = "jar";
        } else {
            groupId = carLoader.get("groupId");
            artifactId = carLoader.get("artifactId");
            version = carLoader.get("version");
            type = carLoader.computeIfAbsent("type", k -> "jar");
        }
        scope = "";
        final Artifact artifact = getArtifact(groupId, artifactId, version, scope, type);
        getLog().info(String.format("CAR loader from artifact %s", referenceFor(artifact)));
        return artifact;
    }

    /**
     * Creates an artifact (including its download URL from the closest repository) from the
     * specified attributes.
     *
     * @param groupId    the group ID.
     * @param artifactId the artifact ID.
     * @param version    the artifact version.
     * @param scope      the artifact scope (optional).
     * @param type       the artifact type / packaging.
     * @return a new artifact.
     * @throws ArtifactNotFoundException if the artifact could not be found in any repository.
     * @throws MalformedURLException     if the download URL is weird.
     */
    private Artifact getArtifact(final String groupId, final String artifactId,
                                 final String version, final @Nullable String scope,
                                 final String type)
            throws ArtifactNotFoundException, MalformedURLException {
        List<ArtifactRepository> repos = new ArrayList<>(remoteRepositories);
        repos.add(0, localRepository);
        Artifact result =
                new DefaultArtifact(groupId, artifactId, VersionRange.createFromVersion(version),
                        scope, type, "", new DefaultArtifactHandler(type));
        final ArtifactRepository repository = //
                repos.stream()
                     .filter(repo -> repo.pathOf(result) != null)
                     .findFirst()
                     .orElseThrow(
                             () -> new ArtifactNotFoundException("Not found in any repo", result));
        result.setRepository(repository);
        URL from = new URL(new URL(repository.getUrl()), repository.pathOf(result));
        final File location = new File(repository.getBasedir(), repository.pathOf(result));
        result.setFile(location);
        result.setDownloadUrl(from.toExternalForm());
        return result;
    }

    /**
     * Creates the boot loader manager.
     *
     * @param carLoader the car loader artifact.
     * @return the boot loader manager.
     */
    private BootLoaderManager createBootLoader(final Artifact carLoader) throws IOException {
        return new BootLoaderManager(carLoader);
    }

    /**
     * Collects the direct dependencies of the project (along with the transient dependencies, if
     * the project is going to be an executable JAR) and renders them into an artifact set (hence
     * each dependency will be ready to be located in the repositories, and duplicates are
     * automatically eliminated).
     *
     * @return the complete set of dependencies according to the type of project.
     * @throws IOException if some dependencies could not be examined due to I/O problems.
     */
    private Set<Artifact> collectDependencies() throws IOException {
        @SuppressWarnings("unchecked") final List<Dependency> dependencies =
                project.getDependencies();
        if (isExecutable()) dependencies.addAll(bootLoader.getDependencies());
        if (isExecutable()) collectTransientDependencies(dependencies);
        return dependencies.stream()
                           .filter(dependency -> includeTestDependencies ||
                                                 !"test".equalsIgnoreCase(dependency.getScope()))
                           .map(this::artifactOf)
                           .collect(toSet());
    }

    /**
     * Recursively collects the transient dependencies from all the specified dependencies into the
     * list itself.
     *
     * @param dependencies the list of initial and final dependencies.
     */
    private void collectTransientDependencies(final List<Dependency> dependencies)
            throws IOException {
        getLog().info(String.format("collectTransientDependencies: %s",
                StringUtil.toString(dependencies)));
        for (int i = 0; i < dependencies.size(); ++i) {
            final Dependency dependency = dependencies.get(i);
            URL location;
            final String downloadUrl = artifactOf(dependency).getDownloadUrl();
            try {
                location = new URL(downloadUrl);
            }
            catch (MalformedURLException e) {
                throw new MalformedURLException(String.format("Invalid URL: \"%s\"", downloadUrl));
            }
            JarInputStream in = new JarInputStream(location.openStream());
            final Manifest manifest = in.getManifest();
            if (manifest != null) dependencies.addAll(getDependencies(location, manifest));
        }
    }

    /**
     * Converts the specified dependency into an artifact, complete with download URL.
     *
     * @param dependency the dependency to convert.
     * @return the archive.
     */
    private Artifact artifactOf(final Dependency dependency) {
//        getLog().info(String.format("Version of %s = %s", dependency, dependency.getVersion()));
        return new DefaultArtifact(dependency.getGroupId(), dependency.getArtifactId(),
                VersionRange.createFromVersion(dependency.getVersion()), dependency.getScope(),
                dependency.getType(), dependency.getClassifier(),
                new DefaultArtifactHandler(dependency.getType()), dependency.isOptional()) {

            @Override public String getDownloadUrl() {
                return repos.stream()
                            .map(repo -> locateArtifact(repo, this))
                            .filter(Objects::nonNull)
                            .findAny()
                            .orElse(null);
            }
        };
    }

    /**
     * Returns the location of the specified artifact in the specified repository.
     *
     * @param repo     the repository.
     * @param artifact the artifact to look up.
     * @return the download URL of the archive.
     */
    private String locateArtifact(final ArtifactRepository repo, final Artifact artifact) {
        StringBuilder result = new StringBuilder(256);
        result.append(repo.getUrl());
        if (result.charAt(result.length() - 1) != '/') result.append('/');
        result.append(repo.pathOf(artifact));
        return result.toString();
    }

    /**
     * Extracts the list of dependencies specified in main attribute {@link #NAME_DEPENDENCIES} in
     * the specified manifest from the specified source.
     * <p>
     * If the manifest does not contain a dependencies specification, the list will be empty.
     *
     * @param source   the source (used in error messages only)
     * @param manifest the manifest.
     * @return a list of dependencies as specified in the manifest.
     */
    List<Dependency> getDependencies(final URL source, final Manifest manifest) {
        String depList = (String)manifest.getMainAttributes().get(NAME_DEPENDENCIES);
        List<Dependency> list = new ArrayList<>();
        if (depList != null && !depList.trim().isEmpty()) {
            for (String s : depList.split("\\s+")) {
                try {
                    Dependency dependency = dependencyOf(s);
                    list.add(dependency);
                }
                catch (IllegalArgumentException e) {
                    getLog().error(String.format(
                            "Invalid dependency reference \"%s\" in dependency list \"%s\" in " +
                            "manifest of package %s", s, depList, source));
                }
            }
        }
        return list;
    }

    /**
     * Decodes the specified dependency reference into a dependency.
     * @param dep the reference.
     * @return the dependency.
     * @throws IllegalArgumentException if the reference is ill-formatted.
     */
    private Dependency dependencyOf(String dep) throws IllegalArgumentException {
        String groupId = null, artifactId = null, version = null, packaging = null;
        String classifier = null, type = null;
        final String[] parts = dep.split(":");
        switch (parts.length) {
            case 1:
                artifactId = parts[0];
                break;
            case 5:
                classifier = parts[3];
            case 4:
                version = parts[parts.length - 1];
            case 3:
                packaging = parts[2];
            case 2:
                artifactId = parts[1];
                groupId = parts[0];
                type = packaging;
                break;
        }
        final Dependency result = new Dependency();
        result.setGroupId(groupId);
        result.setArtifactId(artifactId);
        result.setVersion(version);
        result.setClassifier(classifier);
        result.setType(type);
        return result;
    }

    /**
     * Reports whether we are building an executable CAR.
     *
     * @return {@code true} if the JAR being built is executable.
     */
    private boolean isExecutable() {
        return mainClass != null;
    }

    private Dependency getProjectArtifact() {
        final Dependency dependency = new Dependency();
        dependency.setGroupId(project.getGroupId());
        dependency.setArtifactId(project.getArtifactId());
        dependency.setVersion(project.getVersion());
        dependency.setType(project.getPackaging());
        return dependency;
    }

    /**
     * Handles the boot loader.
     */
    @SuppressWarnings("ClassHasNoToStringMethod")
    private class BootLoaderManager {

        private final JarInputStream stream;
        private final URL source;
        private Manifest manifest;

        BootLoaderManager(final Artifact carLoader) throws IOException {
            source = new URL(carLoader.getDownloadUrl());
            this.stream = new JarInputStream(source.openStream());
        }

        private Manifest getManifest() {
            if (this.manifest == null) manifest = stream.getManifest();
            return this.manifest;
        }

        private List<Dependency> getDependencies() {
            manifest = stream.getManifest();
            return Carchiver.this.getDependencies(source, manifest);
        }

        /**
         * Inserts the boot loader code into the specified jar.
         *
         * @param out the jar.
         */
        void insert(final JarOutputStream out) throws IOException {
            for (JarEntry entry = stream.getNextJarEntry();
                 entry != null;
                 entry = stream.getNextJarEntry()) {
                out.putNextEntry(entry);
                copy(stream, out);
            }
        }

    }

    /**
     * A class inspector used to build the component and implementation section of the manifest.
     */
    private class ClassInspector extends ClassVisitor {

        private String currentClassName;

        ClassInspector() {
            super(Opcodes.ASM5);
        }

        @Override public void visit(final int version, final int access, final String name,
                                    final String signature, final String superName,
                                    final String[] interfaces) {
            currentClassName = name.replace('/', '.');
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public AnnotationVisitor visitAnnotation(final String desc, final boolean visible) {
            final String annClassName = ClassUtil.toExternal(desc);
            if (annClassName.equals(Component.class.getName())) {
                components.add(currentClassName);
            } else if (annClassName.equals(Implementation.class.getName())) {
                implementations.add(currentClassName); // default
            } else {
                // others are uninteresting --- until at least one field is annotated with @Inject
            }
            return null;
        }

        @Override
        public FieldVisitor visitField(final int access, final String name, final String desc,
                                       final String signature, final Object value) {
            if (value == null) {
                getLog().debug(String.format("Detected static field (%s)%s", desc, name));
                return new ClassInspector.FieldInspector(null, name,
                        signature != null ? signature : desc);
            }
            return super.visitField(access, name, desc, signature, value);
        }

        private class FieldInspector extends FieldVisitor {

            private final String name;
            private final String type;

            FieldInspector(final FieldVisitor fv, final String name, final String type) {
                super(Opcodes.ASM5, fv);
                this.name = name;
                this.type = type;
            }

            @Override
            public AnnotationVisitor visitAnnotation(final String desc, final boolean visible) {
                if (desc.equals(INJECT_DESC) && visible) {
                    components.add(currentClassName);
                }
                return super.visitAnnotation(desc, visible);
            }

        }

    }

}
