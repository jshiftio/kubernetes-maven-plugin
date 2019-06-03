package io.jshift.maven.plugin.mojo.build;

import io.jshift.generator.api.GeneratorContext;
import io.jshift.kit.build.maven.MavenBuildContext;
import io.jshift.kit.build.service.docker.BuildService;
import io.jshift.kit.build.service.docker.DockerAccessFactory;
import io.jshift.kit.build.service.docker.ImageConfiguration;
import io.jshift.kit.build.service.docker.ImagePullManager;
import io.jshift.kit.build.service.docker.RegistryService;
import io.jshift.kit.build.service.docker.ServiceHub;
import io.jshift.kit.build.service.docker.ServiceHubFactory;
import io.jshift.kit.build.service.docker.access.DockerAccess;
import io.jshift.kit.build.service.docker.access.DockerAccessException;
import io.jshift.kit.build.service.docker.access.ExecException;
import io.jshift.kit.build.service.docker.access.log.LogOutputSpecFactory;
import io.jshift.kit.build.service.docker.auth.AuthConfigFactory;
import io.jshift.kit.build.service.docker.config.ConfigHelper;
import io.jshift.kit.build.service.docker.config.DockerMachineConfiguration;
import io.jshift.kit.build.service.docker.config.handler.ImageConfigResolver;
import io.jshift.kit.build.service.docker.helper.AnsiLogger;
import io.jshift.kit.build.service.docker.helper.ImageNameFormatter;
import io.jshift.kit.common.KitLogger;
import io.jshift.kit.common.util.EnvUtil;
import io.jshift.kit.common.util.ResourceUtil;
import io.jshift.kit.config.access.ClusterAccess;
import io.jshift.kit.config.access.ClusterConfiguration;
import io.jshift.kit.config.image.build.BuildConfiguration;
import io.jshift.kit.config.image.build.OpenShiftBuildStrategy;
import io.jshift.kit.config.image.build.RegistryAuthConfiguration;
import io.jshift.kit.config.resource.BuildRecreateMode;
import io.jshift.kit.config.resource.ProcessorConfig;
import io.jshift.kit.config.resource.ResourceConfig;
import io.jshift.kit.config.resource.RuntimeMode;
import io.jshift.kit.config.service.JshiftServiceHub;
import io.jshift.kit.profile.ProfileUtil;
import io.jshift.maven.enricher.api.EnricherContext;
import io.jshift.maven.enricher.api.MavenEnricherContext;
import io.jshift.maven.plugin.generator.GeneratorManager;
import org.apache.maven.archiver.MavenArchiveConfiguration;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.settings.Settings;
import org.apache.maven.shared.filtering.MavenFileFilter;
import org.apache.maven.shared.filtering.MavenReaderFilter;
import org.codehaus.plexus.PlexusConstants;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.context.Context;
import org.codehaus.plexus.context.ContextException;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.Contextualizable;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Builds the docker images configured for this project via a Docker or S2I binary build.
 *
 * @author roland
 * @since 16/03/16
 */
@Mojo(name = "build", defaultPhase = LifecyclePhase.PRE_INTEGRATION_TEST, requiresDependencyResolution = ResolutionScope.COMPILE)
public class BuildMojo extends AbstractMojo implements Contextualizable, ConfigHelper.Customizer {

    public static final String DMP_PLUGIN_DESCRIPTOR = "META-INF/maven/io.jshift/k8s-plugin";
    public static final String DOCKER_EXTRA_DIR = "docker-extra";

    // Key for indicating that a "start" goal has run
    public static final String CONTEXT_KEY_START_CALLED = "CONTEXT_KEY_DOCKER_START_CALLED";

    // Key holding the log dispatcher
    public static final String CONTEXT_KEY_LOG_DISPATCHER = "CONTEXT_KEY_DOCKER_LOG_DISPATCHER";

    // Key under which the build timestamp is stored so that other mojos can reuse it
    public static final String CONTEXT_KEY_BUILD_TIMESTAMP = "CONTEXT_KEY_BUILD_TIMESTAMP";

    // Filename for holding the build timestamp
    public static final String DOCKER_BUILD_TIMESTAMP = "docker/build.timestamp";

    @Parameter(property = "docker.apiVersion")
    private String apiVersion;

    // Current maven project
    @Parameter(defaultValue = "${project}", readonly = true)
    protected MavenProject project;

    // For verbose output
    @Parameter(property = "docker.verbose", defaultValue = "false")
    protected boolean verbose;

    // The date format to use when putting out logs
    @Parameter(property = "docker.logDate")
    private String logDate;

    // Log to stdout regardless if log files are configured or not
    @Parameter(property = "docker.logStdout", defaultValue = "false")
    private boolean logStdout;

    /**
     * URL to docker daemon
     */
    @Parameter(property = "docker.host")
    private String dockerHost;

    @Parameter(property = "docker.certPath")
    private String certPath;

    // Docker-machine configuration
    @Parameter
    private DockerMachineConfiguration machine;

    /**
     * Whether the usage of docker machine should be skipped competely
     */
    @Parameter(property = "docker.skip.machine", defaultValue = "false")
    private boolean skipMachine;

    // maximum connection to use in parallel for connecting the docker host
    @Parameter(property = "docker.maxConnections", defaultValue = "100")
    private int maxConnections;

    /**
     * Generator specific options. This is a generic prefix where the keys have the form
     * <code>&lt;generator-prefix&gt;-&lt;option&gt;</code>.
     */
    @Parameter
    private ProcessorConfig generator;

    /**
     * Enrichers used for enricher build objects
     */
    @Parameter
    private ProcessorConfig enricher;

    /**
     * Resource config for getting annotation and labels to be applied to enriched build objects
     */
    @Parameter
    private ResourceConfig resources;

    // To skip over the execution of the goal
    @Parameter(property = "fabric8.skip", defaultValue = "false")
    protected boolean skip;

    /**
     * Profile to use. A profile contains the enrichers and generators to
     * use as well as their configuration. Profiles are looked up
     * in the classpath and can be provided as yaml files.
     * <p>
     * However, any given enricher and or generator configuration overrides
     * the information provided by a profile.
     */
    @Parameter(property = "fabric8.profile")
    private String profile;

    /**
     * Folder where to find project specific files, e.g a custom profile
     */
    @Parameter(property = "fabric8.resourceDir", defaultValue = "${basedir}/src/main/fabric8")
    private File resourceDir;

    /**
     * Environment name where resources are placed. For example, if you set this property to dev and resourceDir is the default one, Fabric8 will look at src/main/fabric8/dev
     */
    @Parameter(property = "fabric8.environment")
    private String environment;

    @Parameter(property = "fabric8.skip.build.pom")
    private Boolean skipBuildPom;

    // Whether to use color
    @Parameter(property = "docker.useColor", defaultValue = "true")
    protected boolean useColor;

    /**
     * Whether to perform a Kubernetes build (i.e. against a vanilla Docker daemon) or
     * an OpenShift build (with a Docker build against the OpenShift API server.
     */
    @Parameter(property = "fabric8.mode")
    private RuntimeMode mode = RuntimeMode.DEFAULT;

    // Authentication information
    @Parameter
    private RegistryAuthConfiguration authConfig;

    /**
     * OpenShift build mode when an OpenShift build is performed.
     * Can be either "s2i" for an s2i binary build mode or "docker" for a binary
     * docker mode.
     */
    @Parameter(property = "fabric8.build.strategy")
    private OpenShiftBuildStrategy buildStrategy = OpenShiftBuildStrategy.s2i;

    /**
     * The S2I binary builder BuildConfig name suffix appended to the image name to avoid
     * clashing with the underlying BuildConfig for the Jenkins pipeline
     */
    @Parameter(property = "fabric8.s2i.buildNameSuffix", defaultValue = "-s2i")
    private String s2iBuildNameSuffix;

    /**
     * The name of pullSecret to be used to pull the base image in case pulling from a private
     * registry which requires authentication.
     */
    @Parameter(property = "fabric8.build.pullSecret", defaultValue = "pullsecret-fabric8")
    private String openshiftPullSecret;

    /**
     * Allow the ImageStream used in the S2I binary build to be used in standard
     * Kubernetes resources such as Deployment or StatefulSet.
     */
    @Parameter(property = "fabric8.s2i.imageStreamLookupPolicyLocal", defaultValue = "true")
    private boolean s2iImageStreamLookupPolicyLocal = true;

    /**
     * While creating a BuildConfig, By default, if the builder image specified in the
     * build configuration is available locally on the node, that image will be used.
     * <p>
     * ForcePull to override the local image and refresh it from the registry to which the image stream points.
     */
    @Parameter(property = "fabric8.build.forcePull", defaultValue = "false")
    private boolean forcePull = false;

    /**
     * Image configurations configured directly.
     */
    @Parameter
    private List<ImageConfiguration> images;

    /**
     * Whether to restrict operation to a single image. This can be either
     * the image or an alias name. It can also be comma separated list.
     * This parameter has to be set via the command line s system property.
     */
    @Parameter(property = "docker.filter")
    private String filter;

    @Component
    protected ServiceHubFactory serviceHubFactory;

    @Component
    protected DockerAccessFactory dockerAccessFactory;

    /**
     * Should we use the project's compile-time classpath to scan for additional enrichers/generators?
     */
    @Parameter(property = "fabric8.useProjectClasspath", defaultValue = "false")
    private boolean useProjectClasspath = false;

    /**
     * How to recreate the build config and/or image stream created by the build.
     * Only in effect when <code>mode == openshift</code> or mode is <code>auto</code>
     * and openshift is detected. If not set, existing
     * build config will not be recreated.
     * <p>
     * The possible values are:
     *
     * <ul>
     * <li><strong>buildConfig</strong> or <strong>bc</strong> :
     * Only the build config is recreated</li>
     * <li><strong>imageStream</strong> or <strong>is</strong> :
     * Only the image stream is recreated</li>
     * <li><strong>all</strong> : Both, build config and image stream are recreated</li>
     * <li><strong>none</strong> : Neither build config nor image stream is recreated</li>
     * </ul>
     */
    @Parameter(property = "fabric8.build.recreate", defaultValue = "none")
    private String buildRecreate;

    @Parameter(property = "docker.skip.build", defaultValue = "false")
    protected boolean skipBuild;

    // Default registry to use if no registry is specified
    @Parameter(property = "docker.registry")
    protected String registry;

    @Parameter
    private MavenArchiveConfiguration archive;

    @Component
    private MavenFileFilter mavenFileFilter;

    @Component
    private MavenReaderFilter mavenFilterReader;

    @Parameter
    private Map<String, String> buildArgs;

    @Component
    private MavenProjectHelper projectHelper;

    @Component
    protected RepositorySystem repositorySystem;

    @Parameter
    protected ClusterConfiguration access;

    // Current maven project
    @Parameter(property = "session")
    protected MavenSession session;

    // Settings holding authentication info
    @Parameter(defaultValue = "${settings}", readonly = true)
    protected Settings settings;

    @Parameter(property = "docker.source.dir", defaultValue="src/main/docker")
    private String sourceDirectory;

    @Parameter(property = "docker.target.dir", defaultValue="target/docker")
    private String outputDirectory;

    @Parameter( defaultValue = "${reactorProjects}", required = true, readonly = true )
    private List<MavenProject> reactorProjects;

    @Parameter(property = "docker.autoPull")
    protected String autoPull;

    @Parameter(property = "docker.imagePullPolicy")
    protected String imagePullPolicy;

    @Parameter(property = "docker.pull.registry")
    private String pullRegistry;

    // Handler for external configurations
    @Component
    protected ImageConfigResolver imageConfigResolver;

    /**
     * Skip extended authentication
     */
    @Parameter(property = "docker.skip.extendedAuth", defaultValue = "false")
    protected boolean skipExtendedAuth;

    // Access for creating OpenShift binary builds
    private ClusterAccess clusterAccess;

    // Images resolved with external image resolvers and hooks for subclass to
    // mangle the image configurations.
    private List<ImageConfiguration> resolvedImages;

    // The Fabric8 service hub
    JshiftServiceHub fabric8ServiceHub;

    // Mode which is resolved, also when 'auto' is set
    private RuntimeMode runtimeMode;

    protected KitLogger log;

    private String minimalApiVersion;

    // Handler dealing with authentication credentials
    private AuthConfigFactory authConfigFactory;


    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip || skipBuild) {
            return;
        }
        clusterAccess = new ClusterAccess(getClusterConfiguration());
        // Platform mode is already used in executeInternal()
        executeDockerBuild();
    }

    @Override
    public void contextualize(Context context) throws ContextException {
        authConfigFactory = new AuthConfigFactory((PlexusContainer) context.get(PlexusConstants.PLEXUS_KEY));
    }

    protected ClusterConfiguration getClusterConfiguration() {
        final ClusterConfiguration.Builder clusterConfigurationBuilder = new ClusterConfiguration.Builder(access);

        return clusterConfigurationBuilder.from(System.getProperties())
                .from(project.getProperties()).build();
    }

    /**
     * Get all images to use. Can be restricted via -Ddocker.filter to pick a one or more images.
     * The values are taken as comma separated list.
     *
     * @return list of image configuration to be use. Can be empty but never null.
     */
    protected List<ImageConfiguration> getResolvedImages() {
        return resolvedImages;
    }

    protected boolean isDockerAccessRequired() {
        return runtimeMode == RuntimeMode.kubernetes;
    }

    protected void executeInternal(ServiceHub hub) throws MojoExecutionException {
        if (skipBuild) {
            return;
        }
        try {
            if (shouldSkipBecauseOfPomPackaging()) {
                getLog().info("Disabling docker build for pom packaging");
                return;
            }
            if (getResolvedImages().size() == 0) {
                log.warn("No image build configuration found or detected");
            }

            // Build the Jshift service hub
            fabric8ServiceHub = new JshiftServiceHub.Builder()
                    .log(log)
                    .clusterAccess(clusterAccess)
                    .platformMode(mode)
                    .dockerServiceHub(hub)
                    .buildServiceConfig(getBuildServiceConfig())
                    .repositorySystem(repositorySystem)
                    .mavenProject(project)
                    .build();

            executeInternally(hub);

            fabric8ServiceHub.getBuildService().postProcess(getBuildServiceConfig());
        } catch (IOException exception) {
            throw new MojoExecutionException(exception.getMessage());
        }
    }

    private void executeInternally(ServiceHub hub) throws IOException, MojoExecutionException {
        if (skipBuild) {
            return;
        }

        // Check for build plugins
        executeBuildPlugins();

        // Iterate over all the ImageConfigurations and process one by one
        for (ImageConfiguration imageConfig : getResolvedImages()) {
            processImageConfig(hub, imageConfig);
        }
    }

    private boolean shouldSkipBecauseOfPomPackaging() {
        if (!project.getPackaging().equals("pom")) {
            // No pom packaging
            return false;
        }
        if (skipBuildPom != null) {
            // If configured take the config option
            return skipBuildPom;
        }

        // Not specified: Skip if no image with build configured, otherwise don't skip
        for (ImageConfiguration image : getResolvedImages()) {
            if (image.getBuildConfiguration() != null) {
                return false;
            }
        }
        return true;
    }

    protected void buildAndTag(ServiceHub hub, ImageConfiguration imageConfig)
            throws MojoExecutionException, DockerAccessException {

        try {
            // TODO need to refactor d-m-p to avoid this call
            EnvUtil.storeTimestamp(getBuildTimestampFile(), getBuildTimestamp());

            fabric8ServiceHub.getBuildService().build(imageConfig);

        } catch (Exception ex) {
            throw new MojoExecutionException("Failed to execute the build", ex);
        }
    }

    protected io.jshift.kit.config.service.BuildService.BuildServiceConfig getBuildServiceConfig() throws MojoExecutionException {
        return new io.jshift.kit.config.service.BuildService.BuildServiceConfig.Builder()
                .dockerBuildContext(getBuildContext())
                .dockerMavenBuildContext(createMojoParameters())
                .buildRecreateMode(BuildRecreateMode.fromParameter(buildRecreate))
                .openshiftBuildStrategy(buildStrategy)
                .openshiftPullSecret(openshiftPullSecret)
                .s2iBuildNameSuffix(s2iBuildNameSuffix)
                .s2iImageStreamLookupPolicyLocal(s2iImageStreamLookupPolicyLocal)
                .forcePullEnabled(forcePull)
                .imagePullManager(getImagePullManager(imagePullPolicy, autoPull))
                .buildDirectory(project.getBuild().getDirectory())
                .attacher((classifier, destFile) -> {
                    if (destFile.exists()) {
                        projectHelper.attachArtifact(project, "yml", classifier, destFile);
                    }
                })
                .build();
    }


    /**
     * Customization hook called by the base plugin.
     *
     * @param configs configuration to customize
     * @return the configuration customized by our generators.
     */
    public List<ImageConfiguration> customizeConfig(List<ImageConfiguration> configs) {
        runtimeMode = clusterAccess.resolveRuntimeMode(mode, log);
        log.info("Running in [[B]]%s[[B]] mode", runtimeMode.getLabel());
        if (runtimeMode == RuntimeMode.openshift) {
            log.info("Using [[B]]OpenShift[[B]] build with strategy [[B]]%s[[B]]", buildStrategy.getLabel());
        } else {
            log.info("Building Docker image in [[B]]Kubernetes[[B]] mode");
        }

        try {
            return GeneratorManager.generate(configs, getGeneratorContext(), false);
        } catch (MojoExecutionException e) {
            throw new IllegalArgumentException("Cannot extract generator config: " + e, e);
        }
    }

    protected String getLogPrefix() {
        return "k8s: ";
    }

    // ==================================================================================================

    // Get generator context
    private GeneratorContext getGeneratorContext() {
        return new GeneratorContext.Builder()
                .config(extractGeneratorConfig())
                .project(project)
                .logger(log)
                .runtimeMode(runtimeMode)
                .strategy(buildStrategy)
                .useProjectClasspath(useProjectClasspath)
                .artifactResolver(getFabric8ServiceHub().getArtifactResolverService())
                .build();
    }

    private JshiftServiceHub getFabric8ServiceHub() {
        return new JshiftServiceHub.Builder()
                .log(log)
                .clusterAccess(clusterAccess)
                .platformMode(mode)
                .repositorySystem(repositorySystem)
                .mavenProject(project)
                .build();
    }

    // Get generator config
    private ProcessorConfig extractGeneratorConfig() {
        try {
            return ProfileUtil.blendProfileWithConfiguration(ProfileUtil.GENERATOR_CONFIG, profile, ResourceUtil.getFinalResourceDir(resourceDir, environment), generator);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot extract generator config: " + e, e);
        }
    }

    // Get enricher context
    public EnricherContext getEnricherContext() {
        return new MavenEnricherContext.Builder()
                .project(project)
                .properties(project.getProperties())
                .session(session)
                .config(extractEnricherConfig())
                .images(getResolvedImages())
                .resources(resources)
                .log(log)
                .build();
    }

    // Get enricher config
    private ProcessorConfig extractEnricherConfig() {
        try {
            return ProfileUtil.blendProfileWithConfiguration(ProfileUtil.ENRICHER_CONFIG, profile, ResourceUtil.getFinalResourceDir(resourceDir, environment), enricher);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot extract enricher config: " + e, e);
        }
    }

    public ImagePullManager getImagePullManager(String imagePullPolicy, String autoPull) {
        return new ImagePullManager(getSessionCacheStore(), imagePullPolicy, autoPull);
    }

    private ImagePullManager.CacheStore getSessionCacheStore() {
        return new ImagePullManager.CacheStore() {
            @Override
            public String get(String key) {
                Properties userProperties = session.getUserProperties();
                return userProperties.getProperty(key);
            }

            @Override
            public void put(String key, String value) {
                Properties userProperties = session.getUserProperties();
                userProperties.setProperty(key, value);
            }
        };
    }

    // check for a run-java.sh dependency an extract the script to target/ if found
    private void executeBuildPlugins() {
        try {
            Enumeration<URL> dmpPlugins = Thread.currentThread().getContextClassLoader().getResources(DMP_PLUGIN_DESCRIPTOR);
            while (dmpPlugins.hasMoreElements()) {

                URL dmpPlugin = dmpPlugins.nextElement();
                File outputDir = getAndEnsureOutputDirectory();
                processDmpPluginDescription(dmpPlugin, outputDir);
            }
        } catch (IOException e) {
            log.error("Cannot load dmp-plugins from %s", DMP_PLUGIN_DESCRIPTOR);
        }
    }

    protected BuildService.BuildContext getBuildContext() throws MojoExecutionException {
        return new BuildService.BuildContext.Builder()
                .buildArgs(buildArgs)
                .mojoParameters(createMojoParameters())
                .registryConfig(getRegistryConfig(pullRegistry))
                .build();
    }

    protected MavenBuildContext createMojoParameters() {
        return new MavenBuildContext.Builder()
                .session(session)
                .project(project)
                .mavenFileFilter(mavenFileFilter)
                .mavenReaderFilter(mavenFilterReader)
                .settings(settings)
                .sourceDirectory(sourceDirectory)
                .outputDirectory(outputDirectory)
                .reactorProjects(reactorProjects)
                .archiveConfiguration(archive)
                .build();
    }

    // Get the reference date for the build. By default this is picked up
    // from an existing build date file. If this does not exist, the current date is used.
    protected Date getReferenceDate() throws IOException {
        Date referenceDate = EnvUtil.loadTimestamp(getBuildTimestampFile());
        return referenceDate != null ? referenceDate : new Date();
    }

    // used for storing a timestamp
    protected File getBuildTimestampFile() {
        return new File(project.getBuild().getDirectory(), DOCKER_BUILD_TIMESTAMP);
    }

    /**
     * Helper method to process an ImageConfiguration.
     *
     * @param hub          ServiceHub
     * @param aImageConfig ImageConfiguration that would be forwarded to build and tag
     * @throws DockerAccessException
     * @throws MojoExecutionException
     */
    private void processImageConfig(ServiceHub hub, ImageConfiguration aImageConfig) throws IOException, MojoExecutionException {
        BuildConfiguration buildConfig = aImageConfig.getBuildConfiguration();

        if (buildConfig != null) {
            if (buildConfig.getSkip()) {
                log.info("%s : Skipped building", aImageConfig.getDescription());
            } else {
                buildAndTag(hub, aImageConfig);
            }
        }
    }

    private File getAndEnsureOutputDirectory() {
        File outputDir = new File(new File(project.getBuild().getDirectory()), DOCKER_EXTRA_DIR);
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }
        return outputDir;
    }

    /**
     * Get the current build timestamp. this has either already been created by a previous
     * call or a new current date is created
     * @return timestamp to use
     */
    protected synchronized Date getBuildTimestamp() throws IOException {
        Date now = (Date) getPluginContext().get(CONTEXT_KEY_BUILD_TIMESTAMP);
        if (now == null) {
            now = getReferenceDate();
            getPluginContext().put(CONTEXT_KEY_BUILD_TIMESTAMP,now);
        }
        return now;
    }

    private void processDmpPluginDescription(URL pluginDesc, File outputDir) throws IOException {
        String line = null;
        try (LineNumberReader reader =
                     new LineNumberReader(new InputStreamReader(pluginDesc.openStream(), "UTF8"))) {
            line = reader.readLine();
            while (line != null) {
                if (line.matches("^\\s*#")) {
                    // Skip comments
                    continue;
                }
                callBuildPlugin(outputDir, line);
                line = reader.readLine();
            }
        } catch (ClassNotFoundException e) {
            // Not declared as dependency, so just ignoring ...
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            log.verbose("Found dmp-plugin %s but could not be called : %s",
                    line,
                    e.getMessage());
        }
    }

    protected RegistryService.RegistryConfig getRegistryConfig(String specificRegistry) throws MojoExecutionException {
        return new RegistryService.RegistryConfig.Builder()
                .settings(settings)
                .authConfig(authConfig != null ? authConfig.toMap() : null)
                .authConfigFactory(authConfigFactory)
                .skipExtendedAuth(skipExtendedAuth)
                .registry(specificRegistry != null ? specificRegistry : registry)
                .build();
    }

    private void callBuildPlugin(File outputDir, String buildPluginClass) throws ClassNotFoundException, NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        Class buildPlugin = Class.forName(buildPluginClass);
        try {
            Method method = buildPlugin.getMethod("addExtraFiles", File.class);
            method.invoke(null, outputDir);
            log.info("Extra files from %s extracted", buildPluginClass);
        } catch (NoSuchMethodException exp) {
            log.verbose("Build plugin %s does not support 'addExtraFiles' method", buildPluginClass);
        }
    }

    public void executeDockerBuild() throws MojoExecutionException, MojoFailureException {
        if (!skip) {
            log = new AnsiLogger(getLog(), useColor, verbose, !settings.getInteractiveMode(), getLogPrefix());
            authConfigFactory.setLog(log);
            imageConfigResolver.setLog(log);

            LogOutputSpecFactory logSpecFactory = new LogOutputSpecFactory(useColor, logStdout, logDate);

            ConfigHelper.validateExternalPropertyActivation(project, images);

            DockerAccess access = null;
            try {
                // The 'real' images configuration to use (configured images + externally resolved images)
                this.minimalApiVersion = initImageConfiguration(getBuildTimestamp());
                if (isDockerAccessRequired()) {
                    DockerAccessFactory.DockerAccessContext dockerAccessContext = getDockerAccessContext();
                    access = dockerAccessFactory.createDockerAccess(dockerAccessContext);
                }
                ServiceHub serviceHub = serviceHubFactory.createServiceHub(project, session, access, log, logSpecFactory);
                executeInternal(serviceHub);
            } catch (IOException exp) {
                logException(exp);
                throw new MojoExecutionException(exp.getMessage());
            } catch (MojoExecutionException exp) {
                logException(exp);
                throw exp;
            } finally {
                if (access != null) {
                    access.shutdown();
                }
            }
        }
    }

    // Resolve and customize image configuration
    private String initImageConfiguration(Date buildTimeStamp)  {
        // Resolve images
        resolvedImages = ConfigHelper.resolveImages(
                log,
                images,                  // Unresolved images
                (ImageConfiguration image) -> imageConfigResolver.resolve(image, project, session),
                filter,                   // A filter which image to process
                this);                     // customizer (can be overwritten by a subclass)

        // Check for simple Dockerfile mode
        File topDockerfile = new File(project.getBasedir(),"Dockerfile");
        if (topDockerfile.exists()) {
            if (resolvedImages.isEmpty()) {
                resolvedImages.add(createSimpleDockerfileConfig(topDockerfile));
            } else if (resolvedImages.size() == 1 && resolvedImages.get(0).getBuildConfiguration() == null) {
                resolvedImages.set(0, addSimpleDockerfileConfig(resolvedImages.get(0), topDockerfile));
            }
        }

        // Initialize configuration and detect minimal API version
        return ConfigHelper.initAndValidate(resolvedImages, apiVersion, new ImageNameFormatter(project, buildTimeStamp), log);
    }

    private ImageConfiguration createSimpleDockerfileConfig(File dockerFile) {
        // No configured name, so create one from maven GAV
        String name = EnvUtil.getPropertiesWithSystemOverrides(project).getProperty("docker.name");
        if (name == null) {
            // Default name group/artifact:version (or 'latest' if SNAPSHOT)
            name = "%g/%a:%l";
        }

        BuildConfiguration buildConfig =
                new BuildConfiguration.Builder()
                        .dockerFile(dockerFile.getPath())
                        .build();

        return new ImageConfiguration.Builder()
                .name(name)
                .buildConfig(buildConfig)
                .build();
    }

    private ImageConfiguration addSimpleDockerfileConfig(ImageConfiguration image, File dockerfile) {
        BuildConfiguration buildConfig =
                new BuildConfiguration.Builder()
                        .dockerFile(dockerfile.getPath())
                        .build();
        return new ImageConfiguration.Builder(image).buildConfig(buildConfig).build();
    }

    private void logException(Exception exp) {
        if (exp.getCause() != null) {
            log.error("%s [%s]", exp.getMessage(), exp.getCause().getMessage());
        } else {
            log.error("%s", exp.getMessage());
        }
    }

    protected DockerAccessFactory.DockerAccessContext getDockerAccessContext() {
        return new DockerAccessFactory.DockerAccessContext.Builder()
                .dockerHost(dockerHost)
                .certPath(certPath)
                .machine(machine)
                .maxConnections(maxConnections)
                .minimalApiVersion(minimalApiVersion)
                .projectProperties(project.getProperties())
                .skipMachine(skipMachine)
                .log(log)
                .build();
    }
}
