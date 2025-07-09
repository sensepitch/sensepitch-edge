package org.sensepitch.edge;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

import java.net.InetSocketAddress;
import java.text.DecimalFormat;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * @author Jens Wilke
 */
public class StandardOutRequestLogger implements RequestLogger {

  private static final DateTimeFormatter CLF_TIME =
    DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.ENGLISH);

  @Override
  public void logRequest(ChannelHandlerContext downstreamCtx, RequestLogInfo info) {
    HttpRequest request = info.request();
    HttpResponse response = info.response();
    String remoteHost = "-";
    if (downstreamCtx.channel().remoteAddress() instanceof InetSocketAddress) {
      InetSocketAddress addr = (InetSocketAddress) downstreamCtx.channel().remoteAddress();
      remoteHost = addr.getAddress().getHostAddress();
    }
    String admissionToken = request.headers().get(AdmissionHandler.ADMISSION_TOKEN_HEADER);
    if (admissionToken == null) {
      admissionToken = "-";
    }
    String time = ZonedDateTime.now().format(CLF_TIME);
    String requestLine = request.method() + " " +
      request.uri() + " " +
      request.protocolVersion();
    String referer = request.headers().get(HttpHeaderNames.REFERER);
    if (referer == null) {
      referer = "-";
    }
    String ua = request.headers().get(HttpHeaderNames.USER_AGENT);
    if (ua == null) { ua = "-"; }
    String bypass = request.headers().get(BypassCheck.HEADER);
    if (bypass == null) { bypass = "-"; }
    String host = request.headers().get(HttpHeaderNames.HOST);
    if (host == null) { host = "-"; }
    int status = response.status().code();
    long delta = System.currentTimeMillis() - info.requestStartTime();
    // pattern "0.000" → at least one digit before the dot, exactly three after
    DecimalFormat df = new DecimalFormat("0.000");
    String deltaTime = df.format(delta / 1000.0);
    String ipLabels = IpTraitsHandler.extract(request);
    if (ipLabels == null) { ipLabels = "-"; }
    System.out.println("RQ0 " +
      host + " " + remoteHost + " \"" + ipLabels + "\" " + admissionToken + " ["+ time + "] " + requestLine + " " + status + " " + info.contentBytes() + " " + deltaTime + " \"" + bypass + "\" \"" + ua + "\" " + referer);
  }

}
