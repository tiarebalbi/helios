/*
 * Copyright (c) 2014 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.helios.master;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Resources;
import com.google.common.util.concurrent.AbstractIdleService;

import com.codahale.metrics.MetricRegistry;
import com.spotify.helios.agent.KafkaClientProvider;
import com.spotify.helios.auth.AuthenticationPlugin;
import com.spotify.helios.auth.AuthenticationPlugin.ServerAuthentication;
import com.spotify.helios.auth.AuthenticationPluginLoader;
import com.spotify.helios.auth.AuthenticationRequestFilter;
import com.spotify.helios.auth.Authenticator;
import com.spotify.helios.auth.ServerAuthenticationConfig;
import com.spotify.helios.common.PomVersion;
import com.spotify.helios.common.VersionCompatibility;
import com.spotify.helios.master.http.VersionResponseFilter;
import com.spotify.helios.master.metrics.ReportingResourceMethodDispatchAdapter;
import com.spotify.helios.master.resources.DeploymentGroupResource;
import com.spotify.helios.master.resources.HistoryResource;
import com.spotify.helios.master.resources.HostsResource;
import com.spotify.helios.master.resources.JobsResource;
import com.spotify.helios.master.resources.MastersResource;
import com.spotify.helios.master.resources.VersionResource;
import com.spotify.helios.rollingupdate.RollingUpdateService;
import com.spotify.helios.serviceregistration.ServiceRegistrar;
import com.spotify.helios.serviceregistration.ServiceRegistration;
import com.spotify.helios.servicescommon.KafkaSender;
import com.spotify.helios.servicescommon.ManagedStatsdReporter;
import com.spotify.helios.servicescommon.ReactorFactory;
import com.spotify.helios.servicescommon.RiemannFacade;
import com.spotify.helios.servicescommon.RiemannHeartBeat;
import com.spotify.helios.servicescommon.RiemannSupport;
import com.spotify.helios.servicescommon.ServiceUtil;
import com.spotify.helios.servicescommon.ZooKeeperRegistrarService;
import com.spotify.helios.servicescommon.coordination.CuratorClientFactory;
import com.spotify.helios.servicescommon.coordination.DefaultZooKeeperClient;
import com.spotify.helios.servicescommon.coordination.Paths;
import com.spotify.helios.servicescommon.coordination.ZooKeeperClient;
import com.spotify.helios.servicescommon.coordination.ZooKeeperClientProvider;
import com.spotify.helios.servicescommon.coordination.ZooKeeperHealthChecker;
import com.spotify.helios.servicescommon.coordination.ZooKeeperModelReporter;
import com.spotify.helios.servicescommon.statistics.Metrics;
import com.spotify.helios.servicescommon.statistics.MetricsImpl;
import com.spotify.helios.servicescommon.statistics.NoopMetrics;
import com.sun.jersey.api.core.HttpRequestContext;

import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.handler.RequestLogHandler;
import org.eclipse.jetty.servlets.CrossOriginFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;

import javax.servlet.DispatcherType;
import javax.servlet.FilterRegistration;

import ch.qos.logback.access.jetty.RequestLogImpl;
import io.dropwizard.configuration.ConfigurationException;
import io.dropwizard.jetty.GzipFilterFactory;
import io.dropwizard.jetty.RequestLogFactory;
import io.dropwizard.logging.AppenderFactory;
import io.dropwizard.server.DefaultServerFactory;
import io.dropwizard.setup.Environment;

import static com.google.common.base.Charsets.UTF_8;
import static com.spotify.helios.servicescommon.ServiceRegistrars.createServiceRegistrar;

/**
 * The Helios master service.
 */
public class MasterService extends AbstractIdleService {

  private static final Logger log = LoggerFactory.getLogger(MasterService.class);

  private static final String LOGBACK_ACCESS_CONFIG = "logback-access.xml";
  private static final String LOGBACK_ACCESS_RESOURCE = "/" + LOGBACK_ACCESS_CONFIG;

  private final Server server;
  private final MasterConfig config;
  private final ServiceRegistrar registrar;
  private final ZooKeeperClient zooKeeperClient;
  private final ExpiredJobReaper expiredJobReaper;
  private final CuratorClientFactory curatorClientFactory;
  private final RollingUpdateService rollingUpdateService;

  private ZooKeeperRegistrarService zkRegistrar;

  /**
   * Create a new service instance. Initializes the control interface and the worker.
   *
   * @param config The service configuration.
   * @param environment The DropWizard environment.
   * @param curatorClientFactory The zookeeper curator factory.
   * @throws ConfigurationException If there is a problem with the DropWizard configuration.
   */
  public MasterService(final MasterConfig config, final Environment environment,
                       final CuratorClientFactory curatorClientFactory)
      throws ConfigurationException, IOException, InterruptedException {
    this.config = config;
    this.curatorClientFactory = curatorClientFactory;

    // Configure metrics
    // TODO (dano): do something with the riemann facade
    final MetricRegistry metricsRegistry = new MetricRegistry();
    final RiemannSupport riemannSupport = new RiemannSupport(metricsRegistry,
        config.getRiemannHostPort(), config.getName(), "helios-master");
    final RiemannFacade riemannFacade = riemannSupport.getFacade();
    log.info("Starting metrics");
    final Metrics metrics;
    if (config.isInhibitMetrics()) {
      metrics = new NoopMetrics();
    } else {
      metrics = new MetricsImpl(metricsRegistry);
      metrics.start();
      environment.lifecycle().manage(riemannSupport);
      environment.lifecycle().manage(new ManagedStatsdReporter(config.getStatsdHostPort(),
          "helios-master", metricsRegistry));
    }

    // Set up the master model
    this.zooKeeperClient = setupZookeeperClient(config);
    final ZooKeeperModelReporter modelReporter = new ZooKeeperModelReporter(
        riemannFacade, metrics.getZooKeeperMetrics());
    final ZooKeeperClientProvider zkClientProvider = new ZooKeeperClientProvider(
        zooKeeperClient, modelReporter);
    final KafkaClientProvider kafkaClientProvider = new KafkaClientProvider(
        config.getKafkaBrokers());

    // Create state directory, if necessary
    final Path stateDirectory = config.getStateDirectory().toAbsolutePath().normalize();
    if (!Files.exists(stateDirectory)) {
      try {
        Files.createDirectories(stateDirectory);
      } catch (IOException e) {
        log.error("Failed to create state directory: {}", stateDirectory, e);
        throw Throwables.propagate(e);
      }
    }

    // Make a KafkaProducer for events that can be serialized to an array of bytes,
    // and wrap it in our KafkaSender.
    final KafkaSender kafkaSender = new KafkaSender(
        kafkaClientProvider.getProducer(new StringSerializer(), new ByteArraySerializer()));

    final ZooKeeperMasterModel model =
        new ZooKeeperMasterModel(zkClientProvider, config.getName(), kafkaSender);

    final ZooKeeperHealthChecker zooKeeperHealthChecker = new ZooKeeperHealthChecker(
        zooKeeperClient, Paths.statusMasters(), riemannFacade, TimeUnit.MINUTES, 2);

    environment.lifecycle().manage(zooKeeperHealthChecker);
    environment.healthChecks().register("zookeeper", zooKeeperHealthChecker);
    environment.lifecycle().manage(new RiemannHeartBeat(TimeUnit.MINUTES, 2, riemannFacade));

    // Set up service registrar
    this.registrar = createServiceRegistrar(config.getServiceRegistrarPlugin(),
                                            config.getServiceRegistryAddress(),
                                            config.getDomain());

    // Set up reaping of expired jobs
    this.expiredJobReaper = ExpiredJobReaper.newBuilder()
        .setMasterModel(model)
        .build();

    // Set up rolling update service
    final ReactorFactory reactorFactory = new ReactorFactory();
    this.rollingUpdateService = new RollingUpdateService(model, reactorFactory);

    // Set up http server
    environment.servlets()
        .addFilter("VersionResponseFilter", new VersionResponseFilter(metrics.getMasterMetrics()))
        .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), true, "/*");
    environment.jersey().register(
        new ReportingResourceMethodDispatchAdapter(metrics.getMasterMetrics()));
    environment.jersey().register(new JobsResource(model, metrics.getMasterMetrics()));
    environment.jersey().register(new HistoryResource(model, metrics.getMasterMetrics()));
    environment.jersey().register(new HostsResource(model));
    environment.jersey().register(new MastersResource(model));
    environment.jersey().register(new VersionResource());
    environment.jersey().register(new UserProvider());
    environment.jersey().register(new DeploymentGroupResource(model));

    // Set up authentication
    if (config.isAuthenticationEnabled()) {
      setupAuthentication(environment, config);
    }

    final DefaultServerFactory serverFactory = ServiceUtil.createServerFactory(
        config.getHttpEndpoint(), config.getAdminPort(), false);

    final RequestLogFactory requestLog = new RequestLogFactory();
    requestLog.setAppenders(ImmutableList.<AppenderFactory>of());
    serverFactory.setRequestLogFactory(requestLog);

    // Enable CORS headers
    final FilterRegistration.Dynamic cors = environment.servlets()
        .addFilter("CORS", CrossOriginFilter.class);

    // Configure CORS parameters
    cors.setInitParameter("allowedOrigins", "*");
    cors.setInitParameter("allowedHeaders", "X-Requested-With,Content-Type,Accept,Origin");
    cors.setInitParameter("allowedMethods", "OPTIONS,GET,PUT,POST,DELETE,HEAD");

    // Add URL mapping
    cors.addMappingForUrlPatterns(EnumSet.allOf(DispatcherType.class), true, "/*");

    // Enable gzip compression for POST and GET requests. Default is GET only.
    final GzipFilterFactory gzip = new GzipFilterFactory();
    gzip.setIncludedMethods(ImmutableSet.of("GET", "POST"));
    serverFactory.setGzipFilterFactory(gzip);

    this.server = serverFactory.build(environment);

    setUpRequestLogging(stateDirectory);
  }

  private void setupAuthentication(final Environment environment, final MasterConfig config) {
    final ServerAuthenticationConfig authConfig = config.getAuthenticationConfig();

    final AuthenticationPlugin<?> authPlugin = AuthenticationPluginLoader.load(authConfig);
    log.info("loaded authentication plugin {} for scheme {}",
        authPlugin.getClass().getName(),
        authConfig.getEnabledScheme());

    final ServerAuthentication<?> authentication = authPlugin.serverAuthentication();
    final Authenticator authenticator = authPlugin.serverAuthentication().authenticator();
    final Predicate<HttpRequestContext> isRequired = authenticationRequired(authConfig);

    final AuthenticationRequestFilter filter =
        new AuthenticationRequestFilter(authenticator, authPlugin.schemeName(), isRequired);

    // setting up filters in Jersey 1.x is convoluted:
    environment.jersey().getResourceConfig().getContainerRequestFilters().add(filter);

    // register any additional resources needed by the plugin
    authentication.registerAdditionalJerseyComponents(environment.jersey());
  }

  private Predicate<HttpRequestContext> authenticationRequired(ServerAuthenticationConfig config) {
    if (config.isEnabledForAllVersions()) {
      return Predicates.alwaysTrue();
    }

    final PomVersion minVersion = PomVersion.parse(config.getMinimumRequiredVersion());

    return new Predicate<HttpRequestContext>() {
      @Override
      public boolean apply(final HttpRequestContext input) {

        final String versionString =
            input.getHeaderValue(VersionCompatibility.HELIOS_VERSION_HEADER);
        // if we don't know the version, jump into the "you need to authenticate flow" as opposed to
        // letting them through
        if (versionString == null) {
          return true;
        }

        final PomVersion clientVersion;
        try {
          clientVersion = PomVersion.parse(versionString);
        } catch (RuntimeException e) {
          // malformed version header
          log.warn("isAuthenticationRequired: rejecting request due to malformed "
                   + "client version header: {}", versionString);
          return true;
        }

        // -1 = minVersion is less than input (i.e. input is greater than minVersion), 0 = equal
        // any input version above the minVersion requires auth
        return minVersion.compareTo(clientVersion) < 1;
      }
    };
  }

  private void setUpRequestLogging(final Path stateDirectory) {
    // Set up request logging
    final Handler originalHandler = server.getHandler();
    final HandlerCollection handlerCollection;
    if (originalHandler instanceof HandlerCollection) {
      handlerCollection = (HandlerCollection) originalHandler;
    } else {
      handlerCollection = new HandlerCollection();
      handlerCollection.addHandler(originalHandler);
    }

    final RequestLogHandler requestLogHandler = new RequestLogHandler();
    final RequestLogImpl requestLog = new RequestLogImpl();
    requestLog.setQuiet(true);

    if (stateDirectory.resolve(LOGBACK_ACCESS_CONFIG).toFile().exists()) {
      requestLog.setFileName(stateDirectory.resolve(LOGBACK_ACCESS_CONFIG).toString());
    } else if (this.getClass().getResource(LOGBACK_ACCESS_RESOURCE) != null) {
      requestLog.setResource(LOGBACK_ACCESS_RESOURCE);
    }

    requestLogHandler.setRequestLog(requestLog);
    handlerCollection.addHandler(requestLogHandler);
    server.setHandler(handlerCollection);
  }

  @Override
  protected void startUp() throws Exception {
    logBanner();
    if (!config.getNoZooKeeperMasterRegistration()) {
      zkRegistrar.startAsync().awaitRunning();
    }
    expiredJobReaper.startAsync().awaitRunning();
    rollingUpdateService.startAsync().awaitRunning();
    try {
      server.start();
    } catch (Exception e) {
      log.error("Unable to start server, shutting down", e);
      server.stop();
    }

    final ServiceRegistration serviceRegistration = ServiceRegistration.newBuilder()
        .endpoint("helios", "http", config.getHttpEndpoint().getPort(),
            config.getDomain(), config.getName())
        .build();
    registrar.register(serviceRegistration);
  }

  @Override
  protected void shutDown() throws Exception {
    server.stop();
    server.join();
    registrar.close();
    rollingUpdateService.stopAsync().awaitTerminated();
    expiredJobReaper.stopAsync().awaitTerminated();
    zkRegistrar.stopAsync().awaitTerminated();
    zooKeeperClient.close();
  }

  private void logBanner() {
    try {
      final String banner = Resources.toString(Resources.getResource("master-banner.txt"), UTF_8);
      log.info("\n{}", banner);
    } catch (IllegalArgumentException | IOException ignored) {
    }
  }

  /**
   * Create a Zookeeper client and create the control and state nodes if needed.
   *
   * @param config The service configuration.
   * @return A zookeeper client.
   */
  private ZooKeeperClient setupZookeeperClient(final MasterConfig config) {
    final RetryPolicy zooKeeperRetryPolicy = new ExponentialBackoffRetry(1000, 3);
    final CuratorFramework curator = curatorClientFactory.newClient(
        config.getZooKeeperConnectionString(),
        config.getZooKeeperSessionTimeoutMillis(),
        config.getZooKeeperConnectionTimeoutMillis(),
        zooKeeperRetryPolicy,
        config.getZooKeeperNamespace());
    final ZooKeeperClient client =
        new DefaultZooKeeperClient(curator, config.getZooKeeperClusterId());
    client.start();
    zkRegistrar = new ZooKeeperRegistrarService(
        client, new MasterZooKeeperRegistrar(config.getName()));

    return client;
  }
}
