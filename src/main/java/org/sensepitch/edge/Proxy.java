package org.sensepitch.edge;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SniHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
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
public class Proxy {

  private final ProxyConfig config;
  private final MetricsBridge metricsBridge;
  private final AdmissionHandler admissionHandler;
  private final SslContext sslContext;
  private final Mapping<String, SslContext> sniMapping;
  private final RedirectHandler redirectHandler;
  // private final DownstreamHandler downstreamHandler;
  private final UpstreamRouter upstreamRouter;
  private final IpTraitsLookup ipTraitsLookup;

  public Proxy(ProxyConfig proxyConfig) {
    dumpConfig(proxyConfig);
    config = proxyConfig;
    metricsBridge = initializeMetrics();
    admissionHandler = new AdmissionHandler(proxyConfig.admission());
    metricsBridge.expose(admissionHandler);
    sslContext = initializeSslContext();
    sniMapping = initializeSniMapping();
    redirectHandler = proxyConfig.redirect() != null ? new RedirectHandler(proxyConfig.redirect()) : null;
    // downstreamHandler = new DownstreamHandler(proxyConfig);
    if (proxyConfig.upstream().size() == 1) {
      Upstream upstream = new Upstream(proxyConfig.upstream().getFirst());
      upstreamRouter = request -> upstream;
    } else {
      upstreamRouter = new HostBasedUpstreamRouter(proxyConfig.upstream());
    }
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
    if (config.metrics() == null || config.metrics().prometheus() == null) {
      return new MetricsBridge.NoMetricsExposed();
    }
    return new PrometheusMetricsBridge(config.metrics().prometheus());
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
        .build();
    } catch (SSLException e) {
      throw new RuntimeException(e);
    }
  }

  public void start() throws Exception {
    EventLoopGroup bossGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
    EventLoopGroup workerGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
    try {
      ServerBootstrap sb = new ServerBootstrap();
      sb.group(bossGroup, workerGroup)
        .channel(NioServerSocketChannel.class)
        .childHandler(new ChannelInitializer<SocketChannel>() {
          @Override
          protected void initChannel(SocketChannel ch) {
            if (sniMapping != null) {
              ch.pipeline().addLast(new SniHandler(sniMapping));
            } else if (sslContext != null) {
              ch.pipeline().addLast(sslContext.newHandler(ch.alloc()));
            }
            ch.pipeline().addLast(new HttpServerCodec());
            ch.pipeline().addLast(new HttpServerKeepAliveHandler());
            ch.pipeline().addLast(new IpTraitsHandler(ipTraitsLookup));
            ch.pipeline().addLast(new ReportIoErrorsHandler("downstream"));
            ch.pipeline().addLast(new RequestLoggingHandler());
            if (redirectHandler != null) {
              ch.pipeline().addLast(redirectHandler);
            }
            ch.pipeline().addLast(admissionHandler);
            ch.pipeline().addLast(new DownstreamHandler(upstreamRouter));
          }
        });
      int port = config.listen().port();
      ChannelFuture f = sb.bind(port).sync();
      System.out.println("Proxy listening on port " + port);
      f.channel().closeFuture().sync();
    } finally {
      bossGroup.shutdownGracefully();
      workerGroup.shutdownGracefully();
    }
  }

}

