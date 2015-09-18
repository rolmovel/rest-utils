/**
 * Copyright 2014 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.confluent.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.jaxrs.base.JsonParseExceptionMapper;

import org.eclipse.jetty.jaas.JAASLoginService;
import org.eclipse.jetty.jaas.spi.AbstractLoginModule;
import org.eclipse.jetty.jaas.spi.UserInfo;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.DefaultIdentityService;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.handler.RequestLogHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.util.security.Credential;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.ServerProperties;
//import org.glassfish.jersey.server.validation.ValidationFeature;
import org.glassfish.jersey.servlet.ServletContainer;

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.security.auth.login.LoginException;
import javax.ws.rs.core.Configurable;

import io.confluent.common.metrics.JmxReporter;
import io.confluent.common.metrics.MetricConfig;
import io.confluent.common.metrics.Metrics;
import io.confluent.common.metrics.MetricsReporter;
import io.confluent.rest.exceptions.ConstraintViolationExceptionMapper;
import io.confluent.rest.exceptions.GenericExceptionMapper;
import io.confluent.rest.exceptions.WebApplicationExceptionMapper;
import io.confluent.rest.logging.Slf4jRequestLog;
import io.confluent.rest.metrics.MetricsResourceMethodApplicationListener;
import io.confluent.rest.metrics.MetricsSelectChannelConnector;
import io.confluent.rest.validation.JacksonMessageBodyProvider;

/**
 * A REST application. Extend this class and implement setupResources() to register REST
 * resources with the JAX-RS server. Use createServer() to get a fully-configured, ready to run
 * Jetty server.
 */
public abstract class Application<T extends RestConfig> {
  protected T config;
  protected Server server = null;
  protected CountDownLatch shutdownLatch = new CountDownLatch(1);
  protected Metrics metrics;

  public Application(T config) {
    this.config = config;
    MetricConfig metricConfig = new MetricConfig()
        .samples(config.getInt(RestConfig.METRICS_NUM_SAMPLES_CONFIG))
        .timeWindow(config.getLong(RestConfig.METRICS_SAMPLE_WINDOW_MS_CONFIG),
                    TimeUnit.MILLISECONDS);
    List<MetricsReporter> reporters =
        config.getConfiguredInstances(RestConfig.METRICS_REPORTER_CLASSES_CONFIG,
                                      MetricsReporter.class);
    reporters.add(new JmxReporter(config.getString(RestConfig.METRICS_JMX_PREFIX_CONFIG)));
    this.metrics = new Metrics(metricConfig, reporters, config.getTime());
  }

  /**
   * Register resources or additional Providers, ExceptionMappers, and other JAX-RS components with
   * the Jersey application. This, combined with your Configuration class, is where you can
   * customize the behavior of the application.
   */
  public abstract void setupResources(Configurable<?> config, T appConfig);

  /**
   * Returns a map of tag names to tag values to apply to metrics for this application.
   *
   * @return a Map of tags and values
   */
  public Map<String,String> getMetricsTags() {
    return new LinkedHashMap<String, String>();
  }

  /**
   * Configure and create the server.
   */
  public Server createServer() throws RestConfigException {
    // The configuration for the JAX-RS REST service
    ResourceConfig resourceConfig = new ResourceConfig();

    Map<String, String> metricTags = getMetricsTags();

    configureBaseApplication(resourceConfig, metricTags);
    setupResources(resourceConfig, getConfiguration());

    // Configure the servlet container
    ServletContainer servletContainer = new ServletContainer(resourceConfig);
    ServletHolder servletHolder = new ServletHolder(servletContainer);
    server = new Server() {
      @Override
      protected void doStop() throws Exception {
        super.doStop();
        Application.this.metrics.close();
        Application.this.onShutdown();
        Application.this.shutdownLatch.countDown();
      }
    };

    int port = getConfiguration().getInt(RestConfig.PORT_CONFIG);
    MetricsSelectChannelConnector connector = new MetricsSelectChannelConnector(
        port, metrics, "jetty", metricTags);
    server.setConnectors(new Connector[]{connector});

    ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
    context.setSecurityHandler(basicAuth("ldaploginmodule"));
    context.setContextPath("/");
    context.addServlet(servletHolder, "/*");

    RequestLogHandler requestLogHandler = new RequestLogHandler();
    Slf4jRequestLog requestLog = new Slf4jRequestLog();
    requestLog.setLoggerName(config.getString(RestConfig.REQUEST_LOGGER_NAME_CONFIG));
    requestLog.setLogLatency(true);
    requestLogHandler.setRequestLog(requestLog);

    HandlerCollection handlers = new HandlerCollection();
    handlers.setHandlers(new Handler[]{context, new DefaultHandler(), requestLogHandler});
    server.setHandler(handlers);

    int gracefulShutdownMs = getConfiguration().getInt(RestConfig.SHUTDOWN_GRACEFUL_MS_CONFIG);
    if (gracefulShutdownMs > 0) {
      server.setGracefulShutdown(gracefulShutdownMs);
    }
    server.setStopAtShutdown(true);

    return server;
  }

  public void configureBaseApplication(Configurable<?> config) {
    configureBaseApplication(config, null);
  }

  /**
   * Register standard components for a JSON REST application on the given JAX-RS configurable,
   * which can be either an ResourceConfig for a server or a ClientConfig for a Jersey-based REST
   * client.
   */
  public void configureBaseApplication(Configurable<?> config, Map<String, String> metricTags) {
    RestConfig restConfig = getConfiguration();

    ObjectMapper jsonMapper = getJsonMapper();
    JacksonMessageBodyProvider jsonProvider = new JacksonMessageBodyProvider(jsonMapper);
    config.register(jsonProvider);
    config.register(JsonParseExceptionMapper.class);

    //config.register(ValidationFeature.class);
    config.register(ConstraintViolationExceptionMapper.class);
    config.register(new WebApplicationExceptionMapper(restConfig));
    config.register(new GenericExceptionMapper(restConfig));

    config.register(new MetricsResourceMethodApplicationListener(metrics, "jersey",
                                                                 metricTags, restConfig.getTime()));

    config.property(ServerProperties.BV_SEND_ERROR_IN_RESPONSE, true);
  }

  public T getConfiguration() {
    return this.config;
  }

  /**
   * Gets a JSON ObjectMapper to use for (de)serialization of request/response entities. Override
   * this to configure the behavior of the serializer. One simple example of customization is to
   * set the INDENT_OUTPUT flag to make the output more readable. The default is a default
   * Jackson ObjectMapper.
   */
  protected ObjectMapper getJsonMapper() {
    return new ObjectMapper();
  }

  /**
   * Start the server (creating it if necessary).
   * @throws Exception
   */
  public void start() throws Exception {
    if (server == null) {
      createServer();
    }
    server.start();
  }

  /**
   * Wait for the server to exit, allowing existing requests to complete if graceful shutdown is
   * enabled and invoking the shutdown hook before returning.
   * @throws InterruptedException
   */
  public void join() throws InterruptedException {
    server.join();
    shutdownLatch.await();
  }

  /**
   * Request that the server shutdown.
   * @throws Exception
   */
  public void stop() throws Exception {
    server.stop();
  }

  /**
   * Shutdown hook that is invoked after the Jetty server has processed the shutdown request,
   * stopped accepting new connections, and tried to gracefully finish existing requests. At this
   * point it should be safe to clean up any resources used while processing requests.
   */
  public void onShutdown() {
  }

  private final SecurityHandler basicAuth(String realm) {

      JAASLoginService l = new JAASLoginService();
      l.setName("Test JAAS Realm");
      l.setLoginModuleName("ldaploginmodule");
      //l.setIdentityService(new DefaultIdentityService());
      //l.setRoleClassNames(new String[]{"org.eclipse.jetty.plus.jaas.JAASRole"});
      
      Constraint constraint = new Constraint();
      constraint.setName(Constraint.__BASIC_AUTH);
      constraint.setRoles(new String[]{"onemanagers"});
      constraint.setAuthenticate(true);
       
      ConstraintMapping cm = new ConstraintMapping();
      cm.setConstraint(constraint);
      cm.setPathSpec("/*");
      
      ConstraintSecurityHandler csh = new ConstraintSecurityHandler();
      csh.setAuthenticator(new BasicAuthenticator());
      csh.addConstraintMapping(cm);
      csh.setLoginService(l);
      csh.setRealmName("realm");
      
      return csh;
    
  }
  
}

