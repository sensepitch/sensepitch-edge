package org.sensepitch.edge;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SniHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import io.netty.util.DomainWildcardMappingBuilder;
import io.netty.util.Mapping;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.introspector.BeanAccess;
import org.yaml.snakeyaml.representer.Representer;

import javax.net.ssl.SSLException;
import java.io.File;
import java.io.IOException;

/**
 * Minimal HTTP/1.1 proxy without aggregation, with keep-alive and basic logging
 */
public class Proxy implements ProxyContext {

  ProxyLogger LOG = ProxyLogger.get(Proxy.class);

  private final ProxyMetrics metrics = new ProxyMetrics();
  private final ProxyConfig config;
  private final ConnectionConfig connectionConfig;
  private final MetricsBridge metricsBridge;
  private final AdmissionHandler admissionHandler;
  private final SslContext sslContext;
  private final Mapping<String, SslContext> sniMapping;
  private final RedirectHandler redirectHandler;
  // private final DownstreamHandler downstreamHandler;
  private final UpstreamRouter upstreamRouter;
  private final IpTraitsLookup ipTraitsLookup;
  private final EventLoopGroup eventLoopGroup;
  private final RequestLogger requestLogger;

  public Proxy(ProxyConfig proxyConfig) {
    dumpConfig(proxyConfig);
    if (proxyConfig.listen().connection() == null) {
      connectionConfig = ConnectionConfig.DEFAULT;
    } else {
      connectionConfig = proxyConfig.listen().connection();
    }
    eventLoopGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
    config = proxyConfig;
    metricsBridge = initializeMetrics();
    metricsBridge.expose(metrics);
    admissionHandler = new AdmissionHandler(proxyConfig.admission());
    metricsBridge.expose(admissionHandler);
    sslContext = initializeSslContext();
    sniMapping = initializeSniMapping();
    redirectHandler = proxyConfig.redirect() != null ? new RedirectHandler(proxyConfig.redirect()) : null;
    // downstreamHandler = new DownstreamHandler(proxyConfig);
    if (proxyConfig.upstream().size() == 1) {
      Upstream upstream = new Upstream(this, proxyConfig.upstream().getFirst());
      upstreamRouter = request -> upstream;
    } else {
      upstreamRouter = new HostBasedUpstreamRouter(this, proxyConfig.upstream());
    }
    requestLogger = new DistributingRequestLogger(
      new StandardOutRequestLogger(),
      metricsBridge.expose(new ExposeRequestCountPerStatusCodeHandler()));
    try {
      if (proxyConfig.ipLookup() != null) {
        ipTraitsLookup = new CombinedIpTraitsLookup(proxyConfig.ipLookup());
      } else {
        ipTraitsLookup = (builder, address) -> { };
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static void dumpConfig(ProxyConfig proxyConfig) {
    DumperOptions options = new DumperOptions();
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    options.setIndent(2);
    options.setPrettyFlow(true);
    Representer repr = new Representer(options);
    repr.getPropertyUtils().setBeanAccess(BeanAccess.FIELD);
    Yaml yaml = new Yaml(repr, options);
    System.out.println(yaml.dump(proxyConfig));
  }

  MetricsBridge initializeMetrics() {
    MetricsConfig metricsConfig = config.metrics();
    if (metricsConfig == null) {
      metricsConfig = MetricsConfig.DEFAULT;
    }
    PrometheusConfig prometheusConfig = metricsConfig.prometheus();
    if (prometheusConfig == null) {
      prometheusConfig = PrometheusConfig.DEFAULT;
    }
    if (!metricsConfig.enable()) {
      return new MetricsBridge.NoMetricsExposed();
    }
    return new PrometheusMetricsBridge(prometheusConfig);
  }

  SslContext initializeSslContext() {
    if (config.listen().ssl() == null) {
      return null;
    }
    return createSslContext(config.listen().ssl());
  }

  Mapping<String, SslContext> initializeSniMapping() {
    var domains = config.listen().domains();
    var snis = config.listen().sni();
    var defaultContext = initializeSslContext();
    if (defaultContext == null && snis != null) {
      defaultContext = createSslContext(snis.get(0).ssl());
    }
    var builder = new DomainWildcardMappingBuilder<>(defaultContext);
    if (domains != null) {
      for (String domain : domains) {
        String filePrefix = "/etc/letsencrypt/live/";
        builder.add(domain, createSslContext(new SslConfig(
          filePrefix + domain + "/privkey.pem",
          filePrefix + domain + "/fullchain.pem")));
      }
    }
    if (snis != null) {
      snis.forEach(sni -> builder.add(sni.domain(), createSslContext(sni.ssl())));
    }
    return builder.build();
  }

  SslContext createSslContext(SslConfig cfg) {
    try {
      return SslContextBuilder.forServer(new File(cfg.cert()), new File(cfg.key()))
        .clientAuth(ClientAuth.NONE)
        .sslProvider(SslProvider.OPENSSL)
        .build();
    } catch (SSLException e) {
      throw new RuntimeException(e);
    }
  }

  public void start() throws Exception {
    EventLoopGroup bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    EventLoopGroup workerGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
    try {
      ServerBootstrap sb = new ServerBootstrap();
      sb.group(bossGroup, workerGroup)
        .channel(NioServerSocketChannel.class)
        // .option(ChannelOption.SO_SNDBUF, 1 * 1024) // testing
        .childHandler(new ChannelInitializer<SocketChannel>() {
          @Override
          protected void initChannel(SocketChannel ch) {
            if (sniMapping != null) {
              ch.pipeline().addLast(new SniHandler(sniMapping));
            } else if (sslContext != null) {
              ch.pipeline().addLast(sslContext.newHandler(ch.alloc()));
            }
            ch.pipeline().addLast(new HttpServerCodec());
            ch.pipeline().addLast(new ClientTimeoutHandler(connectionConfig));
            ch.pipeline().addLast(new HttpServerKeepAliveHandler());
            // ch.pipeline().addLast(new LoggingHandler(LogLevel.INFO, ByteBufFormat.SIMPLE));
            ch.pipeline().addLast(new IpTraitsHandler(ipTraitsLookup));
//            ch.pipeline().addLast(new ReportIoErrorsHandler("downstream"));
            // TODO: check and sanitize host header
            ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
              // strip port from host
              @Override
              public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                if (msg instanceof HttpRequest rq) {
                  String host = rq.headers().get(HttpHeaderNames.HOST);
                  if (host != null) {
                    String []sa =  host.split(":");
                    host = sa[0];
                    rq.headers().set(HttpHeaderNames.HOST, host);
                  }
                }
                super.channelRead(ctx, msg);
              }
            });
            ch.pipeline().addLast(new RequestLoggingHandler(requestLogger));
            if (redirectHandler != null) {
              ch.pipeline().addLast(redirectHandler);
            }
            ch.pipeline().addLast(admissionHandler);
//            ch.pipeline().addLast(new LoggingHandler(LogLevel.INFO));
            ch.pipeline().addLast(new DownstreamHandler(upstreamRouter, metrics));
          }
        });
      int port = config.listen().port();
      ChannelFuture f = sb.bind(port).sync();
      System.out.println("Open SSL: " + OpenSsl.versionString());
      System.out.println("Proxy listening on port " + port);
      LOG.trace("tracing enabled");
      f.channel().closeFuture().sync();
    } finally {
      bossGroup.shutdownGracefully();
      workerGroup.shutdownGracefully();
    }
  }

  @Override
  public EventLoopGroup eventLoopGroup() {
    return eventLoopGroup;
  }

}

